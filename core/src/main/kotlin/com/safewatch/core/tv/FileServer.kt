package com.safewatch.core.tv

import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Hands one video file to a TV on the home network. The TV asks for the file, usually a piece
 * at a time as it plays (HTTP range requests), and this answers. Only the file's own secret
 * address is answered, so nothing else on the phone can be asked for.
 */
class FileServer(private val file: File, private val mime: String = "video/mp4") {
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val secret = UUID.randomUUID().toString().replace("-", "").take(16)

    /** When the TV last asked for anything, in milliseconds since 1970; 0 before the first request. */
    @Volatile var lastRequestAt = 0L
        private set

    @Volatile var requests = 0
        private set

    /** How far into the file the TV has read, as a share from 0 to 1: near 1 once it has played to the end. */
    val readShare: Double get() = if (file.length() == 0L) 0.0 else furthest.toDouble() / file.length()

    @Volatile private var furthest = 0L

    val port: Int get() = socket?.localPort ?: 0

    /** The file's address as seen from the TV, given the phone's address on the network. */
    fun url(phoneAddress: String): String = "http://$phoneAddress:$port/$secret/video.mp4"

    fun start(bindTo: InetAddress? = null): FileServer {
        val server = ServerSocket(0, 16, bindTo)
        socket = server
        pool.execute {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: SocketException) { break }
                pool.execute { answer(client) }
            }
        }
        return this
    }

    fun stop() {
        try { socket?.close() } catch (e: Exception) { /* already closed */ }
        socket = null
        pool.shutdownNow()
    }

    private fun answer(client: Socket) {
        client.use { s ->
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            while (true) {
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val parts = lines.first().split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val headers = lines.drop(1).mapNotNull { line ->
                    val at = line.indexOf(':')
                    if (at > 0) line.substring(0, at).trim().lowercase() to line.substring(at + 1).trim() else null
                }.toMap()
                lastRequestAt = System.currentTimeMillis()
                requests++
                if (path != "/$secret/video.mp4" || (method != "GET" && method != "HEAD")) {
                    write(out, "404 Not Found", listOf("Content-Length" to "0"))
                    return
                }
                if (!serve(out, method, headers)) return
                if (headers["connection"].equals("close", ignoreCase = true)) return
            }
        }
    }

    /** Sends the file, or the part asked for. Returns false once the connection should end. */
    private fun serve(out: OutputStream, method: String, headers: Map<String, String>): Boolean {
        val length = file.length()
        var from = 0L
        var to = length - 1
        var partial = false
        Regex("bytes=(\\d*)-(\\d*)").find(headers["range"].orEmpty())?.let { m ->
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            if (a.isEmpty() && b.isNotEmpty()) {
                from = maxOf(0, length - b.toLong()) // the last b bytes
            } else {
                from = a.toLongOrNull() ?: 0
                if (b.isNotEmpty()) to = minOf(b.toLong(), length - 1)
            }
            partial = true
        }
        if (from > to || from >= length) {
            write(out, "416 Range Not Satisfiable", listOf("Content-Range" to "bytes */$length", "Content-Length" to "0"))
            return true
        }
        val common = listOf(
            "Content-Type" to mime,
            "Accept-Ranges" to "bytes",
            "Content-Length" to (to - from + 1).toString(),
            // Smart TVs check these before playing a file from the network.
            "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
            "transferMode.dlna.org" to "Streaming",
            "Connection" to "keep-alive",
        )
        if (partial) write(out, "206 Partial Content", common + ("Content-Range" to "bytes $from-$to/$length"))
        else write(out, "200 OK", common)
        if (method == "HEAD") return true
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val buffer = ByteArray(64 * 1024)
            var left = to - from + 1
            while (left > 0) {
                val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n <= 0) break
                try {
                    out.write(buffer, 0, n)
                } catch (e: SocketException) {
                    return false // the TV stopped listening, as it does when it jumps elsewhere in the film
                }
                left -= n
                furthest = maxOf(furthest, to - left + 1)
                lastRequestAt = System.currentTimeMillis()
            }
        }
        out.flush()
        return true
    }

    private fun write(out: OutputStream, status: String, headers: List<Pair<String, String>>) {
        val text = StringBuilder("HTTP/1.1 $status\r\n")
        headers.forEach { (k, v) -> text.append(k).append(": ").append(v).append("\r\n") }
        text.append("\r\n")
        out.write(text.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readHead(input: BufferedInputStream): String? {
        val bytes = StringBuilder()
        var last4 = 0
        while (true) {
            val b = try { input.read() } catch (e: Exception) { return null }
            if (b < 0) return null
            bytes.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return bytes.toString().trimEnd()
            if (bytes.length > 16_000) return null
        }
    }
}
