# Android TCP/UDP Downlink Test

This repository contains:

- **An Android app** (Kotlin) that connects to a Python server and measures downlink throughput over TCP or UDP.
- **A Python server** that listens for control commands (`START_TCP`, `START_UDP`, etc.) and sends back data at the requested rate.

## Table of Contents

- [Overview](#overview)
- [How It Works](#how-it-works)
  - [Android App](#android-app)
  - [Python Server](#python-server)
- [Quick Start](#quick-start)
  - [1. Run the Python Server](#1-run-the-python-server)
  - [2. Run the Android App](#2-run-the-android-app)
- [Logging and Throughput Collection](#logging-and-throughput-collection)
- [Troubleshooting](#troubleshooting)
- [License](#license)

---

## Overview

**Goal**: Measure downlink throughput (TCP or UDP) in a controlled manner.

- The **Android app**:
  - Lets you pick **TCP** or **UDP** mode.
  - Sends a control command to the server (`START_TCP <rate>` or `START_UDP <rate>`).
  - Receives data from the server, measures throughput in real time (every ~10 ms), and logs an average every ~1 second.
- The **Python server**:
  - Listens on a **control port** (TCP) for commands.
  - Opens a **data port** (TCP or UDP) to send data back to the phone.
  - Can optionally enforce a specific send rate in Mbps.

## How It Works

### Android App

1. **UI Settings**:
   - **Transport Mode**: `TCP` or `UDP`.
   - **Server IP**: Where the Python server runs.
   - **Control Port**: (e.g. `8889`).
   - **Data Port**: (e.g. `8890`).
   - **Desired Rate**: e.g. `500Mbps`.
2. **Start Test**:
   - Sends `START_TCP <rate>` or `START_UDP <rate>` to the server.
   - Opens a local receiver (TCP socket or UDP socket).
   - Collects throughput samples every ~10 ms, storing results in a CSV file on the device.
3. **Stop Test**:
   - Sends `STOP_TCP` / `STOP_UDP` commands to the server.
   - Closes sockets, stops threads, and resets throughput display.

### Python Server

1. **Control Port** (TCP):
   - Receives commands: `START_TCP <rate>`, `STOP_TCP`, `START_UDP <rate>`, `STOP_UDP`, or `CSV_LOGS`.
   - Adjusts flags to start/stop sending data on the data port.
2. **Data Port**:
   - **TCP**: A thread that listens for a connection. Once connected, it sends data at the desired rate (if set).
   - **UDP**: Waits for the phone’s initial UDP packet to learn its IP/port, then blasts data at the desired rate.

---

## Quick Start

### 1. Run the Python Server

```bash
# Example usage:
#   python3 server.py <CONTROL_PORT> <DATA_PORT>
python3 server.py 8889 8890
```

You should see something like:

```
[CONTROL] Listening on port 8889 for commands (TCP). Data port=8890
[TCP] Listening on port 8890
[UDP] Listening on port 8890 for initial phone packet
```

### 2. Run the Android App

1. Open this project in **Android Studio**.
2. Update the **Server IP** in the app UI to match your Python server’s IP address.
3. Set **Control Port** to `8889` and **Data Port** to `8890`.
4. Enter a **Desired Rate** (e.g. `200Mbps`).
5. Select **TCP** or **UDP**, then tap **Start Test**.

Watch the server logs and the phone’s Logcat to see throughput messages.  

When you tap **Stop Test**, the app sends stop commands to the server and closes everything out.

---

## Logging and Throughput Collection

- **On the phone**:
  - Every 10 ms, the app notes how many bytes were received since the last check, converts it to Mbps, and logs it.
  - Every 1 second, it computes an average Mbps, updates the UI, and (optionally) sends batched CSV logs to the server (`CSV_LOGS`).
  - Also saves logs locally to:  
    `Android/data/<app-package>/files/tcp_test/<timestamp>/logs.csv`
- **On the Python server** (optional):
  - If it receives a `CSV_LOGS` command, it appends the data to a local `logs.csv` file under `tcp_test/<timestamp>`.

---

## Troubleshooting

- **Cannot connect**:  
  Ensure the phone and server can reach each other (same LAN or valid routing), and that the ports aren’t blocked by firewall.
- **Slow or bursty throughput**:  
  Very high UDP rates can overload the phone or network. Try smaller rates first (e.g. `50Mbps`).
- **Truncated logs**:  
  If you see partial lines, it might be a concurrency issue or normal logcat truncation for large lines. The local CSV file usually remains correct.
- **Aggregator delays**:  
  If the phone is very busy receiving data, the aggregator thread might not run exactly every 1 second. This can cause 2–3 second delays in logs.

---

## License

This sample code is for demonstration and testing. You can use, modify, or distribute it as needed for educational or internal use.
No guarantees are provided—use at your own risk!

