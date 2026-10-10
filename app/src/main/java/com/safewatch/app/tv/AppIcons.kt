package com.safewatch.app.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The icons each service publishes for its own website (the picture a phone shows when the site is saved
 * to its home screen), fetched from the service the way a browser does, and kept on the phone so the TV
 * home appears at once next time. Nothing is drawn or copied into the app itself.
 */
object AppIcons {
    class Icon(val bitmap: Bitmap, /** The colour at its edges, to fill the tile around it. */ val edge: Int, /** Its liveliest colour, for the glow behind the row. */ val glow: Int, val fullBleed: Boolean)

    private val memory = LruCache<String, Icon>(24)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** Loads the icon of the site at [host] and hands it to [then] on the main thread; nothing is called if there is none. */
    fun load(context: Context, host: String, then: (Icon) -> Unit) {
        memory.get(host)?.let { then(it); return }
        val app = context.applicationContext
        pool.execute {
            val icon = try { fetch(app, host) } catch (e: Throwable) { null } ?: return@execute
            memory.put(host, icon)
            main.post { then(icon) }
        }
    }

    private fun fetch(context: Context, host: String): Icon? {
        val folder = File(context.cacheDir, "app-icons").apply { mkdirs() }
        val saved = File(folder, host.replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".png")
        val month = 30L * 24 * 60 * 60_000
        if (saved.exists() && System.currentTimeMillis() - saved.lastModified() < month) {
            BitmapFactory.decodeFile(saved.absolutePath)?.let { return describe(it) }
        }
        // The home-screen icon the site offers phones first (a full square picture), then the largest site icon.
        val tries = listOf("https://$host/apple-touch-icon.png", "https://$host/apple-touch-icon-precomposed.png",
            "https://www.google.com/s2/favicons?domain=$host&sz=256")
        for (address in tries) {
            val bytes = download(address) ?: continue
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            if (bitmap.width < 64) { bitmap.recycle(); continue }
            saved.writeBytes(bytes)
            return describe(bitmap)
        }
        // Even a small one is better than none.
        return download(tries.last())?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { describe(it) }
    }

    private fun download(address: String): ByteArray? {
        var url = address
        repeat(4) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 8000
                c.readTimeout = 10000
                c.setRequestProperty("User-Agent", CleanCopy.AGENT)
                val code = c.responseCode
                if (code in 300..399) {
                    url = URL(URL(url), c.getHeaderField("Location") ?: return null).toString()
                    return@repeat
                }
                if (code != 200) return null
                val type = c.contentType.orEmpty()
                if (type.isNotEmpty() && !type.startsWith("image")) return null
                return c.inputStream.use { it.readBytes() }.takeIf { it.size in 100..2_000_000 }
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    private fun describe(b: Bitmap): Icon {
        val w = b.width
        val h = b.height
        // The edges: if they are solid, the picture is a full square tile and fills the TV tile edge to edge.
        val edgePoints = listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1, w / 2 to 0, w / 2 to h - 1, 0 to h / 2, w - 1 to h / 2)
            .map { (x, y) -> b.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)) }
        val solid = edgePoints.count { Color.alpha(it) > 230 } >= 7
        val edge = if (solid) average(edgePoints) else Color.parseColor("#1C2521")
        var glow = edge
        var best = -1f
        val hsv = FloatArray(3)
        for (y in 1..9) for (x in 1..9) {
            val c = b.getPixel(x * (w - 1) / 10, y * (h - 1) / 10)
            if (Color.alpha(c) < 200) continue
            Color.colorToHSV(c, hsv)
            val score = hsv[1] * hsv[2]
            if (score > best) { best = score; glow = c }
        }
        if (best < 0.15f) glow = Color.parseColor("#3B6B52") // a grey or black icon: edenOS's own green
        return Icon(b, edge, glow, solid)
    }

    private fun average(colors: List<Int>): Int {
        val n = colors.size.coerceAtLeast(1)
        return Color.rgb(colors.sumOf { Color.red(it) } / n, colors.sumOf { Color.green(it) } / n, colors.sumOf { Color.blue(it) } / n)
    }
}
