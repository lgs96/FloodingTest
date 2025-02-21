package com.example.tcp_test

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.tcp_test.ui.theme.Tcp_testTheme
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * MainActivity shows a simple UI for controlling TCP/UDP downlink tests.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TCP_UDP_TEST"
    }

    private var controlClient: PersistentControlClient? = null

    // We no longer have averagingJob: we’ll use a thread for averaging instead
    // private var averagingJob: Job? = null

    // Thread-based approach for averaging:
    private var averagingThread: Thread? = null
    @Volatile
    private var averagingThreadRunning = false

    // The single UDP socket used in UDP mode.
    private var udpSocket: DatagramSocket? = null

    // A background job for receiving data (TCP or UDP).
    private var receivingJob: kotlinx.coroutines.Job? = null

    // A dedicated thread for collecting instantaneous throughput ~ every 10ms.
    private var collectorThread: Thread? = null
    @Volatile
    private var collectorThreadRunning = false

    // Throughput displayed on UI
    private val _throughput = mutableStateOf("0.0")
    val throughput: String get() = _throughput.value

    // Buffers for throughput samples and CSV logs.
    private val throughputSamples = mutableListOf<Double>()
    private val csvLogBuffer = mutableListOf<String>()

    // Accumulated received bytes (reset by collector each cycle).
    @Volatile
    private var bytesReceived: Long = 0

    // Path to the folder where CSV logs are stored
    private var logFolderPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load saved settings from SharedPreferences
        val prefs = getSharedPreferences("tcp_test_settings", Context.MODE_PRIVATE)
        val defaultMode = prefs.getString("transport_mode", "TCP") ?: "TCP" // "TCP" or "UDP"
        val defaultIp = prefs.getString("server_ip", "192.168.1.100") ?: "192.168.1.100"
        val defaultControlPort = prefs.getString("control_port", "8889") ?: "8889"
        val defaultDataPort = prefs.getString("data_port", "8890") ?: "8890"
        val defaultRate = prefs.getString("desired_rate", "500Mbps") ?: "500Mbps"

        setContent {
            Tcp_testTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    var transportMode by remember { mutableStateOf(defaultMode) }
                    var serverIp by remember { mutableStateOf(defaultIp) }
                    var controlPort by remember { mutableStateOf(defaultControlPort) }
                    var dataPort by remember { mutableStateOf(defaultDataPort) }
                    var desiredRate by remember { mutableStateOf(defaultRate) }

                    val context = LocalContext.current

                    MainScreen(
                        transportMode = transportMode,
                        onTransportModeChange = { newMode ->
                            transportMode = newMode
                            saveSettings(context, transportMode, serverIp, controlPort, dataPort, desiredRate)
                        },
                        throughput = _throughput.value,
                        serverIp = serverIp,
                        onServerIpChange = { newIp ->
                            serverIp = newIp
                            saveSettings(context, transportMode, serverIp, controlPort, dataPort, desiredRate)
                        },
                        controlPort = controlPort,
                        onControlPortChange = { newPort ->
                            controlPort = newPort
                            saveSettings(context, transportMode, serverIp, controlPort, dataPort, desiredRate)
                        },
                        dataPort = dataPort,
                        onDataPortChange = { newPort ->
                            dataPort = newPort
                            saveSettings(context, transportMode, serverIp, controlPort, dataPort, desiredRate)
                        },
                        desiredRate = desiredRate,
                        onDesiredRateChange = { newRate ->
                            desiredRate = newRate
                            saveSettings(context, transportMode, serverIp, controlPort, dataPort, desiredRate)
                        },
                        onStartTest = {
                            saveSettings(context, transportMode, serverIp, controlPort, dataPort, desiredRate)
                            startTest(
                                mode = transportMode,
                                serverIp = serverIp,
                                controlPort = controlPort.toIntOrNull() ?: 8889,
                                dataPort = dataPort.toIntOrNull() ?: 8890,
                                desiredRate = desiredRate
                            )
                        },
                        onStopTest = {
                            saveSettings(context, transportMode, serverIp, controlPort, dataPort, desiredRate)
                            stopTest(
                                mode = transportMode,
                                serverIp = serverIp,
                                controlPort = controlPort.toIntOrNull() ?: 8889
                            )
                        }
                    )
                }
            }
        }
    }

    /**
     * Save current settings to SharedPreferences
     */
    private fun saveSettings(
        context: Context,
        mode: String,
        ip: String,
        cport: String,
        dport: String,
        rate: String
    ) {
        val sp = context.getSharedPreferences("tcp_test_settings", Context.MODE_PRIVATE)
        with(sp.edit()) {
            putString("transport_mode", mode)
            putString("server_ip", ip)
            putString("control_port", cport)
            putString("data_port", dport)
            putString("desired_rate", rate)
            apply()
        }
        Log.d(TAG, "Settings saved: mode=$mode, ip=$ip, controlPort=$cport, dataPort=$dport, rate=$rate")
    }

    /**
     * Start the test (TCP or UDP).
     */
    private fun startTest(
        mode: String,
        serverIp: String,
        controlPort: Int,
        dataPort: Int,
        desiredRate: String
    ) {
        // Launch in an IO dispatcher so it doesn't block the UI
        GlobalScope.launch(Dispatchers.IO) {
            Log.d(
                TAG,
                "startTest: mode=$mode, server=$serverIp, ctrlPort=$controlPort, dataPort=$dataPort, rate=$desiredRate"
            )

            // Create local log folder
            val timestamp =
                SimpleDateFormat("yy-MM-dd-HH-mm-ss", Locale.getDefault()).format(Date())
            logFolderPath = File(getExternalFilesDir(null), "tcp_test/$timestamp").absolutePath
            Log.d(TAG, "Log folder: $logFolderPath")

            val udpContext = newSingleThreadContext("UdpReceiver")
            val collectorContext = newSingleThreadContext("CollectorThread")

            if (controlClient == null) {
                controlClient = PersistentControlClient(serverIp, controlPort).apply {
                    connect() // open the socket once
                }
            }

            if (mode == "TCP") {
                // Tell server to start TCP
                GlobalScope.launch(Dispatchers.IO) {
                    sendControlCommand("START_TCP $desiredRate", serverIp, controlPort)
                }
                // Start the TCP receiving job if not active
                if (receivingJob?.isActive != true) {
                    receivingJob = GlobalScope.launch(Dispatchers.IO) {
                        runTcpReceiver(serverIp, dataPort)
                    }
                }
            } else {
                // === UDP mode ===
                GlobalScope.launch(udpContext) {
                    // Create/bind local socket if needed
                    if (udpSocket == null) {
                        udpSocket = DatagramSocket(null).apply {
                            reuseAddress = true
                            try {
                                receiveBufferSize = 2 * 1024 * 1024
                            } catch (_: Exception) { /* ignore */
                            }
                            bind(InetSocketAddress(0)) // ephemeral port
                            Log.d(TAG, "UDP: Created socket on port $localPort")
                        }
                    }
                    // Start receiving job
                    if (receivingJob?.isActive != true) {
                        receivingJob = GlobalScope.launch(udpContext) {
                            udpSocket?.let { runUdpReceiver(it) }
                                ?: Log.e(TAG, "UDP: Socket not available")
                        }
                    }
                    // Wait a bit, then send a 'hello' packet for handshake (optional)
                    delay(100)
                    try {
                        udpSocket?.let { sock ->
                            val buf = "hello".toByteArray()
                            val packet =
                                DatagramPacket(buf, buf.size, InetSocketAddress(serverIp, dataPort))
                            sock.send(packet)
                            Log.d(
                                TAG,
                                "UDP: Sent initial handshake to $serverIp:$dataPort from port ${sock.localPort}"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending initial UDP handshake", e)
                    }
                }

                // Tell server to start UDP at desired rate
                GlobalScope.launch(udpContext) {
                    sendControlCommand("START_UDP $desiredRate", serverIp, controlPort)
                }
            }

            // Start 10ms collector + 1s averaging
            startCollectorThread(serverIp, controlPort)
            startAveragingThread(serverIp, controlPort)
        }
    }

    /**
     * Stop the test.
     */
    private fun stopTest(mode: String, serverIp: String, controlPort: Int) {
        Log.d(TAG, "stopTest: mode=$mode")

        // Run the "network" parts of stopTest in a background IO coroutine
        GlobalScope.launch(Dispatchers.IO) {
            // Because sendLine() does network I/O, do it here on IO dispatcher:
            controlClient?.sendLine("STOP_TCP")
            controlClient?.sendLine("STOP_UDP")

            // If UDP mode, close the UDP socket
            if (mode == "UDP") {
                udpSocket?.close()
                udpSocket = null
                Log.d(TAG, "UDP: Socket closed")
            }

            // Optionally close the persistent controlClient
            controlClient?.disconnect()
            controlClient = null
        }

        // Meanwhile, do any purely UI-related work on the main thread
        // For instance, resetting the UI throughput can happen immediately:
        _throughput.value = "0.0"

        // Stop your threads/jobs as well (this is safe on main):
        receivingJob?.cancel()
        receivingJob = null
        stopCollectorThread()
        stopAveragingThread()
    }

    /**
     * TCP receiver: read from the server’s data port until closed.
     */
    private suspend fun runTcpReceiver(serverIp: String, dataPort: Int) {
        try {
            Socket(serverIp, dataPort).use { socket ->
                Log.d(TAG, "TCP connected to $serverIp:$dataPort")
                val input = socket.getInputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) {
                        Log.d(TAG, "TCP socket closed by server.")
                        break
                    }
                    bytesReceived += bytesRead
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP receiver error", e)
        }
        Log.d(TAG, "TCP receiver finished.")
    }

    /**
     * UDP receiver: loop reading from the socket. If error, retry after short delay.
     */
    private suspend fun runUdpReceiver(sock: DatagramSocket) = withContext(Dispatchers.IO) {
        // Set a short timeout so the receive call returns/throws within 100ms
        sock.soTimeout = 100

        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        var packetsReceived = 0
        var lastLogTime = System.currentTimeMillis()

        try {
            Log.d(TAG, "UDP: Receiver started on port ${sock.localPort}")
            while (isActive) { // 'isActive' is a coroutine property
                try {
                    // Try to receive a packet
                    sock.receive(packet)
                    bytesReceived += packet.length
                    packetsReceived++

                    // Logging packets each second
                    val now = System.currentTimeMillis()
                    if (now - lastLogTime >= 1000) {
                        Log.d(
                            TAG,
                            "UDP: Received $packetsReceived packets in the last second from ${packet.address}:${packet.port}"
                        )
                        packetsReceived = 0
                        lastLogTime = now
                    }
                } catch (e: SocketTimeoutException) {
                    // This just means no packet arrived within 'soTimeout' ms.
                    // We can ignore or do small housekeeping here if needed.
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDP receiver error", e)
        } finally {
            Log.d(TAG, "UDP receiver finished.")
        }
    }
    /**
     * A dedicated thread collecting instantaneous throughput ~ every 10ms using real elapsed time.
     */
    private fun startCollectorThread(serverIp: String, controlPort: Int) {
        if (collectorThreadRunning) return
        collectorThreadRunning = true

        collectorThread = thread(start = true, name = "ThroughputCollector") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)

            val desiredIntervalNanos = 10_000_000L // ~10 ms
            var nextTick = System.nanoTime()
            var lastTimeNs = nextTick

            while (collectorThreadRunning && !Thread.currentThread().isInterrupted) {
                val nowNs = System.nanoTime()
                val elapsedNs = nowNs - lastTimeNs
                lastTimeNs = nowNs

                // Snapshot bytes
                val snapshot = bytesReceived
                bytesReceived = 0

                // Calculate Mbps
                val elapsedSec = elapsedNs / 1_000_000_000.0
                val bits = snapshot * 8.0
                val mbps = (bits / 1_000_000.0) / elapsedSec

                // Store numeric throughput
                synchronized(throughputSamples) {
                    throughputSamples.add(mbps)
                }

                // Also store CSV line
                val dateNow = Date()
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                val formattedTime = sdf.format(dateNow)
                val epoch = System.currentTimeMillis()
                val csvLine = "$formattedTime,$epoch,%.3f".format(Locale.US, mbps)

                synchronized(csvLogBuffer) {
                    csvLogBuffer.add(csvLine)
                }
                appendCsvLogToFile(csvLine)

                // Sleep until next tick
                nextTick += desiredIntervalNanos
                val remaining = nextTick - System.nanoTime()
                if (remaining > 0) {
                    val sleepMs = remaining / 1_000_000L
                    val sleepNanos = (remaining % 1_000_000L).toInt()
                    try {
                        Thread.sleep(sleepMs, sleepNanos)
                    } catch (ie: InterruptedException) {
                        break
                    }
                } else {
                    // behind schedule, just continue
                }
            }
            Log.d(TAG, "Collector thread stopped.")
        }
    }

    private fun stopCollectorThread() {
        collectorThreadRunning = false
        collectorThread?.interrupt()
        collectorThread = null
    }

    /**
     * (NEW) A dedicated averaging thread that runs every ~1 second,
     * computes the average throughput, updates the UI, and sends CSV logs.
     */
    private fun startAveragingThread(serverIp: String, controlPort: Int) {
        if (averagingThreadRunning) return
        averagingThreadRunning = true

        averagingThread = thread(start = true, name = "AveragingThread") {
            // We can lower priority a bit, since it’s not as time-critical
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)

            val intervalNanos = 1_000_000_000L // 1 second
            var nextTick = System.nanoTime()

            while (averagingThreadRunning && !Thread.currentThread().isInterrupted) {
                // 1. Compute average
                val avgMbps = synchronized(throughputSamples) {
                    if (throughputSamples.isNotEmpty()) {
                        throughputSamples.average()
                    } else 0.0
                }

                // 2. Log it
                Log.d(TAG, "Average throughput over last second: $avgMbps Mbps")

                // 3. Update the UI on the main thread
                runOnUiThread {
                    _throughput.value = String.format(Locale.US, "%.3f", avgMbps)
                }

                // 4. Gather CSV logs from memory
                val csvData = synchronized(csvLogBuffer) {
                    val data = csvLogBuffer.joinToString("\n")
                    csvLogBuffer.clear()
                    data
                }


                if (csvData.isNotEmpty()) {
                    // Instead of blocking, do an async job:
                    GlobalScope.launch(Dispatchers.IO) {
                        sendCsvLogs(csvData)
                    }
                }

                // 6. (Optional) Clear throughputSamples if you want a fresh set each second
                synchronized(throughputSamples) {
                    throughputSamples.clear()
                }

                // 7. Sleep until next 1-second slot
                nextTick += intervalNanos
                val remaining = nextTick - System.nanoTime()
                if (remaining > 0) {
                    val sleepMs = remaining / 1_000_000L
                    val sleepNanos = (remaining % 1_000_000L).toInt()
                    try {
                        Thread.sleep(sleepMs, sleepNanos)
                    } catch (ie: InterruptedException) {
                        break
                    }
                } else {
                    // behind schedule
                }
            }
            Log.d(TAG, "Averaging thread stopped.")
        }
    }

    private fun stopAveragingThread() {
        averagingThreadRunning = false
        averagingThread?.interrupt()
        averagingThread = null
    }

    /**
     * Send a control command to the server.
     */
    private fun sendControlCommand(command: String, serverIp: String, serverPort: Int) {
        try {
            Socket(serverIp, serverPort).use { sock ->
                sock.getOutputStream().apply {
                    write("$command\n".toByteArray())
                    flush()
                }
            }
            Log.d(TAG, "Sent control command: '$command' to $serverIp:$serverPort")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending control command '$command'", e)
        }
    }

    /**
     * Send CSV logs to the server (batch).
     */
    private fun sendCsvLogs(csvData: String) {
        // Over the same persistent connection:
        controlClient?.sendLine("CSV_LOGS")
        // Then the CSV lines, followed by an empty line:
        csvData.split("\n").forEach {
            controlClient?.sendLine(it)
        }
        // send a blank line to mark the end
        controlClient?.sendLine("")
    }

    /**
     * Append a CSV line to the local file.
     */
    private fun appendCsvLogToFile(logLine: String) {
        try {
            val folder = logFolderPath ?: return
            val dirFile = File(folder)
            if (!dirFile.exists()) {
                dirFile.mkdirs()
                Log.d(TAG, "Created directory: $folder")
            }
            val file = File(dirFile, "logs.csv")
            FileWriter(file, true).use { fw ->
                fw.appendLine(logLine)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error appending CSV log", e)
        }
    }
}

/**
 * Simple UI for test parameters
 */
@Composable
fun MainScreen(
    transportMode: String,
    onTransportModeChange: (String) -> Unit,
    throughput: String,
    serverIp: String,
    onServerIpChange: (String) -> Unit,
    controlPort: String,
    onControlPortChange: (String) -> Unit,
    dataPort: String,
    onDataPortChange: (String) -> Unit,
    desiredRate: String,
    onDesiredRateChange: (String) -> Unit,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Mode toggle buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onTransportModeChange("TCP") }) {
                Text(text = if (transportMode == "TCP") "TCP (Selected)" else "TCP")
            }
            Button(onClick = { onTransportModeChange("UDP") }) {
                Text(text = if (transportMode == "UDP") "UDP (Selected)" else "UDP")
            }
        }
        OutlinedTextField(
            value = serverIp,
            onValueChange = onServerIpChange,
            label = { Text("Server IP") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = controlPort,
            onValueChange = onControlPortChange,
            label = { Text("Control Port") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = dataPort,
            onValueChange = onDataPortChange,
            label = { Text("Data Port") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = desiredRate,
            onValueChange = onDesiredRateChange,
            label = { Text("Desired Rate (e.g. 500Mbps)") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onStartTest) {
                Text("Start Test")
            }
            Button(onClick = onStopTest) {
                Text("Stop Test")
            }
        }
        // Display throughput
        Text(
            text = "Avg Throughput (Mbps): $throughput",
            style = MaterialTheme.typography.headlineMedium
        )
    }
}