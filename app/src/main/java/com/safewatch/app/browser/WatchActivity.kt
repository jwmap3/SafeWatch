package com.safewatch.app.browser

import android.content.Context
import android.content.Intent
import com.safewatch.app.data.Service

/**
 * The player. It is the built-in browser with everything that looks like a
 * browser taken away: a title instead of an address bar, and once the video
 * plays, a sideways full-screen picture with a few controls that fade out.
 *
 * The picture itself is still the streaming service's own web player, because
 * the video can only come from the service; this screen is the frame around it.
 */
class WatchActivity : BrowserActivity() {

    override val watchMode: Boolean get() = true

    companion object {
        /** The address that plays a YouTube video in the app's player. */
        fun youtube(videoId: String): String = "safewatch://youtube/$videoId"

        /** Plays a title, or opens a service to browse, without any browser controls. */
        fun open(ctx: Context, url: String, label: String) = ctx.startActivity(
            Intent(ctx, WatchActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_LABEL, label)
        )

        /** Starts a YouTube video here silently, reads its captions, then plays it in the TV's own YouTube app. */
        fun playOnTv(ctx: Context, videoId: String, label: String) = ctx.startActivity(
            Intent(ctx, WatchActivity::class.java).putExtra(EXTRA_URL, youtube(videoId)).putExtra(EXTRA_LABEL, label).putExtra(EXTRA_TO_TV, true)
        )

        /** Opens a service's sign-in page. The sign-in is remembered by the browser from then on. */
        fun signIn(ctx: Context, service: Service) = ctx.startActivity(
            Intent(ctx, WatchActivity::class.java)
                .putExtra(EXTRA_URL, service.signInUrl)
                .putExtra(EXTRA_LABEL, "Sign in to ${service.name}")
                .putExtra(EXTRA_SIGN_IN, service.id)
        )
    }
}
