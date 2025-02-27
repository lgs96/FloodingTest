#!/usr/bin/env python3

import socket
import time
import argparse
import threading
import sys

def print_stats(start_time, bytes_received, running):
    """Thread to print statistics every second"""
    last_time = start_time
    last_bytes = 0
    
    while running[0]:
        time.sleep(1.0)
        current_time = time.time()
        current_bytes = bytes_received[0]
        
        duration = current_time - last_time
        bytes_delta = current_bytes - last_bytes
        
        if duration > 0:
            rate_mbps = (bytes_delta * 8) / (duration * 1_000_000)
            total_gb = current_bytes / (1024 * 1024 * 1024)
            elapsed = current_time - start_time
            
            sys.stdout.write(f"\rReceived: {total_gb:.3f} GB | Rate: {rate_mbps:.2f} Mbps | Time: {elapsed:.1f}s")
            sys.stdout.flush()
            
        last_time = current_time
        last_bytes = current_bytes

def main():
    parser = argparse.ArgumentParser(description='TCP Throughput Test Client')
    parser.add_argument('--host', default='localhost', help='Server host (default: localhost)')
    parser.add_argument('--port', type=int, default=8890, help='Server port (default: 8890)')
    parser.add_argument('--time', type=int, default=10, help='Test duration in seconds (default: 10)')
    parser.add_argument('--buffer', type=int, default=16777216, help='Receive buffer size in bytes (default: 16MB)')
    args = parser.parse_args()

    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, args.buffer)
        
        print(f"Connecting to {args.host}:{args.port}...")
        sock.connect((args.host, args.port))
        print("Connected! Starting test...")
        
        # Variables for statistics thread
        bytes_received = [0]
        running = [True]
        
        # Start stats printing thread
        start_time = time.time()
        stats_thread = threading.Thread(target=print_stats, args=(start_time, bytes_received, running))
        stats_thread.daemon = True
        stats_thread.start()
        
        # Receive data for the specified duration
        end_time = start_time + args.time
        receive_buffer = bytearray(min(args.buffer, 8 * 1024 * 1024))  # Max 8MB chunks for memory efficiency
        
        while time.time() < end_time:
            try:
                bytes_read = sock.recv_into(receive_buffer)
                if bytes_read == 0:  # Connection closed by server
                    break
                bytes_received[0] += bytes_read
            except socket.timeout:
                continue
            except Exception as e:
                print(f"\nError receiving data: {e}")
                break
        
        # Test complete
        running[0] = False
        stats_thread.join(timeout=1.0)
        
        total_time = time.time() - start_time
        avg_mbps = (bytes_received[0] * 8) / (total_time * 1_000_000)
        total_gb = bytes_received[0] / (1024 * 1024 * 1024)
        
        print(f"\n\nTest complete!")
        print(f"Received: {total_gb:.3f} GB in {total_time:.2f} seconds")
        print(f"Average throughput: {avg_mbps:.2f} Mbps ({avg_mbps/1000:.2f} Gbps)")
    
    except Exception as e:
        print(f"Error: {e}")
    finally:
        try:
            sock.close()
        except:
            pass

if __name__ == "__main__":
    main()