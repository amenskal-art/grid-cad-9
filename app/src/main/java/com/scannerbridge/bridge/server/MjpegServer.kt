package com.scannerbridge.bridge.server

import android.util.Log
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A tiny single-purpose HTTP server that streams MJPEG over
 * multipart/x-mixed-replace -- the exact format OpenCV/FFmpeg's
 * VideoCapture(url, CAP_FFMPEG) consumes on the PC side.
 *
 * Endpoints:
 *   GET /video   -> multipart/x-mixed-replace MJPEG stream
 *   GET /        -> minimal status / preview HTML page
 *   GET /health  -> "ok" (used by the PC tool to verify reachability)
 *
 * Design notes:
 *  - There is ONE latest frame (AtomicReference). Each connected client
 *    runs its own writer loop and sends whatever the newest frame is at
 *    its own pace, so a slow client never blocks the camera or others.
 *  - Frames are pushed in via [submitFrame] from the UVC callback.
 */
class MjpegServer(private val port: Int = 8080) {

    companion object {
        private const val TAG = "MjpegServer"
        private const val BOUNDARY = "scannerbridgeframe"
        private const val TARGET_FPS = 30
    }

    interface Listener {
        fun onClientCountChanged(count: Int)
        fun onServerError(message: String)
    }

    var listener: Listener? = null

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()

    /** Newest JPEG bytes, shared by all client writers. */
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private val frameSeq = AtomicInteger(0)

    private val clientCount = AtomicInteger(0)

    val isRunning: Boolean get() = running.get()
    val connectedClients: Int get() = clientCount.get()

    fun start() {
        if (running.getAndSet(true)) return
        acceptExecutor.execute {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                serverSocket = s
                Log.i(TAG, "MJPEG server listening on :$port")
                while (running.get()) {
                    val client = try {
                        s.accept()
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "accept() failed", e)
                        break
                    }
                    clientExecutor.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed", e)
                listener?.onServerError(e.message ?: "Server error")
                running.set(false)
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        latestJpeg.set(null)
        Log.i(TAG, "MJPEG server stopped")
    }

    /** Push a fresh JPEG frame; replaces any not-yet-sent frame. */
    fun submitFrame(jpeg: ByteArray) {
        latestJpeg.set(jpeg)
        frameSeq.incrementAndGet()
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            try {
                sock.tcpNoDelay = true
                val input = sock.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return
                // Drain remaining header lines.
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val out = BufferedOutputStream(sock.getOutputStream())
                val path = requestLine.split(" ").getOrElse(1) { "/" }

                when {
                    path.startsWith("/video") -> streamMjpeg(out)
                    path.startsWith("/health") -> writeText(out, "ok")
                    else -> writeLandingPage(out)
                }
            } catch (_: Exception) {
                // client disconnected; ignore
            }
        }
    }

    private fun writeText(out: OutputStream, body: String) {
        val bytes = body.toByteArray()
        val header = "HTTP/1.0 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun writeLandingPage(out: OutputStream) {
        val html = """
            <!doctype html><html><head><meta charset="utf-8">
            <title>Scanner Bridge</title>
            <style>body{background:#12161c;color:#e8eef5;font-family:sans-serif;
            text-align:center;padding:24px}img{max-width:96%;border-radius:12px;
            border:1px solid #1f2933}h2{color:#28d0e8}</style></head>
            <body><h2>Scanner Bridge</h2>
            <p>Live MJPEG endpoint: <code>/video</code></p>
            <img src="/video"/></body></html>
        """.trimIndent()
        val bytes = html.toByteArray()
        val header = "HTTP/1.0 200 OK\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun streamMjpeg(out: OutputStream) {
        val header = "HTTP/1.0 200 OK\r\n" +
                "Cache-Control: no-cache, no-store, max-age=0, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n\r\n"
        out.write(header.toByteArray())
        out.flush()

        val count = clientCount.incrementAndGet()
        listener?.onClientCountChanged(count)

        var lastSeq = -1
        val frameIntervalMs = (1000 / TARGET_FPS).toLong()
        try {
            while (running.get()) {
                val seq = frameSeq.get()
                if (seq == lastSeq) {
                    Thread.sleep(2)
                    continue
                }
                lastSeq = seq
                val jpeg = latestJpeg.get() ?: continue

                val partHeader = "--$BOUNDARY\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${jpeg.size}\r\n\r\n"
                out.write(partHeader.toByteArray())
                out.write(jpeg)
                out.write("\r\n".toByteArray())
                out.flush()

                Thread.sleep(frameIntervalMs)
            }
        } catch (_: Exception) {
            // client gone / write failed
        } finally {
            val c = clientCount.decrementAndGet()
            listener?.onClientCountChanged(c)
        }
    }
}
