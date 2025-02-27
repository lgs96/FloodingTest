#!/usr/bin/env python3

import os
import sys
import socket
import time
import datetime
import threading
import multiprocessing
import json  # Needed for JSON encoding/decoding
from http.server import BaseHTTPRequestHandler, HTTPServer
import urllib.parse as urlparse
#from webrtc_analyzer import analyze_webrtc_dump
import math

###########################################################
# Configurable ports via command line arguments
###########################################################
CONTROL_PORT = 8889
DATA_PORT = 8890
if len(sys.argv) >= 3:
    CONTROL_PORT = int(sys.argv[1])
    DATA_PORT = int(sys.argv[2])

###########################################################
# Multiprocessing shared variables
###########################################################
manager = multiprocessing.Manager()

# A global event to signal all child processes to stop
stop_event = manager.Event()

# TCP control
can_send_tcp = manager.Value('b', False)   # boolean (True/False)
tcp_rate_mbps = manager.Value('d', 0.0)      # double (float) for rate

# UDP control
can_send_udp = manager.Value('b', False)
udp_rate_mbps = manager.Value('d', 0.0)

###########################################################
# Logging and file-handling in the main process (optional)
###########################################################
current_log_filename = None
current_protocol = None

def clean_nan(obj):
    if isinstance(obj, float) and math.isnan(obj):
        return None
    elif isinstance(obj, dict):
        return {k: clean_nan(v) for k, v in obj.items()}
    elif isinstance(obj, list):
        return [clean_nan(x) for x in obj]
    else:
        return obj

def start_new_log_file(protocol: str):
    """
    Create a new CSV log file for the given protocol ('tcp' or 'udp'),
    write the CSV header, and track it globally.
    """
    global current_log_filename, current_protocol
    # Close any previous log file first
    if current_log_filename:
        print(f"[{current_protocol.upper() if current_protocol else 'UNKNOWN'}] Closed previous log file 'logs/{current_log_filename}'")
    
    now_str = datetime.datetime.now().strftime("%Y_%m_%d_%H_%M_%S")
    current_log_filename = f"{protocol}_{now_str}.csv"
    current_protocol = protocol

    os.makedirs("logs", exist_ok=True)
    path = os.path.join("logs", current_log_filename)
    with open(path, "w") as f:
        f.write("client_timestamp,client_epoch_ms,server_timestamp,server_epoch_ms,throughput\n")  # Updated CSV header
    print(f"[{protocol.upper()}] Created log file 'logs/{current_log_filename}'")

def append_csv_logs(csv_data: str):
    """
    Append CSV logs to current_log_filename if set,
    or create an 'unknown' file if none set.
    """
    global current_log_filename, current_protocol

    if not current_log_filename:
        now_str = datetime.datetime.now().strftime("%Y_%m_%d_%H_%M_%S")
        current_log_filename = f"unknown_{now_str}.csv"
        current_protocol = "unknown"
        os.makedirs("logs", exist_ok=True)
        path = os.path.join("logs", current_log_filename)
        with open(path, "w") as f:
            f.write("client_timestamp,client_epoch_ms,server_timestamp,server_epoch_ms,throughput\n")  # Updated CSV header

    path = os.path.join("logs", current_log_filename)
    with open(path, "a") as f:
        f.write(csv_data + "\n")


###########################################################
# TCP Data Process (no asyncio, just sockets + sleeps)
###########################################################
def run_tcp_data_process(stop_event, can_send_tcp, tcp_rate_mbps, port):
    """
    High-performance TCP server that:
    1) Works reliably on both high-bandwidth and cellular connections
    2) Achieves much higher throughput than the original version
    3) Adapts to network conditions to maintain stability
    """
    print(f"[TCP-PROC] Starting optimized TCP server on port {port}")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    
    # Optimize socket buffers for high throughput
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 16777216)  # 16MB send buffer
    sock.setsockopt(socket.SOL_TCP, socket.TCP_NODELAY, 1)  # Disable Nagle's algorithm
    
    try:
        sock.bind(('', port))
        sock.listen(5)  # Increase backlog for multiple connections
        sock.settimeout(1.0)
    except Exception as e:
        print(f"[TCP-PROC] Bind/listen error: {e}")
        return

    # Create payload chunks of different sizes for better adaptability
    payload_sizes = {
        'small': 128 * 1024,     # 128KB
        'medium': 512 * 1024,    # 512KB  
        'large': 1024 * 1024,    # 1MB
        'xlarge': 4 * 1024 * 1024 # 4MB
    }
    
    payload_chunks = {size: b"\x00" * payload_sizes[size] for size in payload_sizes}
    
    while not stop_event.is_set():
        conn = None
        addr = None
        try:
            # Try to accept a client
            try:
                conn, addr = sock.accept()
                conn.settimeout(1.0)
                
                # Also set large buffer on the connection socket
                conn.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 16777216)  # 16MB send buffer
                conn.setsockopt(socket.SOL_TCP, socket.TCP_NODELAY, 1)
                
                print(f"[TCP-PROC] Accepted connection from {addr}")
            except socket.timeout:
                continue
            except Exception as e:
                print(f"[TCP-PROC] accept() error: {e}")
                time.sleep(0.1)
                continue

            # Initialize performance tracking variables
            last_throughput_check = time.time()
            bytes_sent_since_check = 0
            consecutive_timeouts = 0
            consecutive_success = 0
            
            # Start with medium chunks and adapt based on connection quality
            current_chunk_size = 'medium'
            
            # Burst parameters - send multiple chunks per timing cycle for higher throughput
            burst_size = 1  # Start with sending 1 chunk per cycle
            max_burst_size = 8  # Maximum number of chunks to send in a burst
            
            while not stop_event.is_set():
                if not can_send_tcp.value:
                    # If not allowed to send, just sleep briefly
                    time.sleep(0.05)
                    continue

                # Get the current target rate
                rate = tcp_rate_mbps.value
                
                # Choose payload size based on rate and connection quality
                current_payload = payload_chunks[current_chunk_size]
                
                # Calculate sending parameters
                if rate <= 0:  # Unlimited rate mode
                    # Use adaptive burst mode for maximum throughput
                    send_interval = 0.001  # Small base interval (1ms)
                    
                    # Determine burst size based on success rate
                    if consecutive_timeouts > 0:
                        burst_size = max(1, burst_size // 2)  # Halve the burst size on timeout
                    elif consecutive_success > 10:
                        burst_size = min(max_burst_size, burst_size + 1)  # Gradually increase burst size
                        consecutive_success = 0  # Reset counter
                else:
                    # Calculate how many bytes we need to send per second
                    target_Bps = (rate * 1_000_000) / 8.0
                    
                    # Calculate how many chunks we need to send per second
                    chunks_per_second = target_Bps / len(current_payload)
                    
                    # Calculate time per chunk
                    send_interval = 1.0 / chunks_per_second if chunks_per_second > 0 else 0
                    
                    # For rate-limited mode, use controlled burst sizes
                    if rate < 100:  # Low rate
                        burst_size = 1
                    elif rate < 500:  # Medium rate
                        burst_size = 2
                    else:  # High rate
                        burst_size = 4
                
                # Send burst of data
                start_time = time.time()
                success = True
                
                for _ in range(burst_size):
                    try:
                        conn.sendall(current_payload)
                        bytes_sent_since_check += len(current_payload)
                        consecutive_success += 1
                        consecutive_timeouts = 0
                    except socket.timeout:
                        print(f"[TCP-PROC] Send timeout - reducing chunk size")
                        consecutive_timeouts += 1
                        consecutive_success = 0
                        success = False
                        
                        # Adapt chunk size on timeout
                        if current_chunk_size == 'xlarge':
                            current_chunk_size = 'large'
                        elif current_chunk_size == 'large':
                            current_chunk_size = 'medium'
                        elif current_chunk_size == 'medium':
                            current_chunk_size = 'small'
                            
                        # Backoff on timeout
                        time.sleep(0.01 * (2 ** min(consecutive_timeouts, 3)))
                        break
                        
                    except (BrokenPipeError, ConnectionResetError):
                        print("[TCP-PROC] Client disconnected.")
                        success = False
                        break
                        
                    except Exception as e:
                        print(f"[TCP-PROC] sendall error: {e}")
                        consecutive_timeouts += 1
                        consecutive_success = 0
                        success = False
                        time.sleep(0.01)
                        break
                
                # If burst completed successfully and we're in rate-limited mode,
                # sleep for the appropriate interval
                if success and send_interval > 0:
                    elapsed = time.time() - start_time
                    sleep_time = max(0, send_interval - elapsed)
                    if sleep_time > 0.001:  # Only sleep for meaningful intervals
                        time.sleep(sleep_time)
                
                # Periodically calculate and log actual throughput
                now = time.time()
                if now - last_throughput_check >= 1.0:  # Log every second
                    actual_duration = now - last_throughput_check
                    actual_mbps = (bytes_sent_since_check * 8) / (actual_duration * 1_000_000)
                    
                    # Adapt chunk size based on performance
                    if consecutive_success > 20 and actual_mbps > 500 and current_chunk_size == 'small':
                        current_chunk_size = 'medium'
                        print(f"[TCP-PROC] Connection stable, increasing chunk size to {current_chunk_size}")
                    elif consecutive_success > 30 and actual_mbps > 1000 and current_chunk_size == 'medium':
                        current_chunk_size = 'large'
                        print(f"[TCP-PROC] Connection very stable, increasing chunk size to {current_chunk_size}")
                    elif consecutive_success > 50 and actual_mbps > 2000 and current_chunk_size == 'large':
                        current_chunk_size = 'xlarge'
                        print(f"[TCP-PROC] Connection excellent, increasing chunk size to {current_chunk_size}")
                    
                    print(f"[TCP-PROC] Actual throughput: {actual_mbps:.2f} Mbps (Target: {rate} Mbps, Chunk: {current_chunk_size}, Burst: {burst_size})")
                    last_throughput_check = now
                    bytes_sent_since_check = 0

        except Exception as e:
            print(f"[TCP-PROC] Error in main loop: {e}")
        finally:
            if conn:
                try:
                    conn.close()
                except:
                    pass

    sock.close()
    print("[TCP-PROC] Exiting process.")


###########################################################
# UDP Data Process (no asyncio, just sockets + sleeps)
###########################################################
def run_udp_data_process(stop_event, can_send_udp, udp_rate_mbps, port):
    """
    Child process function that:
      1) Binds a UDP socket on `port`
      2) Waits for the first packet from the phone to get `phone_addr`
      3) Sends data in bursts with `udp_rate_mbps` if `can_send_udp` is True
    """
    print(f"[UDP-PROC] Starting UDP server on port {port}")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        sock.bind(('', port))
    except Exception as e:
        print(f"[UDP-PROC] Bind error: {e}")
        return

    sock.settimeout(1.0)
    phone_addr = None
    payload = b"\x00" * 1400  # typical MTU-sized chunk

    while not stop_event.is_set():
        if phone_addr is None:
            # Wait to receive first handshake from phone
            try:
                data, addr = sock.recvfrom(2048)
                phone_addr = addr
                print(f"[UDP-PROC] Received handshake from {phone_addr}, start sending.")
            except socket.timeout:
                continue
            except Exception as e:
                print(f"[UDP-PROC] recvfrom error: {e}")
                time.sleep(0.1)
            continue

        # If we have phone_addr but can't send, just wait
        if not can_send_udp.value:
            phone_addr = None
            time.sleep(0.05)
            continue

        # Check current rate
        rate = udp_rate_mbps.value
        if rate > 0:
            target_Bps = (rate * 1_000_000) / 8.0
        else:
            # default to 3Mbps if user sets 0, or do infinite
            target_Bps = (3.0 * 1_000_000) / 8.0

        before_send = time.time()
        # Send one packet
        try:
            sock.sendto(payload, phone_addr)
        except Exception as e:
            print(f"[UDP-PROC] sendto error: {e}")
            time.sleep(0.1)
            continue

        elapsed = time.time() - before_send
        ideal_time = len(payload) / target_Bps
        leftover = ideal_time - elapsed
        if leftover > 0:
            time.sleep(leftover)

    sock.close()
    print("[UDP-PROC] Exiting process.")


###########################################################
# Control-plane HTTP server (runs in main process)
###########################################################
class ControlRequestHandler(BaseHTTPRequestHandler):
    """
    HTTP interface for control commands, file uploads, and WebRTC dump analysis.
    Endpoints:
      - GET /ptp_sync            (new endpoint for PTP time synchronization)
      - GET /start_tcp?rate=5
      - GET /stop_tcp
      - GET /start_udp?rate=10
      - GET /stop_udp
      - POST /csv_logs         (body = raw CSV lines)
      - POST /upload_file      (body = file data; optional header X-Filename)
      - POST /analyze_dump     (body = WebRTC dump file to be analyzed)
    """

    def do_GET(self):
        parsed = urlparse.urlparse(self.path)
        path = parsed.path
        query = urlparse.parse_qs(parsed.query)

        if path == "/ptp_sync":
            # Implementation of the SYNC + FOLLOW_UP message pattern
            t2 = int(time.time() * 1000000)  # Server receive time in microseconds
            
            # Create the FOLLOW_UP response with the server receive timestamp
            response_obj = {
                "t2": t2
            }
            
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(response_obj).encode())
            print(f"[CONTROL] PTP SYNC message received, responded with t2={t2}µs")

        elif path == "/start_tcp":
            rate_str = query.get("rate", ["0"])[0]
            try:
                # Extract numeric part from rate string (e.g. "500Mbps" -> 500)
                rate_val = float(''.join(filter(lambda c: c.isdigit() or c == '.', rate_str)))
            except:
                rate_val = 0.0
            tcp_rate_mbps.value = rate_val
            can_send_tcp.value = True
            start_new_log_file("tcp")
            print(f"[CONTROL] START_TCP with rate {rate_val} Mbps")
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"OK: started TCP\n")

        elif path == "/stop_tcp":
            can_send_tcp.value = False
            tcp_rate_mbps.value = 0.0
            print("[CONTROL] STOP_TCP => done")
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"OK: stopped TCP\n")

        elif path == "/start_udp":
            rate_str = query.get("rate", ["0"])[0]
            try:
                # Extract numeric part from rate string (e.g. "500Mbps" -> 500)
                rate_val = float(''.join(filter(lambda c: c.isdigit() or c == '.', rate_str)))
            except:
                rate_val = 0.0
            udp_rate_mbps.value = rate_val
            can_send_udp.value = True
            start_new_log_file("udp")
            print(f"[CONTROL] START_UDP with rate {rate_val} Mbps")
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"OK: started UDP\n")

        elif path == "/stop_udp":
            can_send_udp.value = False
            udp_rate_mbps.value = 0.0
            print("[CONTROL] STOP_UDP => done")
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"OK: stopped UDP\n")

        else:
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"Unknown GET command\n")

    def do_POST(self):
        parsed = urlparse.urlparse(self.path)
        path = parsed.path

        if path == "/ptp_delay_req":
            # Implementation of the DELAY_REQ + DELAY_RESP message pattern
            content_length = int(self.headers.get('Content-Length', '0'))
            post_data = self.rfile.read(content_length).decode('utf-8')
            request_obj = json.loads(post_data)
            
            # Client's timestamps
            t1 = request_obj.get("t1")
            t2 = request_obj.get("t2")
            
            # Server's DELAY_REQ receive timestamp
            t4 = int(time.time() * 1000000)  # Microseconds
            
            # Create the DELAY_RESP response
            response_obj = {
                "t4": t4
            }
            
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(response_obj).encode())
            print(f"[CONTROL] PTP DELAY_REQ processed: t1={t1}µs, t2={t2}µs, t4={t4}µs")

        elif path == "/upload_file":
            content_length = int(self.headers.get('Content-Length', '0'))
            file_data = self.rfile.read(content_length)
            # Optionally, get the file name from a custom header
            file_name = self.headers.get("X-Filename")
            if not file_name:
                file_name = "uploaded_" + datetime.datetime.now().strftime("%Y_%m_%d_%H_%M_%S")
            os.makedirs("uploads", exist_ok=True)
            file_path = os.path.join("uploads", file_name)
            with open(file_path, "wb") as f:
                f.write(file_data)
            result = f"Received file '{file_name}' with {len(file_data)} bytes"
            print(f"[UPLOAD] {result}")
            self.send_response(200)
            self.end_headers()
            self.wfile.write(result.encode())

        elif path == "/csv_logs":
            content_length = int(self.headers.get('Content-Length', '0'))
            post_data = self.rfile.read(content_length).decode('utf-8')
            if post_data.strip():
                lines_to_append = []
                
                # Get the current time for reference
                current_time = time.time() * 1000  # milliseconds
                
                for line in post_data.strip().splitlines():
                    parts = line.split(',')
                    if len(parts) >= 2:  # Make sure line has enough parts
                        try:
                            # Extract the client timestamp
                            client_epoch_ms = float(parts[1])
                            
                            # Check if the timestamp is within the last 60 seconds
                            # This filters out old logs from previous sessions
                            if current_time - client_epoch_ms < 60000:  # 60 seconds in ms
                                lines_to_append.append(line)
                            else:
                                print(f"[CONTROL] Filtered out old log entry: {line[:50]}...")
                        except (ValueError, IndexError):
                            # If we can't parse the timestamp, append it anyway
                            lines_to_append.append(line)
                    else:
                        # Malformed line - skip it
                        print(f"[CONTROL] Skipping malformed log line: {line[:50]}...")
                
                # Append the filtered lines
                for line in lines_to_append:
                    append_csv_logs(line)
                    
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"OK: csv logs appended\n")

        elif path == "/analyze_dump":
            # New endpoint: process an uploaded WebRTC dump file and return analysis results.
            # Get query parameters – in particular, the designated folder
            parsed = urlparse.urlparse(self.path)
            query = urlparse.parse_qs(parsed.query)
            designated_folder = query.get("folder", [""])[0]
            if not designated_folder:
                designated_folder = "rts_log"  # default folder if not provided

            content_length = int(self.headers.get('Content-Length', '0'))
            if content_length == 0:
                self.send_response(400)
                self.end_headers()
                self.wfile.write(b"No file uploaded")
                return

            dump_data = self.rfile.read(content_length)
            # Write dump data to a temporary file
            temp_filename = "temp_webrtc_dump.json"
            with open(temp_filename, "wb") as f:
                f.write(dump_data)
            try:
                analysis_result = 0#analyze_webrtc_dump(temp_filename, specific_log = designated_folder)
            except Exception as e:
                analysis_result = {"error": str(e)}
            finally:
                os.remove(temp_filename)

            # (Optional) Clean the result to replace NaN values, etc.

            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            response_json = json.dumps(analysis_result)
            self.wfile.write(response_json.encode())

        else:
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"Unknown POST command\n")

    def log_message(self, format, *args):
        # Override to prevent logging to stderr.
        pass


def control_server_main():
    """
    Run the HTTP server for control commands in the main process.
    """
    httpd = HTTPServer(('0.0.0.0', CONTROL_PORT), ControlRequestHandler)
    httpd.timeout = 0.1  # poll frequently
    print(f"[CONTROL] HTTP server on port {CONTROL_PORT}, data port={DATA_PORT}")

    # Keep handling requests until we set stop_event
    while not stop_event.is_set():
        httpd.handle_request()

    httpd.server_close()
    print("[CONTROL] control_server stopped.")


###########################################################
# Main entry point
###########################################################
if __name__ == "__main__":
    print(f"Starting server. CONTROL_PORT={CONTROL_PORT}, DATA_PORT={DATA_PORT}")

    # 1) Start the TCP data-plane process
    tcp_process = multiprocessing.Process(
        target=run_tcp_data_process,
        args=(stop_event, can_send_tcp, tcp_rate_mbps, DATA_PORT),
        daemon=True
    )
    tcp_process.start()

    # 2) Start the UDP data-plane process
    udp_process = multiprocessing.Process(
        target=run_udp_data_process,
        args=(stop_event, can_send_udp, udp_rate_mbps, DATA_PORT),
        daemon=True
    )
    udp_process.start()

    # 3) Run the control server (HTTP) in the main process
    try:
        control_server_main()
    except KeyboardInterrupt:
        print("KeyboardInterrupt received in main process.")
    finally:
        # Signal children to stop
        stop_event.set()

        # Wait for them to exit
        tcp_process.join()
        udp_process.join()

        print("Server fully shut down.")