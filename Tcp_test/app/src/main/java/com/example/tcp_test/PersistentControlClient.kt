package com.example.tcp_test

import android.util.Log
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class PersistentControlClient(
    private val serverIp: String,
    private val serverPort: Int
) {
    companion object {
        private const val TAG = "ControlClient"
    }

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var readThread: Thread? = null

    @Volatile
    private var running = false

    /**
     * Connect to the server with optional timeouts.
     *
     * @param connectTimeoutMs how long to wait for TCP connect (milliseconds).
     * @param readTimeoutMs how long to wait for data (milliseconds) before SocketTimeoutException.
     */
    fun connect(connectTimeoutMs: Int = 3000, readTimeoutMs: Int = 2000) {
        if (running) {
            Log.w(TAG, "connect() called but already running.")
            return
        }

        // Create an unconnected Socket so we can set timeouts
        val sock = Socket()
        sock.soTimeout = readTimeoutMs // read timeout
        sock.connect(InetSocketAddress(serverIp, serverPort), connectTimeoutMs)
        socket = sock

        // Prepare a PrintWriter (autoFlush=false for control; we call flush explicitly)
        writer = PrintWriter(
            BufferedWriter(OutputStreamWriter(socket!!.getOutputStream())),
            /*autoFlush=*/ false
        )

        running = true
        Log.d(TAG, "Connected to $serverIp:$serverPort (persistent).")

        // Start a background thread to read responses (or watch for disconnection)
        readThread = Thread {
            val reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
            try {
                while (running && !Thread.currentThread().isInterrupted) {
                    val line = try {
                        reader.readLine()  // May throw SocketTimeoutException
                    } catch (ste: SocketTimeoutException) {
                        // If read timed out, loop again unless user called disconnect
                        if (!running) break
                        continue
                    }
                    if (line == null) {
                        // Means the server closed the connection
                        Log.d(TAG, "Server closed the control socket.")
                        break
                    }
                    Log.d(TAG, "Received from server: $line")
                    // TODO: You could add a callback or a queue to handle the incoming message
                }
            } catch (e: IOException) {
                if (running) {
                    Log.e(TAG, "Read error on control socket", e)
                }
            } finally {
                Log.d(TAG, "Read thread finished.")
                disconnect()  // Ensure we close everything
            }
        }
        readThread?.start()
    }

    /**
     * Send a single line (with newline appended) to the server.
     * Thread-safe thanks to the synchronized block.
     */
    fun sendLine(msg: String) {
        synchronized(this) {
            if (!running || writer == null) {
                Log.w(TAG, "sendLine() called while not connected or writer is null.")
                return
            }
            writer!!.println(msg)  // println() will append '\n'
            writer!!.flush()
        }
    }

    /**
     * Clean shutdown of the control connection.
     * Closes the socket, stops the read thread.
     */
    fun disconnect() {
        synchronized(this) {
            if (!running) return
            running = false
            try {
                socket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            socket = null
            writer = null
        }
        // Interrupt the read thread if it's still alive.
        readThread?.interrupt()
        try {
            readThread?.join(500)
        } catch (_: InterruptedException) {
        }
        readThread = null
        Log.d(TAG, "Disconnected from $serverIp:$serverPort.")
    }
}
