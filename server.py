#!/usr/bin/env python3
import os
import sys
import socket
import threading
import time
import datetime

# Default ports if none given
CONTROL_PORT = 8889
DATA_PORT = 8890

if len(sys.argv) >= 3:
    CONTROL_PORT = int(sys.argv[1])
    DATA_PORT = int(sys.argv[2])

# Events and rate variables
stop_server_event = threading.Event()         # entire server shutdown
can_send_tcp_event = threading.Event()        # whether to send TCP data
can_send_udp_event = threading.Event()        # whether to send UDP data

current_send_rate_tcp = 0.0  # in Mbps
current_send_rate_udp = 0.0  # in Mbps

phone_addr = None              # (ip, port) for UDP
current_tcp_conn = None        # track active TCP data connection (if any)

current_log_filename = None
current_protocol = None

def start_new_log_file(protocol: str):
    """
    Create a new CSV log file for the given protocol ('tcp' or 'udp'),
    write the CSV header, and track it globally.
    """
    global current_log_filename, current_protocol
    now_str = datetime.datetime.now().strftime("%Y_%m_%d_%H_%M_%S")
    current_log_filename = f"{protocol}_{now_str}.csv"
    current_protocol = protocol

    os.makedirs("logs", exist_ok=True)
    path = os.path.join("logs", current_log_filename)
    with open(path, "w") as f:
        f.write("time,time_ms,throughput\n")  # CSV header
    print(f"[{protocol.upper()}] Created log file '{current_log_filename}'")

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
            f.write("time,time_ms,throughput\n")  # header for unknown

    path = os.path.join("logs", current_log_filename)
    with open(path, "a") as f:
        f.write(csv_data + "\n")

    print(f"[{current_protocol.upper()}] Appended CSV logs to {path}.")

def tcp_data_thread():
    """
    Listens on DATA_PORT (TCP) for the actual data connection from the phone.
    If 'can_send_tcp_event' is set, we send data at the chosen rate (if any).
    """
    global current_send_rate_tcp, current_tcp_conn
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        s.bind(('', DATA_PORT))
        s.listen(1)
        print(f"[TCP] Listening for data-plane on port {DATA_PORT}")
    except Exception as e:
        print(f"[TCP] Bind/listen error on port {DATA_PORT}: {e}")
        return

    s.settimeout(1.0)
    payload = b"\x00" * 65535

    while not stop_server_event.is_set():
        conn = None
        try:
            try:
                conn, addr = s.accept()
                print(f"[TCP] Accepted data connection from {addr}")
                current_tcp_conn = conn
                conn.settimeout(1.0)
                last_send_time = time.time()
            except socket.timeout:
                continue

            # Sending loop
            while not stop_server_event.is_set():
                if not can_send_tcp_event.is_set():
                    time.sleep(0.1)
                    continue

                # Optional "rate limit" logic
                if current_send_rate_tcp > 0:
                    target_bps = (current_send_rate_tcp * 1_000_000) / 8.0
                    interval = len(payload) / target_bps
                    elapsed = time.time() - last_send_time
                    if elapsed < interval:
                        time.sleep(interval - elapsed)

                try:
                    conn.sendall(payload)
                    last_send_time = time.time()
                except (BrokenPipeError, ConnectionResetError):
                    print("[TCP] Client disconnected.")
                    break
        except Exception as e:
            print(f"[TCP] Error in data thread: {e}")
        finally:
            if conn:
                conn.close()
            current_tcp_conn = None

    s.close()
    print("[TCP] tcp_data_thread stopped.")

def udp_data_thread():
    """
    Binds to DATA_PORT (UDP). If phone_addr is known and can_send_udp_event is set,
    send UDP data at the chosen rate. If phone_addr is None, wait for a packet from phone.
    """
    global phone_addr, current_send_rate_udp
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        s.bind(('', DATA_PORT))
        print(f"[UDP] Listening for data-plane on port {DATA_PORT}")
    except Exception as e:
        print(f"[UDP] Bind error on port {DATA_PORT}: {e}")
        return

    s.settimeout(1.0)
    payload = b"\x00" * 1400
    last_send_time = time.time()
    packets_sent = 0
    last_log_time = time.time()

    while not stop_server_event.is_set():
        if phone_addr is None:
            # Wait for phone's initial "hello" packet
            try:
                data, addr = s.recvfrom(2048)
                phone_addr = addr
                packets_sent = 0
                last_log_time = time.time()
                print(f"[UDP] Received handshake from {phone_addr}, starting downlink.")
            except socket.timeout:
                continue
            except Exception as e:
                print(f"[UDP] recvfrom error: {e}")
                time.sleep(0.1)
            continue

        if not can_send_udp_event.is_set():
            time.sleep(0.1)
            continue

        # Rate-limited send
        if current_send_rate_udp > 0:
            target_bps = (current_send_rate_udp * 1_000_000) / 8.0
            interval = len(payload) / target_bps
            elapsed = time.time() - last_send_time
            if elapsed < interval:
                time.sleep(interval - elapsed)

        try:
            s.sendto(payload, phone_addr)
            packets_sent += 1
            last_send_time = time.time()

            if (time.time() - last_log_time) >= 1.0:
                print(f"[UDP] Sent {packets_sent} pkts/sec to {phone_addr}")
                packets_sent = 0
                last_log_time = time.time()

        except Exception as e:
            print(f"[UDP] sendto error: {e}")
            time.sleep(0.1)

    s.close()
    print("[UDP] udp_data_thread stopped.")

def handle_persistent_client(conn, addr):
    """
    Reads commands from a single persistent connection:
      - START_TCP <rate>
      - STOP_TCP
      - START_UDP <rate>
      - STOP_UDP
      - CSV_LOGS <batchId> (followed by lines of CSV)
    We'll read until client disconnects.
    """
    global current_send_rate_tcp, current_send_rate_udp
    global phone_addr, current_tcp_conn

    reader = conn.makefile('r')  # text-mode file for line-by-line reading

    try:
        while True:
            line = reader.readline()
            if not line:
                # Client closed connection
                break

            line = line.strip()
            if not line:
                continue

            print(f"[CONTROL] From {addr}: {line}")

            if line.startswith("CSV_LOGS"):
                # Expect to read CSV lines until we can't read more
                # (In practice, you might want a length or delimiter.)
                parts = line.split()
                batch_id = -1
                if len(parts) >= 2:
                    try:
                        batch_id = int(parts[1])
                    except ValueError:
                        pass

                # read the rest of the lines until an empty line or EOF
                csv_lines = []
                # Here we read until the next blank line or EOF
                # You could also define a number-of-lines approach, etc.
                while True:
                    next_line = reader.readline()
                    if not next_line:
                        # socket closed
                        break
                    next_line = next_line.rstrip('\n')
                    if next_line == "":
                        # blank line => end of CSV
                        break
                    csv_lines.append(next_line)

                if csv_lines:
                    csv_data = "\n".join(csv_lines)
                    append_csv_logs(csv_data)
                # (No need to respond with "OK" unless you want to.)

            elif line.startswith("START_TCP"):
                # "START_TCP 5" => 5 Mbps
                parts = line.split()
                rate_val = 0.0
                if len(parts) >= 2:
                    try:
                        rate_val = float(parts[1])
                    except ValueError:
                        rate_val = 0.0
                current_send_rate_tcp = rate_val
                can_send_tcp_event.set()
                start_new_log_file("tcp")
                print(f"[CONTROL] START_TCP with rate {rate_val} Mbps")

            elif line.startswith("STOP_TCP"):
                can_send_tcp_event.clear()
                current_send_rate_tcp = 0.0
                # Optionally close the data-plane connection:
                if current_tcp_conn:
                    try:
                        current_tcp_conn.close()
                    except:
                        pass
                    current_tcp_conn = None
                print("[CONTROL] STOP_TCP => done")

            elif line.startswith("START_UDP"):
                # "START_UDP 10" => 10 Mbps
                parts = line.split()
                rate_val = 0.0
                if len(parts) >= 2:
                    try:
                        rate_val = float(parts[1])
                    except ValueError:
                        rate_val = 0.0
                current_send_rate_udp = rate_val
                can_send_udp_event.set()
                start_new_log_file("udp")
                print(f"[CONTROL] START_UDP with rate {rate_val} Mbps")

            elif line.startswith("STOP_UDP"):
                can_send_udp_event.clear()
                current_send_rate_udp = 0.0
                phone_addr = None
                print("[CONTROL] STOP_UDP => done")

            else:
                print("[CONTROL] Unknown command:", line)

    except Exception as e:
        print(f"[CONTROL] Error reading commands from {addr}: {e}")

    finally:
        conn.close()
        print(f"[CONTROL] Connection from {addr} closed.")

def control_server():
    """
    Accepts new connections on CONTROL_PORT; for each connection, 
    we run 'handle_persistent_client' in a separate thread.
    """
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(('', CONTROL_PORT))
    s.listen(5)
    print(f"[CONTROL] Listening on port {CONTROL_PORT}, data port={DATA_PORT}")

    s.settimeout(1.0)
    while not stop_server_event.is_set():
        try:
            conn, addr = s.accept()
            print(f"[CONTROL] Accepted control connection from {addr}")
            th = threading.Thread(
                target=handle_persistent_client,
                args=(conn, addr),
                daemon=True
            )
            th.start()
        except socket.timeout:
            continue
        except KeyboardInterrupt:
            print("[CONTROL] KeyboardInterrupt => shutting down.")
            break
        except Exception as e:
            print(f"[CONTROL] Error in server accept: {e}")
            break

    s.close()
    print("[CONTROL] control_server stopped.")

if __name__ == "__main__":
    # Start the data-plane threads
    tcp_thread = threading.Thread(target=tcp_data_thread, daemon=True)
    udp_thread = threading.Thread(target=udp_data_thread, daemon=True)
    tcp_thread.start()
    udp_thread.start()

    # Run the control server (main thread)
    try:
        control_server()
    finally:
        stop_server_event.set()
        tcp_thread.join()
        udp_thread.join()
        print("Server fully shut down.")
