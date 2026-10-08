package com.safewatch.core.tv

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A TV on the home network that can play a video file by itself, fetching it from the phone.
 *
 * [ROKU] is a Roku stick or Roku TV, reached through Roku's own remote-control protocol.
 * [DLNA] is a smart TV (Samsung, LG, Sony and others) that accepts a video address through the
 * standard home-media protocol (UPnP AV / DLNA "media renderer").
 */
data class TvDevice(
    val kind: Kind,
    val name: String,
    /** Where the TV described itself, used as its identity. */
    val location: String,
    /** For a smart TV: where its playback controls are addressed. Unused for Roku. */
    val controlUrl: String = "",
    /** For a smart TV: where its sound controls (mute) are addressed, if it has them. */
    val renderingUrl: String = "",
) {
    enum class Kind { ROKU, DLNA }

    /** The TV's address on the network, such as 192.168.1.40. */
    val host: String get() = try { URL(location).host } catch (e: Exception) { "" }
}

/**
 * Finding TVs. Devices answer a multicast question (SSDP) with where their description is.
 * The network part is done by the app; this builds the question and reads the answers.
 */
object Ssdp {
    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900
    const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val ROKU = "roku:ecp"

    fun question(target: String): String =
        "M-SEARCH * HTTP/1.1\r\nHOST: $ADDRESS:$PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n"

    /** The headers of one answer, with names in capitals. */
    fun headers(answer: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in answer.split("\r\n", "\n").drop(1)) {
            val at = line.indexOf(':')
            if (at > 0) out[line.substring(0, at).trim().uppercase()] = line.substring(at + 1).trim()
        }
        return out
    }

    /** Whether an answer came from a Roku. */
    fun isRoku(headers: Map<String, String>): Boolean =
        headers["ST"]?.contains("roku", ignoreCase = true) == true || headers["SERVER"]?.contains("roku", ignoreCase = true) == true
}

/** Plain HTTP for the TV protocols. */
internal object Net {
    fun get(url: String, timeoutMs: Int = 4000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            if (c.responseCode !in 200..299) throw IOException("$url answered ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun post(url: String, body: String, headers: Map<String, String>, timeoutMs: Int = 6000): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.doOutput = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = try {
                (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            } catch (e: IOException) { "" }
            return code to text
        } finally {
            c.disconnect()
        }
    }

    fun unescapeXml(text: String): String = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}

/** Roku's remote-control protocol (ECP), on port 8060. */
object Roku {
    /** Reads a Roku's name. [location] is the address it gave when found, such as http://192.168.1.40:8060/. */
    fun describe(location: String): TvDevice? {
        val base = location.trimEnd('/') + "/"
        val info = try { Net.get(base + "query/device-info") } catch (e: Exception) { return null }
        fun field(name: String) = Regex("<$name>([^<]*)</$name>").find(info)?.groupValues?.get(1)?.trim().orEmpty()
        val name = field("user-device-name").ifEmpty { field("friendly-device-name") }.ifEmpty { field("model-name") }.ifEmpty { "Roku" }
        return TvDevice(TvDevice.Kind.ROKU, Net.unescapeXml(name), base)
    }

    /**
     * The request that has the Roku play [videoUrl] in its built-in "Play on Roku" player, the one
     * Roku's own phone app uses to show phone videos.
     */
    fun playRequest(device: TvDevice, videoUrl: String, title: String, format: String = "mp4"): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return device.location.trimEnd('/') + "/input/15985?t=v&u=" + enc(videoUrl) + "&k=(null)&videoName=" + enc(title) + "&videoFormat=" + format
    }

    fun play(device: TvDevice, videoUrl: String, title: String, format: String = "mp4") {
        val (code, _) = Net.post(playRequest(device, videoUrl, title, format), "", emptyMap())
        if (code !in 200..299) throw IOException("The Roku answered $code")
    }

    /** Where the Roku's player is in the video, or null when nothing is playing. */
    fun position(device: TvDevice): Long? {
        val info = Net.get(device.location.trimEnd('/') + "/query/media-player", 2500)
        return Regex("<position>\\s*(\\d+)\\s*ms\\s*</position>").find(info)?.groupValues?.get(1)?.toLongOrNull()
    }

    fun press(device: TvDevice, key: String) {
        Net.post(device.location.trimEnd('/') + "/keypress/$key", "", emptyMap())
    }
}

/** Smart TVs' standard home-media protocol: a TV's AVTransport service is told what to play and when. */
object Dlna {
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Reads a TV's description from [location] and finds its playback controls; null if it has none. */
    fun describe(location: String): TvDevice? {
        val xml = try { Net.get(location) } catch (e: Exception) { return null }
        return fromDescription(xml, location)
    }

    fun fromDescription(xml: String, location: String): TvDevice? {
        val doc = try {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder()
                .parse(xml.byteInputStream())
        } catch (e: Exception) {
            return null
        }
        fun first(parent: Element, tag: String): String =
            (parent.getElementsByTagName(tag).item(0)?.textContent ?: "").trim()
        val root = doc.documentElement
        val base = first(root, "URLBase").ifEmpty { location }
        val name = first(root, "friendlyName").ifEmpty { first(root, "modelName") }.ifEmpty { "TV" }
        val services = root.getElementsByTagName("service")
        var transport = ""
        var rendering = ""
        for (i in 0 until services.length) {
            val service = services.item(i) as Element
            val type = first(service, "serviceType")
            val control = first(service, "controlURL")
            if (control.isEmpty()) continue
            val url = try { URL(URL(base), control).toString() } catch (e: Exception) { continue }
            if (type.startsWith("urn:schemas-upnp-org:service:AVTransport:") && transport.isEmpty()) transport = url
            if (type.startsWith("urn:schemas-upnp-org:service:RenderingControl:") && rendering.isEmpty()) rendering = url
        }
        return if (transport.isEmpty()) null else TvDevice(TvDevice.Kind.DLNA, name, location, transport, rendering)
    }

    /** What the TV is told about the file: some TVs (Samsung in particular) refuse a video without it. */
    fun metadata(videoUrl: String, title: String, mime: String = "video/mp4"): String {
        val info = "http-get:*:$mime:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${Net.escape(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"$info\">${Net.escape(videoUrl)}</res></item></DIDL-Lite>"
    }

    private const val RENDERING = "urn:schemas-upnp-org:service:RenderingControl:1"

    fun envelope(action: String, arguments: List<Pair<String, String>>, service: String = AV_TRANSPORT): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$service\"><InstanceID>0</InstanceID>" +
            arguments.joinToString("") { (k, v) -> "<$k>${Net.escape(v)}</$k>" } +
            "</u:$action></s:Body></s:Envelope>"

    fun call(device: TvDevice, action: String, arguments: List<Pair<String, String>> = emptyList(), service: String = AV_TRANSPORT,
             url: String = device.controlUrl, timeoutMs: Int = 6000): String {
        val (code, answer) = Net.post(url, envelope(action, arguments, service), mapOf(
            "Content-Type" to "text/xml; charset=\"utf-8\"",
            "SOAPAction" to "\"$service#$action\"",
        ), timeoutMs)
        if (code !in 200..299) {
            val reason = Regex("<errorDescription>([^<]*)</errorDescription>").find(answer)?.groupValues?.get(1)
            throw IOException("The TV refused $action" + (reason?.let { ": $it" } ?: " ($code)"))
        }
        return answer
    }

    fun play(device: TvDevice, videoUrl: String, title: String, mime: String = "video/mp4") {
        try { call(device, "Stop") } catch (e: Exception) { /* nothing was playing */ }
        call(device, "SetAVTransportURI", listOf("CurrentURI" to videoUrl, "CurrentURIMetaData" to metadata(videoUrl, title, mime)))
        call(device, "Play", listOf("Speed" to "1"))
    }

    /** Where the TV is in the video, or null when it does not say. */
    fun position(device: TvDevice): Long? {
        val answer = call(device, "GetPositionInfo", timeoutMs = 2500)
        return clock(Regex("<RelTime>([^<]*)</RelTime>").find(answer)?.groupValues?.get(1) ?: return null)
    }

    fun seek(device: TvDevice, ms: Long) {
        call(device, "Seek", listOf("Unit" to "REL_TIME", "Target" to clock(ms)))
    }

    /** Mutes or unmutes the TV; false when it has no sound controls to do it with. */
    fun mute(device: TvDevice, on: Boolean): Boolean {
        if (device.renderingUrl.isEmpty()) return false
        call(device, "SetMute", listOf("Channel" to "Master", "DesiredMute" to if (on) "1" else "0"), RENDERING, device.renderingUrl, 2500)
        return true
    }

    /** "1:02:03" (or "1:02:03.500") as milliseconds; null for "NOT_IMPLEMENTED" and the like. */
    fun clock(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return h * 3_600_000 + m * 60_000 + (s * 1000).toLong()
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    fun stop(device: TvDevice) {
        call(device, "Stop")
    }

    fun pause(device: TvDevice) {
        call(device, "Pause")
    }

    fun resume(device: TvDevice) {
        call(device, "Play", listOf("Speed" to "1"))
    }
}

/** Starts and stops playback on either kind of TV. */
object Tv {
    fun play(device: TvDevice, videoUrl: String, title: String) = when (device.kind) {
        TvDevice.Kind.ROKU -> Roku.play(device, videoUrl, title)
        TvDevice.Kind.DLNA -> Dlna.play(device, videoUrl, title)
    }

    fun stop(device: TvDevice) = when (device.kind) {
        TvDevice.Kind.ROKU -> Roku.press(device, "Back")
        TvDevice.Kind.DLNA -> Dlna.stop(device)
    }

    fun pauseOrResume(device: TvDevice, pause: Boolean) = when (device.kind) {
        TvDevice.Kind.ROKU -> Roku.press(device, "Play") // Roku's play button toggles
        TvDevice.Kind.DLNA -> if (pause) Dlna.pause(device) else Dlna.resume(device)
    }
}
