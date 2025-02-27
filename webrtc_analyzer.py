#!/usr/bin/env python3

import sys
import json
import os
import csv
from statistics import mean

def eprint(*args, **kwargs):
    """Print to stderr for debugging without breaking JSON output to stdout."""
    print(*args, file=sys.stderr, **kwargs)

# Dynamically set output filenames based on session folder
def get_output_paths(session_folder=None):
    if session_folder and len(sys.argv) >= 3:
        # Strip out any timestamp folders - we want to save directly in the session folder
        # If path contains a slash, take only the first part (sessionId)
        if '/' in session_folder:
            session_parts = session_folder.split('/')
            session_folder = session_parts[0]  # Take only the sessionId part
            
        # Create output directory path
        output_dir = os.path.join("logs", "logs_analyzed", session_folder)
        if not os.path.exists(output_dir):
            try:
                os.makedirs(output_dir, exist_ok=True)
            except Exception as e:
                eprint(f"Could not create output directory: {e}")
                return "webrtc_data.csv", "webrtc_summary.json"
                
        return os.path.join(output_dir, "webrtc_data.csv"), os.path.join(output_dir, "webrtc_summary.json")
    else:
        return "webrtc_data.csv", "webrtc_summary.json"

def parse_logs(data_list):
    """
    Parse WebRTC log entries and extract relevant metrics.
    Only processes the current batch of logs (incremental processing).
    
    Returns a deduplicated list of data rows from the current batch only.
    """
    # Track timestamps within this batch to prevent duplication
    timestamp_data = {}
    
    # Keep track of previous bytesReceived to compute "bitrate_received"
    prev_bytes = None

    for entry in data_list:
        ts = entry.get("timestamp", "")
        raw_stats = entry.get("rawStats", {})

        # Skip entries with no timestamp
        if not ts:
            continue

        # We'll collect data in local variables
        fps = None
        frames_received = None
        frames_decoded = None
        frames_dropped = None
        decode_time = None
        bitrate_received = None
        round_trip_time = None

        # Identify inbound-rtp for video and candidate-pair
        for stat_id, stat_obj in raw_stats.items():
            stype = stat_obj.get("type")
            kind = stat_obj.get("kind")

            # inbound-rtp => gather video info
            if stype == "inbound-rtp" and kind == "video":
                # fps
                fps_val = stat_obj.get("framesPerSecond")
                if isinstance(fps_val, (int, float)):
                    fps = float(fps_val)

                # framesReceived
                fr_val = stat_obj.get("framesReceived")
                if isinstance(fr_val, int):
                    frames_received = fr_val

                # framesDecoded
                fd_val = stat_obj.get("framesDecoded")
                if isinstance(fd_val, int):
                    frames_decoded = fd_val

                # framesDropped
                drop_val = stat_obj.get("framesDropped")
                if isinstance(drop_val, int):
                    frames_dropped = drop_val

                # decode_time = totalDecodeTime / framesDecoded (simple average)
                tdt = stat_obj.get("totalDecodeTime")
                if (isinstance(tdt, (int, float)) and
                    isinstance(frames_decoded, int) and
                    frames_decoded > 0):
                    decode_time = float(tdt) / float(frames_decoded)

                # simplistic "bitrate_received" from bytesReceived
                # ignoring actual time deltas
                b_recv = stat_obj.get("bytesReceived")
                if isinstance(b_recv, int):
                    # If we have a prev_bytes, compute a difference
                    if prev_bytes is not None:
                        delta_bytes = b_recv - prev_bytes
                        if delta_bytes < 0:
                            delta_bytes = 0  # guard in case it resets
                        # convert to Mbit/s for a 1-second window
                        bitrate_received = (delta_bytes * 8) / 1_000_000.0  # Mbit/s
                    prev_bytes = b_recv

            # candidate-pair => gather round_trip_time
            if stype == "candidate-pair":
                crrt = stat_obj.get("currentRoundTripTime")
                if isinstance(crrt, (int, float)):
                    # convert s => ms
                    round_trip_time = crrt * 1000.0

        # Build a row dict - use 0 for missing values
        row = {
            "timestamp": ts,
            "fps": fps if fps is not None else 0,
            "frames_received": frames_received if frames_received is not None else 0,
            "frames_decoded": frames_decoded if frames_decoded is not None else 0,
            "frames_dropped": frames_dropped if frames_dropped is not None else 0,
            "decode_time": decode_time if decode_time is not None else 0,
            "bitrate_received": bitrate_received if bitrate_received is not None else 0,
            "round_trip_time": round_trip_time if round_trip_time is not None else 0
        }
        
        # Deduplicate within this batch
        if ts in timestamp_data:
            # Check if this row has any different (non-zero) values compared to the stored one
            existing_row = timestamp_data[ts]
            has_new_data = False
            
            # Check if any metric is non-zero and different from existing data
            for metric in ["fps", "frames_received", "frames_decoded", "frames_dropped", 
                          "decode_time", "bitrate_received", "round_trip_time"]:
                if row[metric] != 0 and row[metric] != existing_row[metric]:
                    has_new_data = True
                    break
            
            if has_new_data:
                # Update the stored data with this new information
                for metric in ["fps", "frames_received", "frames_decoded", "frames_dropped", 
                              "decode_time", "bitrate_received", "round_trip_time"]:
                    if row[metric] != 0:
                        existing_row[metric] = row[metric]
        else:
            # This is a new timestamp within this batch, store it
            timestamp_data[ts] = row
    
    # Convert the deduplicated timestamp data to rows
    rows = list(timestamp_data.values())
    
    # Sort rows by timestamp for consistency
    rows.sort(key=lambda x: x["timestamp"])
    
    return rows

def load_existing_csv(csv_filename):
    """
    Reads existing CSV (if any), returns the number of rows found.
    Also returns a set of timestamps that are already in the CSV.
    """
    if not os.path.isfile(csv_filename):
        return 0, set()

    existing_timestamps = set()
    with open(csv_filename, 'r', newline='', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        row_count = 0
        for row in reader:
            row_count += 1
            existing_timestamps.add(row['timestamp'])
        
        return row_count, existing_timestamps

def append_to_csv(csv_filename, parsed_rows):
    """
    Appends only new rows to the CSV, skipping any timestamps that already exist.
    """
    file_exists = os.path.isfile(csv_filename)
    start_id = 0
    existing_timestamps = set()
    
    if file_exists:
        start_id, existing_timestamps = load_existing_csv(csv_filename)

    # Make sure the directory exists
    os.makedirs(os.path.dirname(os.path.abspath(csv_filename)), exist_ok=True)

    # Filter out rows with timestamps that already exist in the CSV
    new_rows = [row for row in parsed_rows if row["timestamp"] not in existing_timestamps]
    
    if not new_rows:
        eprint("No new data points to append.")
        return 0

    fieldnames = [
        "sample_id",
        "timestamp",
        "fps",
        "frames_received",
        "frames_decoded",
        "frames_dropped",
        "decode_time",
        "bitrate_received",
        "round_trip_time",
        "frames_received_per_second",
        "frames_decoded_per_second"
    ]

    with open(csv_filename, 'a', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)

        # If file didn't exist, write header
        if not file_exists:
            writer.writeheader()

        # Write only new rows
        for i, row in enumerate(new_rows):
            out_row = {
                "sample_id": start_id + i,
                "timestamp": row["timestamp"],
                "fps": f"{row['fps']:.2f}" if isinstance(row['fps'], (float, int)) and row['fps'] != 0 else "0.00",
                "frames_received": f"{row['frames_received']:.2f}" if isinstance(row['frames_received'], (float, int)) and row['frames_received'] != 0 else "0.00",
                "frames_decoded": f"{row['frames_decoded']:.2f}" if isinstance(row['frames_decoded'], (float, int)) and row['frames_decoded'] != 0 else "0.00",
                "frames_dropped": f"{row['frames_dropped']:.2f}" if isinstance(row['frames_dropped'], (float, int)) and row['frames_dropped'] != 0 else "0.00",
                "decode_time": f"{row['decode_time']:.2f}" if isinstance(row['decode_time'], (float, int)) and row['decode_time'] != 0 else "0.00",
                "bitrate_received": f"{row['bitrate_received']:.2f}" if isinstance(row['bitrate_received'], (float, int)) and row['bitrate_received'] != 0 else "0.00",
                "round_trip_time": f"{row['round_trip_time']:.2f}" if isinstance(row['round_trip_time'], (float, int)) and row['round_trip_time'] != 0 else "0.00",
                "frames_received_per_second": f"{row['frames_received_per_second']:.2f}" if isinstance(row['frames_received_per_second'], (float, int)) and row['frames_received_per_second'] != 0 else "0.00",
                "frames_decoded_per_second": f"{row['frames_decoded_per_second']:.2f}" if isinstance(row['frames_decoded_per_second'], (float, int)) and row['frames_decoded_per_second'] != 0 else "0.00"
            }
            writer.writerow(out_row)
            
    return len(new_rows)

def read_all_csv_data(csv_filename):
    """
    Read all data from the CSV file for computing summary statistics.
    """
    if not os.path.isfile(csv_filename):
        return []
    
    all_data = []
    with open(csv_filename, 'r', newline='', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            # Convert string values to float, handling potential missing columns
            processed_row = {
                "timestamp": row["timestamp"],
                "fps": float(row["fps"]) if row["fps"] else 0,
                "frames_received": float(row["frames_received"]) if row["frames_received"] else 0,
                "frames_decoded": float(row["frames_decoded"]) if row["frames_decoded"] else 0,
                "frames_dropped": float(row["frames_dropped"]) if row["frames_dropped"] else 0,
                "decode_time": float(row["decode_time"]) if row["decode_time"] else 0,
                "bitrate_received": float(row["bitrate_received"]) if row["bitrate_received"] else 0,
                "round_trip_time": float(row["round_trip_time"]) if row["round_trip_time"] else 0
            }
            
            # Handle the new columns which might not exist in older CSV files
            processed_row["frames_received_per_second"] = float(row.get("frames_received_per_second", 0)) if row.get("frames_received_per_second") else 0
            processed_row["frames_decoded_per_second"] = float(row.get("frames_decoded_per_second", 0)) if row.get("frames_decoded_per_second") else 0
            
            all_data.append(processed_row)
    
    return all_data

def compute_summary(csv_filename):
    """
    We produce a simple summary with average FPS, average RTT, etc.
    Now reads all data from the CSV file to compute summary across the entire session.
    """
    # Read all data from CSV
    all_rows = read_all_csv_data(csv_filename)
    
    # Gather numeric values, ignoring zeros
    fps_vals = []
    rtt_vals = []
    bitrate_vals = []
    frames_received_per_second_vals = []
    frames_decoded_per_second_vals = []
    
    for row in all_rows:
        if row["fps"] > 0:
            fps_vals.append(row["fps"])
        if row["round_trip_time"] > 0:
            rtt_vals.append(row["round_trip_time"])
        if row["bitrate_received"] > 0:
            bitrate_vals.append(row["bitrate_received"])
        if row.get("frames_received_per_second", 0) > 0:
            frames_received_per_second_vals.append(row["frames_received_per_second"])
        if row.get("frames_decoded_per_second", 0) > 0:
            frames_decoded_per_second_vals.append(row["frames_decoded_per_second"])

    # Build summary statistics
    summary = {
        "num_samples": len(all_rows),
        "avg_fps": mean(fps_vals) if fps_vals else 0,
        "avg_rtt_ms": mean(rtt_vals) if rtt_vals else 0,
        "avg_bitrate_mbps": mean(bitrate_vals) if bitrate_vals else 0
    }
    
    # Add the new per-second metrics if available
    if frames_received_per_second_vals:
        summary["avg_frames_received_per_second"] = mean(frames_received_per_second_vals)
        summary["max_frames_received_per_second"] = max(frames_received_per_second_vals)
    
    if frames_decoded_per_second_vals:
        summary["avg_frames_decoded_per_second"] = mean(frames_decoded_per_second_vals)
        summary["max_frames_decoded_per_second"] = max(frames_decoded_per_second_vals)
    
    # Add max values if available
    if fps_vals:
        summary["max_fps"] = max(fps_vals)
    if rtt_vals:
        summary["max_rtt_ms"] = max(rtt_vals)
    if bitrate_vals:
        summary["max_bitrate_mbps"] = max(bitrate_vals)
    
    # Add min values if available
    if fps_vals:
        summary["min_fps"] = min(fps_vals)
    if rtt_vals:
        summary["min_rtt_ms"] = min(rtt_vals)
    
    # Add the timestamp range if available
    if all_rows:
        summary["first_timestamp"] = all_rows[0]["timestamp"]
        summary["latest_timestamp"] = all_rows[-1]["timestamp"]
    
    return summary

def main():
    if len(sys.argv) < 2:
        eprint("Usage: python webrtc_analyzer.py <dump_file.json> [session_folder]")
        # Print a safe JSON so Node doesn't crash
        print(json.dumps({"error": "No input file provided"}))
        sys.exit(1)

    file_path = sys.argv[1]
    
    # Get session folder from command line if provided
    session_folder = sys.argv[2] if len(sys.argv) >= 3 else None
    
    # Get output paths based on session folder
    csv_filename, summary_filename = get_output_paths(session_folder)
    
    eprint(f"Using CSV output path: {csv_filename}")
    eprint(f"Using Summary output path: {summary_filename}")

    # Load data from JSON
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
    except Exception as e:
        eprint(f"Error reading JSON: {e}")
        print(json.dumps({"error": "Could not read JSON file"}))
        sys.exit(1)

    # data is expected to be a list
    if not isinstance(data, list):
        eprint("Top-level JSON is not a list; please adjust or transform data.")
        print(json.dumps({"error": "JSON top-level was not a list"}))
        sys.exit(1)

    # Parse the logs from this batch
    parsed_rows = parse_logs(data)
    eprint(f"Parsed {len(parsed_rows)} unique data points from current batch")

    # Append only new rows to CSV
    rows_appended = append_to_csv(csv_filename, parsed_rows)
    eprint(f"Appended {rows_appended} new rows to CSV")

    # Compute summary using all data in the CSV
    summary_data = compute_summary(csv_filename)
    
    # Make sure the directory exists for the summary file
    os.makedirs(os.path.dirname(os.path.abspath(summary_filename)), exist_ok=True)
    
    with open(summary_filename, 'w', encoding='utf-8') as sf:
        json.dump(summary_data, sf, indent=2)

    # Print a minimal JSON to stdout for Node.js to parse
    print(json.dumps({
        "csv_appended_rows": rows_appended,
        "total_samples": summary_data["num_samples"],
        "csv_file": csv_filename,
        "summary_file": summary_filename
    }))

if __name__ == "__main__":
    main()