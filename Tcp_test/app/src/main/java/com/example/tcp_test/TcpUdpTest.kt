package com.example.tcp_test

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class TcpUdpTester {
    companion object {
        private const val TAG = "TcpUdpTester"
    }

    private var controlSocket: Socket? = null
    private var controlWriter: BufferedWriter? = null
    private var controlReader: BufferedReader? = null

    private val _throughput = mutableStateOf("0.0")
    val throughput: String get() = _throughput.value

    private val _isTestRunning = mutableStateOf(false)
    val isTestRunning: Boolean get() = _isTestRunning.value

    // Local logs
    private var logFolderPath: String? = null
    private val csvLogBuffer = mutableListOf<String>()

    // For measuring throughput in collectorThread
    @Volatile private var bytesReceived: Long = 0
    private val bytesLock = Object()

    private var collectorThread: Thread? = null
    @Volatile private var collectorThreadRunning = false

    private var averagingThread: Thread? = null
    @Volatile private var averagingThreadRunning = false

    fun startTest(
        context: Context,
        mode: String,
        serverIp: String,
        controlPort: Int,
        dataPort: Int,
        desiredRate: String
    ) {
        _isTestRunning.value = true

        // Create local log folder
        val timestamp = SimpleDateFormat("yy-MM-dd-HH-mm-ss", Locale.getDefault()).format(Date())
        logFolderPath = File(context.getExternalFilesDir(null), "tcp_test/$timestamp").absolutePath
        File(logFolderPath!!).mkdirs()
        Log.d(TAG, "Log folder: $logFolderPath")

        // 1) Open a single persistent socket for control & logging
        try {
            controlSocket = Socket(serverIp, controlPort)
            controlWriter = controlSocket?.getOutputStream()?.bufferedWriter(Charsets.UTF_8)
            controlReader = controlSocket?.getInputStream()?.bufferedReader(Charsets.UTF_8)
            Log.d(TAG, "Opened persistent socket to $serverIp:$controlPort")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening control socket", e)
            _isTestRunning.value = false
            return
        }

        // 2) Send a command to start either TCP or UDP on the server
        GlobalScope.launch(Dispatchers.IO) {
            if (mode == "TCP") {
                sendCommand("START_TCP $desiredRate")
                // Start a TCP receiver if needed:
                runTcpReceiver(serverIp, dataPort)
            } else {
                sendCommand("START_UDP $desiredRate")
                // Start a UDP receiver if needed:
                runUdpReceiver(serverIp, dataPort)
            }
        }

        // 3) Start local collector & averaging threads
        startCollectorThread()
        startAveragingThread()
    }

    private fun sendCommand(command: String) {
        try {
            controlWriter?.write(command + "\n")
            controlWriter?.flush()
            Log.d(TAG, "Sent command: $command")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending command $command", e)
        }
    }

    private suspend fun runTcpReceiver(serverIp: String, dataPort: Int) {
        // Connect to server for data-plane
        try {
            val dataSocket = Socket(serverIp, dataPort)
            dataSocket.use { sock ->
                val input = sock.getInputStream()
                val buffer = ByteArray(32 * 1024)
                while (_isTestRunning.value) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead <= 0) break
                    synchronized(bytesLock) {
                        bytesReceived += bytesRead
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP receiver error", e)
        }
    }

    private suspend fun runUdpReceiver(serverIp: String, dataPort: Int) {
        val socket = DatagramSocket()
        // Send a small “hello” so Python’s phone_addr = addr
        val helloData = "hello".toByteArray()
        val serverAddress = InetSocketAddress(serverIp, dataPort)
        socket.send(DatagramPacket(helloData, helloData.size, serverAddress))

        // Now read data in a loop
        val buffer = ByteArray(32 * 1024)
        val packet = DatagramPacket(buffer, buffer.size)
        while (_isTestRunning.value) {
            try {
                socket.receive(packet)
                val bytesRead = packet.length
                synchronized(bytesLock) {
                    bytesReceived += bytesRead
                }
            } catch (ex: Exception) {
                Log.e(TAG, "UDP receiver error", ex)
                break
            }
        }
        socket.close()
    }


    private fun startCollectorThread() {
        if (collectorThreadRunning) return
        collectorThreadRunning = true

        collectorThread = thread(start = true, name = "CollectorThread") {
            val intervalNs = 10_000_000L // ~10 ms
            var nextTick = System.nanoTime()
            var lastTimeNs = nextTick

            while (collectorThreadRunning) {
                val nowNs = System.nanoTime()
                val elapsedNs = nowNs - lastTimeNs
                lastTimeNs = nowNs

                val snapshot: Long
                synchronized(bytesLock) {
                    snapshot = bytesReceived
                    bytesReceived = 0
                }
                val elapsedSec = elapsedNs / 1_000_000_000.0
                val bits = snapshot * 8.0
                val mbps = (bits / 1_000_000.0) / elapsedSec

                val now = System.currentTimeMillis()
                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(now))
                val csvLine = "$dateStr,$now,%.3f".format(Locale.US, mbps)

                // Locally append to CSV buffer
                synchronized(csvLogBuffer) {
                    csvLogBuffer.add(csvLine)
                }
                // Also append to local file
                appendCsvLogToFile(csvLine)

                nextTick += intervalNs
                val remaining = nextTick - System.nanoTime()
                if (remaining > 0) {
                    try {
                        Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                    } catch (ie: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    private fun startAveragingThread() {
        if (averagingThreadRunning) return
        averagingThreadRunning = true

        averagingThread = thread(start = true, name = "AveragingThread") {
            while (averagingThreadRunning) {
                // Gather all CSV lines up to now
                val chunk: String = synchronized(csvLogBuffer) {
                    if (csvLogBuffer.isEmpty()) return@synchronized ""
                    val lines = csvLogBuffer.joinToString("\n")
                    csvLogBuffer.clear()
                    lines
                }
                if (chunk.isNotEmpty()) {
                    // Send lines as a CSV_LOGS batch
                    // We can use a simple incremental batch ID if we like
                    sendCsvLogs(chunk)
                }

                // Just for display, you might compute average from the last chunk
                // or do something else ...
                // Update _throughput if you want an immediate average here

                try {
                    Thread.sleep(1000)
                } catch (ie: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun sendCsvLogs(csvData: String) {
        // Use the same persistent socket
        // We'll define batchId = System.currentTimeMillis() or an increment
        val batchId = System.currentTimeMillis()
        try {
            controlWriter?.write("CSV_LOGS $batchId\n")
            controlWriter?.write(csvData)
            controlWriter?.write("\n\n") // blank line to signal end
            controlWriter?.flush()

            Log.d(TAG, "Sent CSV logs (batchId=$batchId) with lines:\n$csvData")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending CSV logs", e)
        }
    }

    private fun appendCsvLogToFile(logLine: String) {
        try {
            val folder = logFolderPath ?: return
            val file = File(folder, "logs.csv")
            FileWriter(file, true).use { fw ->
                fw.appendLine(logLine)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error appending to local CSV file", e)
        }
    }

    fun stopTest(mode: String) {
        _isTestRunning.value = false

        // 1) Send STOP commands if you wish
        GlobalScope.launch(Dispatchers.IO) {
            sendCommand("STOP_TCP")
            sendCommand("STOP_UDP")
        }

        // 2) Stop threads
        collectorThreadRunning = false
        collectorThread?.interrupt()
        collectorThread = null

        averagingThreadRunning = false
        averagingThread?.interrupt()
        averagingThread = null

        // 3) Close the persistent socket
        try {
            controlWriter?.close()
            controlReader?.close()
            controlSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing control socket", e)
        } finally {
            controlWriter = null
            controlReader = null
            controlSocket = null
        }

        Log.d(TAG, "Test stopped")
    }
}
