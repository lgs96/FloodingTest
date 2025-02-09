#!/usr/bin/env python3
import os
import sys
import socket
import threading
import time
import datetime

"""
Usage:
  python3 server.py <CONTROL_PORT> <DATA_PORT>
Example:
  python3 server.py 8889 8890

Features:
- A control server listening on CONTROL_PORT (TCP) for commands:
    - START_TCP <rate>
    - STOP_TCP
    - START_UDP <rate>
    - STOP_UDP
    - CSV_LOGS ...
- A TCP data thread listening on DATA_PORT for TCP connections (downlink).
- A UDP data thread listening on DATA_PORT for an initial "hello" from the phone,
  then sending UDP traffic back to that phone IP:port at the requested rate.
"""

if len(sys.argv) < 3:
    print("Usage: python3 server.py <CONTROL_PORT> <DATA_PORT>")
    sys.exit(1)

CONTROL_PORT = int(sys.argv[1])
DATA_PORT = int(sys.argv[2])

# Thread/event variables
stop_server_event = threading.Event()      # entire server shutdown
can_send_tcp_event = threading.Event()     # whether to send TCP data
can_send_udp_event = threading.Event()     # whether to send UDP data

current_send_rate_tcp = None  # in Mbps, optional rate for TCP
current_send_rate_udp = None  # in Mbps, optional rate for UDP

# -- Variables for CSV logging --
current_log_filename = None   # e.g. "tcp_2025_02_08_19_45_01.csv"
current_protocol = None       # "tcp" or "udp"

# >>> CHANGED: track the UDP phone address in a global so we can reset it easily
phone_addr = None

# >>> CHANGED: track the active TCP connection (optional) so we can close on STOP
current_tcp_conn = None


def start_new_log_file(protocol: str):
    """
    Create a fresh CSV log file for the given protocol ('tcp' or 'udp'),
    write the CSV header, and set current_log_filename/current_protocol.
    """
    global current_log_filename, current_protocol

    now_str = datetime.datetime.now().strftime("%Y_%m_%d_%H_%M_%S")
    current_log_filename = f"{protocol}_{now_str}.csv"
    current_protocol = protocol

    os.makedirs("logs", exist_ok=True)
    path = os.path.join("logs", current_log_filename)

    # Write the header line at the top of this file
    with open(path, "w") as f:
        f.write("time,time_ms,throughput\n")

    print(f"[{protocol.upper()}] Created new log file '{current_log_filename}' with header.")


def append_csv_logs(csv_data: str):
    """
    Append CSV logs to the current file in logs/ folder.
    If no current protocol is set, we use 'unknown' + timestamp.
    """
    global current_log_filename, current_protocol

    # If we have no current log file, create a fallback with "unknown"
    if not current_log_filename:
        now_str = datetime.datetime.now().strftime("%Y_%m_%d_%H_%M_%S")
        current_log_filename = f"unknown_{now_str}.csv"
        current_protocol = "unknown"

        os.makedirs("logs", exist_ok=True)
        path = os.path.join("logs", current_log_filename)
        with open(path, "w") as f:
            f.write("time,time_ms,throughput\n")   # Write a header even for unknown

    path = os.path.join("logs", current_log_filename)
    with open(path, "a") as f:
        # We append a newline so each CSV_LOGS call is appended as a new row
        f.write(csv_data + "\n")

    print(f"[{current_protocol.upper()}] Appended CSV logs to {path}.")


def tcp_data_thread():
    """
    Listens on DATA_PORT (TCP).
    When START_TCP <rate> is issued, we begin sending payload data as soon as a client connects.
    If STOP_TCP is issued, we stop sending.
    """
    global current_send_rate_tcp, current_tcp_conn

    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    # Increase buffers
    buffer_size = 16 * 1024 * 1024
    s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, buffer_size)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, buffer_size)

    try:
        # Attempt to set some TCP-specific options (may fail on some OS)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_CONGESTION, b"cubic")
        print("[TCP] Set congestion control to cubic")
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_SLOW_START_AFTER_IDLE, 0)
        print("[TCP] Disabled slow start after idle")
    except Exception as e:
        print(f"[TCP] Warning: could not set TCP options: {e}")

    try:
        s.bind(('', DATA_PORT))
        s.listen(1)
        print(f"[TCP] Listening (downlink) on port {DATA_PORT}")
    except Exception as e:
        print(f"[TCP] Failed to bind on {DATA_PORT}: {e}")
        return

    s.settimeout(1.0)
    payload = b"\x00" * 65535

    while not stop_server_event.is_set():
        conn = None
        try:
            try:
                conn, addr = s.accept()
                print(f"[TCP] Accepted connection from {addr}")

                # Track the active connection globally if you want to close on STOP
                current_tcp_conn = conn

                # Also set SNDBUF on the accepted socket
                conn.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, buffer_size)
                try:
                    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_CONGESTION, b"cubic")
                    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_SLOW_START_AFTER_IDLE, 0)
                except:
                    pass

                conn.settimeout(1.0)
                last_send_time = time.time()

            except socket.timeout:
                continue

            # Main sending loop
            while not stop_server_event.is_set():
                if not can_send_tcp_event.is_set():
                    # If we are told "STOP_TCP", we clear the event => wait here
                    time.sleep(0.1)
                    continue

                try:
                    # Optional "rate limit" logic for TCP:
                    if current_send_rate_tcp and current_send_rate_tcp > 0:
                        # current_send_rate_tcp is in Mbps
                        target_bytes_per_sec = (current_send_rate_tcp * 1_000_000) / 8.0
                        target_interval = len(payload) / target_bytes_per_sec
                        elapsed = time.time() - last_send_time
                        if elapsed < target_interval:
                            time.sleep(target_interval - elapsed)

                    conn.sendall(payload)
                    last_send_time = time.time()

                except socket.timeout:
                    continue
                except (BrokenPipeError, ConnectionResetError):
                    print("[TCP] Client disconnected.")
                    break

            if conn:
                conn.close()
                current_tcp_conn = None

        except Exception as e:
            print(f"[TCP] Error in accept/send loop: {e}")
            if conn:
                conn.close()
                current_tcp_conn = None

    s.close()
    print("[TCP] tcp_data_thread stopped.")


def udp_data_thread():
    """
    Continuously:
    - If we don't know phone_addr yet, wait for a packet from the phone.
    - Once we have phone_addr, if can_send_udp_event is set, send data at the configured rate.
    - If STOP_UDP is called (phone_addr is set to None), we revert to waiting for a new packet.
    """
    global current_send_rate_udp, phone_addr

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    send_buffer_size = s.getsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF)
    print(f"[UDP] Send buffer size: {send_buffer_size} bytes")

    try:
        s.bind(('', DATA_PORT))
        print(f"[UDP] Listening (downlink) on UDP port {DATA_PORT} for phone packets...")
    except Exception as e:
        print(f"[UDP] Failed to bind on {DATA_PORT}: {e}")
        return

    payload = b"\x00" * 1400
    last_log_time = time.time()
    last_send_time = time.time()
    packets_sent = 0

    # We'll do non-blocking or short-timeout reads in the main loop
    s.settimeout(1.0)

    while not stop_server_event.is_set():
        # 1) If phone_addr is None, we wait for the phone to send us *any* packet.
        if phone_addr is None:
            try:
                data, addr = s.recvfrom(2048)
                phone_addr = addr
                packets_sent = 0
                last_log_time = time.time()
                print(f"[UDP] Received first packet from {phone_addr}, will start downlink.")
            except socket.timeout:
                # No new phone packet => just loop again
                continue
            except Exception as e:
                print(f"[UDP] Error receiving phone packet: {e}")
                time.sleep(0.1)
            continue  # After setting phone_addr, go to next iteration.

        # 2) If we have a known phone_addr, check whether we are allowed to send data.
        if not can_send_udp_event.is_set():
            # We are told STOP_UDP or just not started yet => do nothing but sleep
            time.sleep(0.1)
            continue

        # 3) If we get here, we have phone_addr and we want to send data.
        try:
            # Optional rate-limiting
            if current_send_rate_udp and current_send_rate_udp > 0:
                # Convert Mbps -> bytes/sec
                target_bytes_per_sec = (current_send_rate_udp * 1_000_000) / 8.0
                target_interval = len(payload) / target_bytes_per_sec
                elapsed = time.time() - last_send_time
                if elapsed < target_interval:
                    time.sleep(target_interval - elapsed)

            s.sendto(payload, phone_addr)
            packets_sent += 1
            last_send_time = time.time()

            # Log every 1 second
            now = time.time()
            if now - last_log_time >= 1.0:
                print(f"[UDP] Sent {packets_sent} packets in the last second to {phone_addr}")
                packets_sent = 0
                last_log_time = now

        except Exception as e:
            print(f"[UDP] sendto error: {e}")
            time.sleep(0.1)

    s.close()
    print("[UDP] udp_data_thread stopped.")



def handle_control_connection(conn, addr):
    """
    Receives text commands like:
      START_TCP <rate>
      STOP_TCP
      START_UDP <rate>
      STOP_UDP
      CSV_LOGS ...
    """
    global current_send_rate_tcp
    global current_send_rate_udp
    global current_log_filename, current_protocol
    global phone_addr, current_tcp_conn

    # Read all data until client closes
    chunks = []
    while True:
        block = conn.recv(4096)
        if not block:
            break
        chunks.append(block)
    data = b''.join(chunks).decode(errors='replace')
    if not data:
        conn.close()
        return

    print("Control from", addr, "->", data)

    if data.startswith("CSV_LOGS"):
        # lines[0] = "CSV_LOGS", lines[1..] = the CSV lines
        lines = data.splitlines()
        if len(lines) > 1:
            csv_data = "\n".join(lines[1:])
            append_csv_logs(csv_data)

    elif data.startswith("START_TCP"):
        # Example: "START_TCP 500Mbps"
        parts = data.split()
        if len(parts) >= 2:
            rate_str = parts[1]
            try:
                rate_value = float(''.join(c for c in rate_str if c.isdigit() or c == '.'))
                current_send_rate_tcp = rate_value
                print(f"[CONTROL] START_TCP with rate {rate_value} Mbps")
            except ValueError:
                print("[CONTROL] Could not parse TCP rate; ignoring")

        can_send_tcp_event.set()

        # >>> CHANGED: start new log file with header
        start_new_log_file("tcp")

    elif data.startswith("STOP_TCP"):
        print("[CONTROL] STOP_TCP => can_send_tcp_event.clear()")
        can_send_tcp_event.clear()
        current_send_rate_tcp = None

        # >>> CHANGED: optionally force close the active connection
        if current_tcp_conn:
            try:
                current_tcp_conn.close()
            except:
                pass
            current_tcp_conn = None

    elif data.startswith("START_UDP"):
        # Example: "START_UDP 200Mbps"
        parts = data.split()
        if len(parts) >= 2:
            rate_str = parts[1]
            try:
                rate_value = float(''.join(c for c in rate_str if c.isdigit() or c == '.'))
                current_send_rate_udp = rate_value
                print(f"[CONTROL] START_UDP with rate {rate_value} Mbps")
            except ValueError:
                print("[CONTROL] Could not parse UDP rate; ignoring")

        can_send_udp_event.set()

        # >>> CHANGED: start new log file with header
        start_new_log_file("udp")

    elif data.startswith("STOP_UDP"):
        print("[CONTROL] STOP_UDP => can_send_udp_event.clear()")
        can_send_udp_event.clear()
        current_send_rate_udp = None

        # >>> CHANGED: reset phone_addr so next START_UDP must wait for new hello
        global phone_addr
        phone_addr = None

    else:
        print("[CONTROL] Unknown command:", data)

    conn.close()


def control_server():
    """
    Listens on CONTROL_PORT for commands (TCP).
    """
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(('', CONTROL_PORT))
    s.listen(5)
    print(f"[CONTROL] Listening on port {CONTROL_PORT} for commands (TCP). Data port={DATA_PORT}")

    s.settimeout(1.0)
    while not stop_server_event.is_set():
        try:
            conn, caddr = s.accept()
            threading.Thread(
                target=handle_control_connection,
                args=(conn, caddr),
                daemon=True
            ).start()
        except socket.timeout:
            continue
        except KeyboardInterrupt:
            print("[CONTROL] KeyboardInterrupt => shutting down.")
            break
        except Exception as e:
            print("[CONTROL] Error in control_server:", e)
            break

    s.close()
    print("[CONTROL] control_server stopped.")


if __name__ == "__main__":
    # Start data-plane threads:
    tcp_thread = threading.Thread(target=tcp_data_thread, daemon=True)
    tcp_thread.start()

    udp_thread = threading.Thread(target=udp_data_thread, daemon=True)
    udp_thread.start()

    # Run the control server in the main thread
    try:
        control_server()
    finally:
        stop_server_event.set()
        tcp_thread.join()
        udp_thread.join()
        print("Server fully shut down.")
