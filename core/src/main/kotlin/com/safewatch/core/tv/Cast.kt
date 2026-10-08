package com.safewatch.core.tv

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Passes a website's video on to the TV through the phone, so the TV can play it in its own player at full
 * quality even when the site only hands its video to a request that looks like its own player's: the phone
 * fetches each piece with the site's headers and cookies ([headersFor]) and passes it straight on. A stream's
 * list of pieces is rewritten so its pieces come through the phone too. Nothing is kept on the phone.
 */
class StreamProxy(private val headersFor: (String) -> Map<String, String>) {
    private val secret = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile var lastRequestAt = 0L
        private set
    @Volatile var requests = 0
        private set

    val port: Int get() = server?.localPort ?: 0

    fun start(bindTo: InetAddress? = null): StreamProxy {
        val s = ServerSocket(0, 50, bindTo)
        server = s
        Thread({
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: IOException) { break }
                pool.execute { try { client.use { answer(it) } } catch (e: Exception) { /* the TV hung up */ } }
            }
        }, "stream-proxy").apply { isDaemon = true }.start()
        return this
    }

    fun stop() {
        try { server?.close() } catch (e: IOException) { /* already closed */ }
        pool.shutdownNow()
    }

    /** The address the TV fetches [target] from, through the phone at [phone]. */
    fun url(phone: String, target: String): String {
        val name = Regex("[^/?#]+(?=([?#]|$))").find(URL(target).path)?.value?.takeIf { '.' in it } ?: "video"
        return "http://$phone:$port/$secret/" + Base64.getUrlEncoder().withoutPadding().encodeToString(target.toByteArray()) + "/" + name
    }

    private fun answer(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, path) = lines[0].split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val out = client.getOutputStream()
        val parts = path.trimStart('/').split('/')
        if (parts.size < 2 || parts[0] != secret || (method != "GET" && method != "HEAD")) return status(out, "404 Not Found")
        val target = try { String(Base64.getUrlDecoder().decode(parts[1])) } catch (e: IllegalArgumentException) { return status(out, "404 Not Found") }
        if (!target.startsWith("http")) return status(out, "404 Not Found")
        requests++
        lastRequestAt = System.currentTimeMillis()
        val phone = client.localAddress.hostAddress ?: return
        val c = URL(target).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.requestMethod = method
            for ((k, v) in headersFor(target)) c.setRequestProperty(k, v)
            headers["range"]?.let { c.setRequestProperty("Range", it) }
            val code = c.responseCode
            val type = c.contentType.orEmpty()
            if (code in 200..299 && isPlaylist(target, type)) {
                // A stream's list of pieces: every piece, and every other list, comes through the phone too.
                val text = (c.inputStream).bufferedReader().use { it.readText() }
                val body = rewrite(text, c.url.toString(), phone).toByteArray()
                write(out, "200 OK", listOf("Content-Type" to "application/vnd.apple.mpegurl", "Content-Length" to body.size.toString(), "Connection" to "close"))
                if (method != "HEAD") out.write(body)
                return
            }
            val reply = ArrayList<Pair<String, String>>()
            reply += "Content-Type" to type.ifEmpty { "video/mp4" }
            c.getHeaderField("Content-Length")?.let { reply += "Content-Length" to it }
            c.getHeaderField("Content-Range")?.let { reply += "Content-Range" to it }
            reply += "Accept-Ranges" to "bytes"
            reply += "Connection" to "close"
            write(out, "$code ${c.responseMessage ?: "OK"}", reply)
            if (method == "HEAD" || code >= 400) return
            c.inputStream.use { copy(it, out) }
        } finally {
            c.disconnect()
        }
    }

    /** A stream's list rewritten so that each address in it is fetched through the phone. */
    fun rewrite(text: String, base: String, phone: String): String = text.lines().joinToString("\n") { line ->
        val t = line.trim()
        when {
            t.isEmpty() -> line
            t.startsWith("#") -> Regex("URI=\"([^\"]+)\"").replace(line) { m -> "URI=\"" + url(phone, URL(URL(base), m.groupValues[1]).toString()) + "\"" }
            else -> url(phone, URL(URL(base), t).toString())
        }
    }

    private fun isPlaylist(target: String, type: String): Boolean =
        type.contains("mpegurl", true) || Regex("\\.m3u8?(\\?|#|$)", RegexOption.IGNORE_CASE).containsMatchIn(target)

    private fun copy(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            lastRequestAt = System.currentTimeMillis()
        }
        out.flush()
    }

    private fun status(out: OutputStream, status: String) = write(out, status, listOf("Content-Length" to "0", "Connection" to "close"))

    private fun write(out: OutputStream, status: String, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        out.write(sb.append("\r\n").toString().toByteArray())
        out.flush()
    }

    private fun readHead(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 16_384) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString().trimEnd()
        }
        return null
    }
}

/**
 * Keeps a video playing in the TV's own player clean, from the phone: given where the TV is, says whether its
 * sound should be off now and whether to jump past a scene. The TV only says where it is when asked, so each
 * answer is treated as a moment ago and muting starts [leadMs] early.
 */
class CastFollower(mute: List<LongRange>, skip: List<LongRange>, private val leadMs: Long = 700) {
    private val mute = mute.sortedBy { it.first }
    private val skip = skip.sortedBy { it.first }

    sealed class Command {
        data class Mute(val on: Boolean) : Command()
        data class Seek(val toMs: Long) : Command()
    }

    var muted = false
        private set
    private var seekingTo = -1L

    /** What to tell the TV, now that it says it is at [positionMs]. */
    fun at(positionMs: Long): List<Command> {
        val out = ArrayList<Command>()
        // A scene to jump past, unless a jump past it was just asked for and the TV has not caught up yet.
        val scene = skip.firstOrNull { positionMs >= it.first - 200 && positionMs < it.last }
        if (scene != null && seekingTo != scene.last) {
            seekingTo = scene.last
            if (!muted) { muted = true; out += Command.Mute(true) }
            out += Command.Seek(scene.last)
            return out
        }
        if (scene == null && seekingTo >= 0 && positionMs >= seekingTo - 1000) seekingTo = -1
        val shouldMute = scene != null || mute.any { positionMs + leadMs >= it.first && positionMs < it.last + 150 }
        if (shouldMute != muted) {
            muted = shouldMute
            out += Command.Mute(shouldMute)
        }
        return out
    }
}
