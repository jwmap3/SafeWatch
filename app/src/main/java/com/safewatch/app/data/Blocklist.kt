package com.safewatch.app.data

import android.content.Context
import com.safewatch.core.SiteBlock
import java.util.zip.GZIPInputStream

/** The websites the browser will not open: adult sites, and Twitter (X), Reddit and Instagram, as chosen in Settings. */
object Blocklist {
    @Volatile private var adult: Set<String>? = null

    /** Why [host] is blocked ("adult" or "social"), or null. The list of adult sites is read the first time it is needed. */
    fun reason(ctx: Context, host: String?): String? {
        val blockAdult = Prefs.blockAdult(ctx)
        val blockSocial = Prefs.blockSocial(ctx)
        if (!blockAdult && !blockSocial) return null
        return SiteBlock(if (blockAdult) adultSites(ctx) else emptySet(), blockAdult, blockSocial).reason(host)
    }

    private fun adultSites(ctx: Context): Set<String> = adult ?: synchronized(this) {
        adult ?: try {
            GZIPInputStream(ctx.assets.open("blocklist-adult.txt.gz")).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.toHashSet()
            }
        } catch (e: Exception) {
            emptySet()
        }.also { adult = it }
    }

    /** The page shown instead of a blocked site. */
    fun page(host: String, reason: String): String {
        val what = if (reason == "social") "Social media sites are blocked in edenOS." else "This site is blocked in edenOS."
        return "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><title>Blocked</title>" +
            "<style>body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#0c0f0e;color:#e9f5ee;" +
            "font-family:sans-serif;text-align:center}div{padding:32px;max-width:420px}h1{font-weight:500;font-size:22px;margin:0 0 10px}" +
            "p{color:#9fb5a8;font-size:15px;line-height:1.5;margin:0}</style></head><body><div><h1>" + what + "</h1><p>" +
            host.replace("<", "&lt;") + " cannot be opened here. This can be changed in Settings, which may be locked with a PIN.</p></div></body></html>"
    }
}
