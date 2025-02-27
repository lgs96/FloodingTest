package com.example.tcp_test

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
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

    // Existing members...
    private var udpSocket: DatagramSocket? = null
    private var receivingJob: Job? = null

    private var collectorThread: Thread? = null
    @Volatile private var collectorThreadRunning = false

    private var averagingThread: Thread? = null
    @Volatile private var averagingThreadRunning = false

    private val _throughput = mutableStateOf("0.0")
    val throughput: String get() = _throughput.value

    private val throughputSamples = mutableListOf<Double>()
    // We'll still keep the buffer for local file logging if needed.
    private val csvLogBuffer = mutableListOf<String>()

    // New buffer for batching logs to send over the network.
    private val networkLogBuffer = mutableListOf<String>()

    @Volatile private var bytesReceived: Long = 0
    private var logFolderPath: String? = null

    // Channel for sequential log sending.
    private var logChannel = Channel<String>(Channel.UNLIMITED)
    private var logSenderJob: Job? = null

    // New job for batching logs every 1 second.
    private var logBatcherJob: Job? = null

    // Starts a dedicated sender that posts each CSV batch immediately.
    private fun startLogSender(serverIp: String, controlPort: Int) {
        val url = "http://$serverIp:$controlPort/csv_logs"
        logSenderJob = GlobalScope.launch(Dispatchers.IO) {
            for (csvData in logChannel) {
                // This suspend call waits until the POST completes before processing the next batch.
                HttpControlClient.post(url, csvData)
            }
        }
    }

    // New function: every 1 second, flush accumulated logs and send as a single batch.
    private fun startLogBatcher() {
        logBatcherJob = GlobalScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)  // Batch every 1 second.
                val batch: List<String> = synchronized(networkLogBuffer) {
                    val copy = networkLogBuffer.toList()
                    networkLogBuffer.clear()
                    copy
                }
                if (batch.isNotEmpty()) {
                    // Combine the batched logs into a single newline-separated string.
                    val batchedCsv = batch.joinToString("\n")
                    try {
                        logChannel.send(batchedCsv)
                    } catch (e: ClosedSendChannelException) {
                        break
                    }
                }
            }
        }
    }

    fun startTest(
        context: Context,
        mode: String,
        serverIp: String,
        controlPort: Int,
        dataPort: Int,
        desiredRate: String
    ) {

        logChannel = Channel(Channel.UNLIMITED)
        // Create local log folder
        val timestamp = SimpleDateFormat("yy-MM-dd-HH-mm-ss", Locale.getDefault()).format(Date())
        logFolderPath = File(context.getExternalFilesDir(null), "tcp_test/$timestamp").absolutePath
        Log.d(TAG, "Log folder: $logFolderPath")

        val serverBase = "http://$serverIp:$controlPort"

        // Start either TCP or UDP data-plane
        if (mode == "TCP") {
            GlobalScope.launch(Dispatchers.IO) {
                HttpControlClient.get("$serverBase/start_tcp?rate=$desiredRate")
            }
            if (receivingJob?.isActive != true) {
                receivingJob = GlobalScope.launch(Dispatchers.IO) {
                    runTcpReceiver(serverIp, dataPort)
                }
            }
        } else {
            val udpContext = newSingleThreadContext("UdpReceiver")
            GlobalScope.launch(udpContext) {
                if (udpSocket == null) {
                    udpSocket = DatagramSocket(null).apply {
                        reuseAddress = true
                        try {
                            receiveBufferSize = 2 * 1024 * 1024
                        } catch (_: Exception) {}
                        bind(InetSocketAddress(0))
                        Log.d(TAG, "UDP: Created socket on port $localPort")
                    }
                }
                if (receivingJob?.isActive != true) {
                    receivingJob = GlobalScope.launch(udpContext) {
                        udpSocket?.let { runUdpReceiver(it) }
                            ?: Log.e(TAG, "UDP: Socket not available")
                    }
                }
                delay(100)
                try {
                    udpSocket?.let { sock ->
                        val buf = "hello".toByteArray()
                        val packet = DatagramPacket(buf, buf.size, InetSocketAddress(serverIp, dataPort))
                        sock.send(packet)
                        Log.d(TAG, "UDP: Sent handshake to $serverIp:$dataPort from port ${sock.localPort}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending initial UDP handshake", e)
                }
            }
            GlobalScope.launch(Dispatchers.IO) {
                Log.d(TAG, "Start UDP with $desiredRate")
                HttpControlClient.get("$serverBase/start_udp?rate=$desiredRate")
            }
        }

        // Kick off threads and the log sender.
        startCollectorThread()
        startAveragingThread()  // now used only for UI updates
        startLogSender(serverIp, controlPort)
        startLogBatcher() // start batching logs every 1 second
    }

    fun stopTest(mode: String, serverIp: String, controlPort: String) {
        // Stop background tasks
        collectorThreadRunning = false
        averagingThreadRunning = false

        // Cancel the log sender and batcher and close the channel.
        logSenderJob?.cancel()
        logBatcherJob?.cancel()
        logChannel.close()

        val serverBase = "http://$serverIp:$controlPort"
        GlobalScope.launch(Dispatchers.IO) {
            try {
                when (mode) {
                    "TCP" -> HttpControlClient.get("$serverBase/stop_tcp")
                    "UDP" -> {
                        HttpControlClient.get("$serverBase/stop_udp")
                        receivingJob?.join() // Wait until receiver finishes
                        udpSocket?.close()
                        udpSocket = null
                        Log.d(TAG, "UDP socket closed")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping test on server", e)
            }
        }

        try {
            receivingJob?.cancel()
            collectorThread?.interrupt()
            averagingThread?.interrupt()

            collectorThread?.join(1000)
            averagingThread?.join(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping threads", e)
        } finally {
            receivingJob = null
            collectorThread = null
            averagingThread = null
            _throughput.value = "0.0"
        }
        Log.d(TAG, "Test stopped")
    }

    private suspend fun runTcpReceiver(serverIp: String, dataPort: Int) {
        try {
            Socket(serverIp, dataPort).use { socket ->
                Log.d(TAG, "TCP connected to $serverIp:$dataPort")
                val input = socket.getInputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    bytesReceived += bytesRead
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP receiver error", e)
        }
    }

    private suspend fun runUdpReceiver(sock: DatagramSocket) = withContext(Dispatchers.IO) {
        sock.soTimeout = 100
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            Log.d(TAG, "UDP: Receiver started on port ${sock.localPort}")
            while (isActive) {
                try {
                    sock.receive(packet)
                    bytesReceived += packet.length
                } catch (_: Exception) {
                    // Timeout – keep looping
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDP receiver error", e)
        }
    }

    private fun startCollectorThread() {
        if (collectorThreadRunning) return
        collectorThreadRunning = true

        collectorThread = thread(start = true, name = "ThroughputCollector") {
            val intervalNanos = 10_000_000L // 10ms
            var nextTick = System.nanoTime()
            var lastTimeNs = nextTick

            while (collectorThreadRunning && !Thread.currentThread().isInterrupted) {
                val nowNs = System.nanoTime()
                val elapsedNs = nowNs - lastTimeNs
                lastTimeNs = nowNs

                val snapshot = bytesReceived
                bytesReceived = 0

                val elapsedSec = elapsedNs / 1_000_000_000.0
                val bits = snapshot * 8.0
                val mbps = (bits / 1_000_000.0) / elapsedSec

                synchronized(throughputSamples) {
                    throughputSamples.add(mbps)
                }

                // Create CSV log line.
                val dateNow = Date()
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                val formattedTime = sdf.format(dateNow)
                val epoch = System.currentTimeMillis()
                val csvLine = "$formattedTime,$epoch,%.3f".format(Locale.US, mbps)

                // Append locally.
                synchronized(csvLogBuffer) {
                    csvLogBuffer.add(csvLine)
                }
                appendCsvLogToFile(csvLine)

                // Instead of sending immediately, add to the network log buffer.
                synchronized(networkLogBuffer) {
                    networkLogBuffer.add(csvLine)
                }

                nextTick += intervalNanos
                val remaining = nextTick - System.nanoTime()
                if (remaining > 0) {
                    try {
                        Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    // Now the averaging thread is only updating the throughput UI.
    private fun startAveragingThread() {
        if (averagingThreadRunning) return
        averagingThreadRunning = true

        averagingThread = thread(start = true, name = "AveragingThread") {
            try {
                while (averagingThreadRunning && !Thread.currentThread().isInterrupted) {
                    val avgMbps = synchronized(throughputSamples) {
                        if (throughputSamples.isNotEmpty()) throughputSamples.average() else 0.0
                    }
                    _throughput.value = String.format(Locale.US, "%.3f", avgMbps)
                    synchronized(throughputSamples) {
                        throughputSamples.clear()
                    }
                    Thread.sleep(1000) // Update every second.
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(TAG, "Averaging thread error", e)
            } finally {
                Log.d(TAG, "Averaging thread stopped")
            }
        }
    }

    private fun appendCsvLogToFile(logLine: String) {
        try {
            val folder = logFolderPath ?: return
            val dirFile = File(folder)
            if (!dirFile.exists()) {
                dirFile.mkdirs()
            }
            val file = File(dirFile, "logs.csv")
            if (!file.exists()) {
                FileWriter(file).use { fw ->
                    fw.write("timestamp,epoch_ms,throughput_mbps\n")
                }
            }
            FileWriter(file, true).use { fw ->
                fw.appendLine(logLine)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error appending CSV log", e)
        }
    }
}
