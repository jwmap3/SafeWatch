package com.safewatch.core.tv

import java.io.BufferedReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A small JSON reader, enough for the messages YouTube's TV app sends: objects become maps,
 * arrays lists, numbers Double, and the rest strings, booleans and null.
 */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.value()
        p.space()
        if (p.i != text.length) throw IllegalArgumentException("Unexpected text at ${p.i}")
        return value
    }

    private class Parser(val s: String) {
        var i = 0

        fun space() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            space()
            if (i >= s.length) throw IllegalArgumentException("Unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw IllegalArgumentException("Unexpected text at $i")
            i += w.length
            return v
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("Bad number at $start")
        }

        private fun str(): String {
            i++ // opening quote
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> { out.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            space()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value()
                space()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("Expected , or ] at ${i - 1}")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            space()
            if (s[i] == '}') { i++; return out }
            while (true) {
                space()
                val key = str()
                space()
                if (s[i++] != ':') throw IllegalArgumentException("Expected : at ${i - 1}")
                out[key] = value()
                space()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalArgumentException("Expected , or } at ${i - 1}")
                }
            }
        }
    }
}

/**
 * Messages from YouTube's TV app, as the remote-control connection delivers them.
 *
 * The connection answers in chunks: a line with the chunk's length, then the chunk, a JSON list
 * of events. Each event is [number, [name, arguments...]].
 */
object LoungeMessages {
    data class Event(val id: Long, val name: String, val args: List<Any?>) {
        /** The first argument as a map of strings, which is how most events carry their details. */
        val data: Map<String, String>
            get() = (args.firstOrNull() as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to (v?.toString() ?: "") } ?: emptyMap()
    }

    /** Reads chunks until the stream ends, handing each event to [onEvent]. */
    fun read(reader: BufferedReader, onEvent: (Event) -> Unit) {
        while (true) {
            val header = reader.readLine() ?: return
            val length = header.trim().toIntOrNull() ?: continue
            val chunk = StringBuilder()
            var remaining = length
            while (remaining > 0) {
                val line = reader.readLine() ?: return
                chunk.append(line)
                remaining -= line.length + 1
            }
            val list = try { events(chunk.toString()) } catch (e: IllegalArgumentException) { emptyList() }
            list.forEach(onEvent)
        }
    }

    fun events(chunk: String): List<Event> {
        val list = MiniJson.parse(chunk) as? List<*> ?: return emptyList()
        return list.mapNotNull { entry ->
            val pair = entry as? List<*> ?: return@mapNotNull null
            val id = (pair.getOrNull(0) as? Double)?.toLong() ?: return@mapNotNull null
            val body = pair.getOrNull(1) as? List<*> ?: return@mapNotNull null
            val name = body.firstOrNull() as? String ?: return@mapNotNull null
            Event(id, name, body.drop(1))
        }
    }
}

/**
 * When to mute a video that is playing on the TV, from stretches worked out from its captions.
 * The TV hears about it a moment late, so a mute is sent [leadMs] early; the stretches themselves
 * already end a little after each word.
 */
class TvMuteTimeline(stretches: List<LongRange>, private val leadMs: Long = 400) {
    private val ranges: List<LongRange> = stretches.sortedBy { it.first }.fold(ArrayList()) { out, r ->
        val last = out.lastOrNull()
        if (last != null && r.first <= last.last) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
        out
    }

    val count: Int get() = ranges.size

    /** Whether the sound should be off with the TV at [positionMs], sending the mute [lead] ms early. */
    fun mutedAt(positionMs: Long, lead: Long = leadMs): Boolean = inside(positionMs) || inside(positionMs + lead)

    /** How long until the answer of [mutedAt] next changes, at most [maxMs]. */
    fun untilChange(positionMs: Long, maxMs: Long = 1000, lead: Long = leadMs): Long {
        val now = mutedAt(positionMs, lead)
        var t = 50L
        while (t < maxMs) {
            if (mutedAt(positionMs + t, lead) != now) return t
            t += 50
        }
        return maxMs
    }

    private fun inside(at: Long): Boolean {
        var lo = 0
        var hi = ranges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val r = ranges[mid]
            when {
                at < r.first -> hi = mid - 1
                at >= r.last -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }
}

/** A TV whose YouTube app was linked to EdenOS with the TV's code. */
data class LoungeScreen(val screenId: String, val token: String, val name: String)

/** Why YouTube turned a request down; [status] is the HTTP status, 0 when YouTube could not be reached. */
class LoungeException(message: String, val status: Int = 0, val unlinked: Boolean = false) : Exception(message) {
    /** The TV link's token ran out: ask for a new one, then connect again. */
    val expired: Boolean get() = status == 401

    /** The connection to the TV is over: connect again. */
    val gone: Boolean get() = status == 400 || status == 404 || status == 410
}

/**
 * YouTube's own way for a phone to work a TV's YouTube app: the "Link with TV code" remote, which the
 * YouTube phone app uses too. The TV plays the video itself, from YouTube; EdenOS only sends it the
 * same commands a remote would (play this, mute, unmute, jump ahead).
 */
class LoungeClient(
    private val base: String = "https://www.youtube.com/api/lounge",
    val name: String = "EdenOS",
) {
    /** Links a TV from the code its YouTube app shows under Settings > Link with TV code. */
    fun pair(code: String): LoungeScreen {
        val digits = code.filter { it.isDigit() }
        if (digits.length < 8) throw LoungeException("Type the whole code the TV shows: 12 numbers.", 400)
        val (status, body) = post("$base/pairing/get_screen", mapOf("pairing_code" to digits))
        if (status == 404 || status == 400) throw LoungeException("That code did not work. The TV makes a new code each time; check the one it shows now.", status)
        if (status != 200) throw LoungeException("YouTube answered $status. Try again in a moment.", status)
        val screen = (parseObject(body)["screen"] as? Map<*, *>) ?: throw LoungeException("That code did not work.", 404)
        return LoungeScreen(
            screen["screenId"] as? String ?: throw LoungeException("YouTube sent no TV for that code.", 404),
            screen["loungeToken"] as? String ?: "",
            (screen["name"] as? String)?.takeIf { it.isNotBlank() } ?: "TV",
        )
    }

    /** A fresh token for a TV linked before. Tokens last a couple of weeks. */
    fun refresh(screen: LoungeScreen): LoungeScreen {
        val (status, body) = post("$base/pairing/get_lounge_token_batch", mapOf("screen_ids" to screen.screenId))
        if (status != 200) throw LoungeException("YouTube did not renew the TV link ($status).", status)
        val list = parseObject(body)["screens"] as? List<*>
        val found = list?.firstOrNull() as? Map<*, *> ?: throw LoungeException("YouTube no longer knows this TV. Link it again.", 404, unlinked = true)
        return screen.copy(token = found["loungeToken"] as? String ?: throw LoungeException("YouTube sent no token.", 404))
    }

    /** Whether the TV's YouTube app is on and ready, or null when YouTube did not say. */
    fun available(screen: LoungeScreen): Boolean? = try {
        val (status, body) = post("$base/pairing/get_screen_availability", mapOf("lounge_token" to screen.token), timeoutMs = 10_000)
        if (status != 200) null
        else ((parseObject(body)["screens"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("status")?.let { it == "online" }
    } catch (e: LoungeException) {
        null
    }

    /** Opens a connection to the TV. The first news from the TV goes to [onEvent] straight away. */
    fun connect(screen: LoungeScreen, onEvent: (LoungeMessages.Event) -> Unit): LoungeSession {
        val form = linkedMapOf(
            "app" to "web", "mdx-version" to "3", "name" to name, "id" to screen.screenId,
            "device" to "REMOTE_CONTROL", "capabilities" to "que,dsdtr,atp,vsp", "magnaKey" to "cloudPairedDevice",
            "ui" to "false", "deviceContext" to "user_agent=dunno&window_width_points=&window_height_points=&os_name=android&ms=",
            "theme" to "cl", "loungeIdToken" to screen.token,
        )
        val (status, body) = post("$base/bc/bind?RID=1&VER=8&CVER=1&auth_failure_option=send_error", form)
        if (status == 401) throw LoungeException("The TV link has run out.", 401)
        if (status != 200) throw LoungeException("The TV could not be reached ($status). Is it on, with YouTube open?", status)
        val session = LoungeSession(this, base, screen)
        LoungeMessages.read(BufferedReader(StringReader(body))) { session.take(it, onEvent) }
        if (session.sid == null || session.gsession == null) throw LoungeException("The TV did not answer.", 0)
        return session
    }

    internal fun post(url: String, form: Map<String, String>, timeoutMs: Int = 20_000): Pair<Int, String> {
        val c = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw LoungeException("Could not reach YouTube: ${e.message}")
        }
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(encode(form).toByteArray()) }
            val status = c.responseCode
            val body = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to body
        } catch (e: LoungeException) {
            throw e
        } catch (e: Exception) {
            throw LoungeException("Could not reach YouTube: ${e.message}")
        } finally {
            c.disconnect()
        }
    }

    private fun parseObject(body: String): Map<*, *> =
        try { MiniJson.parse(body) as? Map<*, *> } catch (e: IllegalArgumentException) { null } ?: emptyMap<String, Any>()

    companion object {
        fun encode(form: Map<String, String>): String =
            form.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
    }
}

/** One open connection to a TV's YouTube app: news from the TV, and commands to it. */
class LoungeSession internal constructor(private val client: LoungeClient, private val base: String, val screen: LoungeScreen) {
    @Volatile var sid: String? = null
        private set
    @Volatile var gsession: String? = null
        private set
    @Volatile private var lastEventId = 0L
    private var offset = 1
    @Volatile private var listening: HttpURLConnection? = null
    @Volatile private var closed = false

    internal fun take(e: LoungeMessages.Event, onEvent: (LoungeMessages.Event) -> Unit) {
        lastEventId = maxOf(lastEventId, e.id)
        when (e.name) {
            "c" -> sid = e.args.firstOrNull() as? String
            "S" -> gsession = e.args.firstOrNull() as? String
            else -> onEvent(e)
        }
    }

    private fun common(): Map<String, String> = linkedMapOf(
        "name" to client.name, "loungeIdToken" to screen.token, "SID" to (sid ?: ""), "AID" to lastEventId.toString(),
        "gsessionid" to (gsession ?: ""), "device" to "REMOTE_CONTROL", "app" to "youtube-desktop", "VER" to "8", "v" to "2",
    )

    /**
     * Waits for news from the TV, handing each event to [onEvent], until YouTube ends this wait (it does
     * every few minutes; then call again). Throws [LoungeException] when the connection has to be made again.
     */
    fun listen(onEvent: (LoungeMessages.Event) -> Unit, waitMs: Int = 120_000) {
        if (closed) throw LoungeException("Closed", 410)
        val url = "$base/bc/bind?" + LoungeClient.encode(common() + mapOf("RID" to "rpc", "CI" to "0", "TYPE" to "xmlhttp"))
        val c = try { URI(url).toURL().openConnection() as HttpURLConnection } catch (e: Exception) { throw LoungeException("Could not reach YouTube: ${e.message}") }
        listening = c
        try {
            c.connectTimeout = 20_000
            c.readTimeout = waitMs
            val status = c.responseCode
            if (status != 200) throw LoungeException("The TV connection ended ($status).", status)
            c.inputStream.bufferedReader().use { reader -> LoungeMessages.read(reader) { take(it, onEvent) } }
        } catch (e: SocketTimeoutException) {
            // Nothing for a while: simply wait again.
        } catch (e: LoungeException) {
            throw e
        } catch (e: Exception) {
            if (closed) return
            throw LoungeException("Lost the TV connection: ${e.message}")
        } finally {
            listening = null
            c.disconnect()
        }
    }

    /** Sends one command, such as "setVolume" with its values, as the YouTube remote would. */
    @Synchronized
    fun command(name: String, params: Map<String, String> = emptyMap()) {
        if (closed) return
        val form = linkedMapOf("count" to "1", "ofs" to offset.toString(), "req0__sc" to name)
        params.forEach { (k, v) -> form["req0_$k"] = v }
        offset++
        val (status, _) = client.post("$base/bc/bind?" + LoungeClient.encode(common() + ("RID" to offset.toString())), form)
        if (status != 200) throw LoungeException("The TV did not take \"$name\" ($status).", status)
    }

    /** Ends the connection; the TV keeps playing. */
    fun close() {
        if (closed) return
        try {
            client.post("$base/bc/bind?" + LoungeClient.encode(common() + mapOf("RID" to (offset + 1).toString(), "CVER" to "1",
                "auth_failure_option" to "send_error")), mapOf("ui" to "", "TYPE" to "terminate",
                "clientDisconnectReason" to "MDX_SESSION_DISCONNECT_REASON_DISCONNECTED_BY_USER"), timeoutMs = 5000)
        } catch (e: Exception) {
            // Already gone.
        }
        closed = true
        try { listening?.disconnect() } catch (e: Exception) { /* already closed */ }
    }
}

/**
 * Follows a YouTube video playing on the TV from what the TV reports, and works out when its sound should
 * be off and when to jump past a marked scene. The TV only reports where it is when something changes
 * (play, pause, a jump), so between reports the position is worked out from the clock.
 */
class TvYouTubeFollower(
    val videoId: String,
    mute: List<LongRange>,
    skips: List<LongRange>,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    val timeline = TvMuteTimeline(mute)
    private val skips = skips.sortedBy { it.first }

    /** How early commands are sent, to make up for the trip through YouTube to the TV. Adjusts to the delay seen. */
    @Volatile var leadMs = 700L
        private set
    @Volatile var started = false
        private set
    @Volatile var playing = false
        private set
    @Volatile var ended = false
        private set
    @Volatile var advert = false
        private set
    /** Set when the TV has moved on to a different video, which EdenOS has no captions for. */
    @Volatile var otherVideo: String? = null
        private set
    @Volatile var volume = 100
        private set
    @Volatile var tvMuted = false
        private set
    /** The TV's YouTube app, when it said which one: YouTube Kids does not take commands. */
    @Volatile var client: String? = null
        private set

    private var reportedMs = 0L
    private var reportedAt = 0L
    private var speed = 1.0
    private var muteSentAt = 0L

    @Synchronized
    fun take(e: LoungeMessages.Event) {
        val d = e.data
        when (e.name) {
            "nowPlaying" -> {
                val id = d["videoId"].orEmpty()
                when {
                    id.isEmpty() -> Unit
                    id != videoId -> if (started) otherVideo = id
                    else -> { otherVideo = null; position(d) }
                }
            }
            "onStateChange" -> if (otherVideo == null) position(d)
            "onVolumeChanged" -> {
                volume = d["volume"]?.toIntOrNull() ?: volume
                val muted = d["muted"] == "true"
                if (muted && !tvMuted && muteSentAt > 0) {
                    // The TV confirmed a mute: the round trip shows how early to send the next one.
                    val trip = clock() - muteSentAt
                    if (trip in 50..4000) leadMs = ((leadMs * 2 + trip + 250) / 3).coerceIn(450, 1800)
                    muteSentAt = 0
                }
                tvMuted = muted
            }
            "adPlaying" -> advert = true
            "onAdStateChange" -> advert = d["adState"] != "0"
            "onPlaybackSpeedChanged" -> d["playbackSpeed"]?.toDoubleOrNull()?.let { speed = it }
            "loungeStatus" -> {
                val devices = d["devices"]?.let { try { MiniJson.parse(it) as? List<*> } catch (x: IllegalArgumentException) { null } }
                devices?.mapNotNull { it as? Map<*, *> }?.firstOrNull { it["type"] == "LOUNGE_SCREEN" }?.let { screen ->
                    val info = (screen["deviceInfo"] as? String)?.let { try { MiniJson.parse(it) as? Map<*, *> } catch (x: IllegalArgumentException) { null } }
                    client = info?.get("clientName") as? String
                }
            }
        }
    }

    private fun position(d: Map<String, String>) {
        val state = d["state"] ?: return
        val at = d["currentTime"]?.toDoubleOrNull()
        if (at != null) {
            reportedMs = (at * 1000).toLong()
            reportedAt = clock()
        }
        playing = state == "1"
        ended = state == "0"
        if (state == "1") { started = true; advert = false }
    }

    /** Where the TV is in the video now, in milliseconds. */
    @Synchronized
    fun positionMs(): Long = if (playing) reportedMs + ((clock() - reportedAt) * speed).toLong() else reportedMs

    /**
     * Whether the TV's sound should be off now. A mute is sent [leadMs] early; the sound comes back a
     * little before the stretch ends, as it takes a moment to arrive and every stretch ends well after its word.
     */
    fun shouldMute(): Boolean {
        if (!started || otherVideo != null || advert) return false
        return mutedAt(positionMs())
    }

    private fun mutedAt(position: Long): Boolean {
        val early = minOf(300L, leadMs / 2)
        return timeline.mutedAt(position + early, leadMs - early)
    }

    /** A marked scene the TV is about to play, as the place to jump to, or null. */
    fun skipTo(): Long? {
        if (!started || !playing || otherVideo != null || advert) return null
        return skipAt(positionMs())
    }

    private fun skipAt(position: Long): Long? = skips.firstOrNull { position + leadMs >= it.first && position < it.last }?.last

    /** How long to wait before looking again: until the next mute, unmute or jump is due, and at most a second. */
    fun nextCheckMs(): Long {
        if (!playing) return 500
        val at = positionMs()
        val muted = mutedAt(at)
        val skip = skipAt(at)
        var t = 25L
        while (t < 1000) {
            if (mutedAt(at + (t * speed).toLong()) != muted || skipAt(at + (t * speed).toLong()) != skip) return t
            t += 25
        }
        return 1000
    }

    @Synchronized
    fun muteSent() { muteSentAt = clock() }
}

/** What the TV filter is doing, for the app to show. */
interface TvYouTubeListener {
    fun status(text: String)
    fun finished(reason: String)
}

/**
 * Plays one YouTube video on a linked TV and keeps it clean: starts it, mutes the sound for each
 * stretch worked out from the captions, jumps past marked scenes, and turns the TV's captions off when
 * asked to. Runs until [stop], the video ends, or the TV moves on to another video.
 */
class TvYouTubeRemote(
    private val client: LoungeClient,
    private var screen: LoungeScreen,
    private val follower: TvYouTubeFollower,
    private val startAtMs: Long,
    private val hideCaptions: Boolean,
    private val listener: TvYouTubeListener,
    private val onNewToken: (LoungeScreen) -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var session: LoungeSession? = null
    @Volatile private var stopped = false
    @Volatile var mutes = 0
        private set
    @Volatile private var mutedByUs = false
    // Only touched on the timer's thread:
    private var askedAt = 0L
    private var lastSkipTo = -1L
    private var lastSkipAt = 0L
    private var lastSync = 0L
    private var check: ScheduledFuture<*>? = null
    private var captionsHidden = false
    private var endedSince = 0L
    private var said = ""

    /** Runs the connection to the TV on the calling thread until the work is over. */
    fun run() {
        var failures = 0
        var first = true
        while (!stopped) {
            try {
                if (first) waitForTv()
                if (stopped) return
                val s = connect()
                session = s
                if (follower.client == "TVHTML5_FOR_KIDS") {
                    finish("This TV is running YouTube Kids, which does not take commands from a phone. Open the main YouTube app on the TV.")
                    return
                }
                if (first) {
                    first = false
                    s.command("setAutoplayMode", mapOf("autoplayMode" to "DISABLED"))
                    val params = linkedMapOf("videoId" to follower.videoId, "audioOnly" to "false")
                    if (startAtMs > 3000) params["currentTime"] = String.format(java.util.Locale.US, "%.1f", startAtMs / 1000.0)
                    s.command("setPlaylist", params)
                    tell("Starting on ${screen.name}")
                }
                failures = 0
                later(200)
                var quick = 0
                while (!stopped) {
                    val began = clock()
                    s.listen({ follower.take(it); later(0) })
                    // A wait that ends at once, again and again, means the connection is not right: make it afresh.
                    if (clock() - began < 1000) {
                        if (++quick > 5) throw LoungeException("The TV connection keeps closing.", 410)
                        try { Thread.sleep(1000) } catch (e: InterruptedException) { return }
                    } else quick = 0
                }
            } catch (e: LoungeException) {
                if (stopped) return
                session = null
                if (e.unlinked) { finish(e.message ?: "Link the TV again."); return }
                failures++
                if (failures > 5) { finish("Lost the connection to ${screen.name}. ${e.message}"); return }
                tell("Reconnecting to ${screen.name}…")
                try { Thread.sleep(1000L * failures) } catch (x: InterruptedException) { return }
            }
        }
    }

    /** Waits up to three minutes for the TV to be on with YouTube ready. */
    private fun waitForTv() {
        val until = clock() + 180_000
        while (!stopped && client.available(screen) == false && clock() < until) {
            tell("Turn on ${screen.name} and open YouTube on it")
            try { Thread.sleep(3000) } catch (e: InterruptedException) { return }
        }
    }

    private fun connect(): LoungeSession = try {
        client.connect(screen) { follower.take(it) }
    } catch (e: LoungeException) {
        if (!e.expired) throw e
        screen = client.refresh(screen)
        onNewToken(screen)
        client.connect(screen) { follower.take(it) }
    }

    /** Stops filtering, leaving the TV playing with its sound as the viewer had it. */
    fun stop() {
        if (stopped) return
        stopped = true
        val s = session
        val unmute = mutedByUs
        try {
            sender.execute {
                if (unmute) try { s?.command("setVolume", mapOf("volume" to follower.volume.toString(), "muted" to "false")) } catch (e: Exception) { /* the TV's remote can unmute */ }
                s?.close()
            }
        } catch (e: Exception) {
            s?.close()
        }
        sender.shutdown()
        timer.shutdownNow()
    }

    private fun finish(reason: String) {
        stop()
        listener.finished(reason)
    }

    private fun tell(text: String) {
        if (text == said) return
        said = text
        listener.status(text)
    }

    /** Looks again after [delayMs]. */
    private fun later(delayMs: Long) {
        if (stopped) return
        try {
            timer.execute {
                check?.cancel(false)
                check = timer.schedule({ look() }, delayMs, TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) {
            // Stopping.
        }
    }

    /** Looks at where the TV is and sends what is needed: mute, unmute or a jump. Runs on the timer's thread. */
    private fun look() {
        if (stopped) return
        val s = session
        if (follower.otherVideo != null) {
            finish("A different video started on ${screen.name}, so EdenOS stopped filtering. Send it from EdenOS to filter it.")
            return
        }
        val now = clock()
        if (follower.ended) {
            if (endedSince == 0L) endedSince = now
            if (now - endedSince > 90_000) { finish("The video ended."); return }
        } else endedSince = 0
        if (s != null && follower.started) {
            if (hideCaptions && !captionsHidden) {
                captionsHidden = true
                send(s, "setSubtitlesTrack", mapOf("languageCode" to "", "videoId" to follower.videoId))
            }
            val want = follower.shouldMute()
            if (want != mutedByUs) {
                mutedByUs = want
                askedAt = now
                if (want) { mutes++; follower.muteSent() }
                send(s, "setVolume", mapOf("volume" to follower.volume.toString(), "muted" to want.toString()))
            } else if (askedAt > 0 && follower.tvMuted != mutedByUs && now - askedAt > 2500) {
                // The TV never said it did as asked: ask once more.
                askedAt = 0
                send(s, "setVolume", mapOf("volume" to follower.volume.toString(), "muted" to mutedByUs.toString()))
            } else if (follower.tvMuted == mutedByUs) {
                askedAt = 0
            }
            follower.skipTo()?.let { to ->
                if (to != lastSkipTo || now - lastSkipAt > 5000) {
                    lastSkipTo = to
                    lastSkipAt = now
                    send(s, "seekTo", mapOf("newTime" to String.format(java.util.Locale.US, "%.1f", to / 1000.0)))
                }
            }
            // The TV only says where it is when something changes; ask now and then, so the clock does not drift.
            if (follower.playing && now - lastSync > 30_000) {
                lastSync = now
                send(s, "getNowPlaying", emptyMap())
            }
            tell(if (follower.playing) "Filtering on ${screen.name}" + (if (mutes > 0) " · muted $mutes ${if (mutes == 1) "time" else "times"}" else "")
                else "Paused on ${screen.name}")
        }
        val wait = follower.nextCheckMs()
        try { check = timer.schedule({ look() }, wait, TimeUnit.MILLISECONDS) } catch (e: Exception) { /* stopping */ }
    }

    private fun send(s: LoungeSession, name: String, params: Map<String, String>) {
        try {
            sender.execute { try { s.command(name, params) } catch (e: Exception) { /* looked at again shortly */ } }
        } catch (e: Exception) {
            // Stopping.
        }
    }
}
