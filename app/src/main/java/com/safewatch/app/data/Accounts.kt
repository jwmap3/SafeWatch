package com.safewatch.app.data

import android.content.Context
import android.webkit.CookieManager

/**
 * Whether the viewer is signed in to a service in the built-in browser.
 *
 * Sign-ins are kept by the browser itself, the same way Chrome keeps them:
 * the service's website stores a sign-in cookie, and it stays until the viewer
 * signs out there. edenOS never sees or stores a password. This only works
 * out, for display, whether a sign-in is in place.
 */
object Accounts {
    fun isSignedIn(ctx: Context, service: Service): Boolean {
        // Surest sign: the service's own sign-in cookie is present.
        if (service.sessionCookies.isNotEmpty()) {
            val names = cookieNames(service.homeUrl) + cookieNames(service.signInUrl)
            if (service.sessionCookies.any { it in names }) return true
        }
        // Otherwise go by whether a sign-in started from Settings was seen through.
        return Prefs.signInSeen(ctx, service.id)
    }

    private fun cookieNames(url: String): Set<String> = try {
        (CookieManager.getInstance().getCookie(url) ?: "").split(';').map { it.substringBefore('=').trim() }.toSet()
    } catch (e: Exception) {
        emptySet() // the browser engine is not available yet
    }

    /** True for addresses that are part of signing in, as opposed to the service itself. */
    fun looksLikeSignIn(url: String): Boolean {
        val u = url.lowercase()
        return listOf("login", "signin", "sign-in", "sign_in", "/auth", "auth.", "accounts.google", "/ap/").any { it in u }
    }
}
