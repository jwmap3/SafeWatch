package com.safewatch.app.detect

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Fetches the nudity detection model the first time the app runs, so
 * detection works without any setup.
 *
 * The model is NudeNet's `320n.onnx`. It is taken from NudeNet's own
 * published package on the Python Package Index: the package is a zip file
 * with the model inside. About 11 MB is downloaded once.
 */
object ModelSetup {
    private const val PACKAGE_INFO = "https://pypi.org/pypi/nudenet/json"

    enum class State { MISSING, DOWNLOADING, READY, FAILED }

    @Volatile var state = State.MISSING
        private set

    fun stateFor(ctx: Context): State {
        if (NudityDetector.isInstalled(ctx)) return State.READY
        return state
    }

    /** Starts the download in the background unless the model is here or already on its way. */
    @Synchronized
    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        if (NudityDetector.isInstalled(app) || state == State.DOWNLOADING) return
        state = State.DOWNLOADING
        Thread {
            state = try {
                if (download(app)) State.READY else State.FAILED
            } catch (e: Exception) {
                State.FAILED
            }
        }.start()
    }

    private fun download(ctx: Context): Boolean {
        val info = JSONObject(open(PACKAGE_INFO).inputStream.bufferedReader().use { it.readText() })
        val files = info.getJSONArray("urls")
        var packageUrl: String? = null
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            if (f.optString("packagetype") == "bdist_wheel") packageUrl = f.getString("url")
        }
        val url = packageUrl ?: return false
        if (!url.startsWith("https://files.pythonhosted.org/")) return false

        val partial = File(ctx.cacheDir, "model.download")
        try {
            ZipInputStream(open(url).inputStream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: return false
                    if (entry.name.endsWith("320n.onnx")) {
                        partial.outputStream().use { zip.copyTo(it) }
                        break
                    }
                }
            }
            return NudityDetector.install(ctx, partial)
        } finally {
            partial.delete()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
}
