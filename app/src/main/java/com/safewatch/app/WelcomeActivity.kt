package com.safewatch.app

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.safewatch.app.browser.WatchActivity
import com.safewatch.app.data.Accounts
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Services
import com.safewatch.app.ui.Ui

/**
 * Shown the first time the app is opened: choose your streaming services and
 * sign in to them, with a plain statement of where those sign-ins are kept.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (page, col) = Ui.page(this, ownWindow = true)
        column = col
        setContentView(page)
    }

    override fun onResume() {
        super.onResume()
        build() // rebuilt on return from a sign-in, so it shows as done
    }

    private fun build() {
        column.removeAllViews()
        column.addView(Ui.largeTitle(this, "Welcome to SafeWatch").apply { setPadding(0, Ui.dp(context, 36), 0, Ui.dp(context, 6)) })
        column.addView(Ui.subtitle(this, "Watch your own streaming services with cursing muted and nudity hidden."))

        column.addView(Ui.sectionHeader(this, "Your sign-ins stay on this phone"))
        column.addView(Ui.card(this).apply {
            addView(Ui.caption(context,
                "SafeWatch has no account and no server of its own. When you sign in below, you are on the service's " +
                    "own page, and the sign-in is kept on this phone the way a browser keeps it. Your passwords, and " +
                    "what you watch, go to no one but the service itself.").apply {
                textSize = 14f
                setTextColor(Ui.color(context, R.color.text))
                setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 14))
            })
        })

        column.addView(Ui.sectionHeader(this, "Choose and sign in to your services"))
        val chosen = Prefs.connectedServices(this)
        column.addView(Ui.card(this).apply {
            Services.choices.forEachIndexed { i, service ->
                if (i > 0) addView(Ui.divider(context))
                val on = service.id in chosen
                addView(Ui.switchRow(context, service.name, on) { use ->
                    Prefs.setConnectedServices(context, if (use) Prefs.connectedServices(context) + service.id else Prefs.connectedServices(context) - service.id)
                    build()
                })
                if (on) {
                    val signedIn = Accounts.isSignedIn(context, service)
                    addView(Ui.inset(context, Ui.actionButton(context, if (signedIn) "Signed in to ${service.name}" else "Sign in to ${service.name}", filled = !signedIn) {
                        WatchActivity.signIn(context, service)
                    }, top = 0, bottom = 14))
                }
            }
        })
        column.addView(Ui.caption(this, "You can skip signing in now and do it later under Settings. YouTube needs no sign-in here."))

        column.addView(Ui.inset(this, Ui.actionButton(this, "Start watching") {
            Prefs.setWelcomed(this)
            finish()
        }, top = 28, bottom = 8).apply { setPadding(0, paddingTop, 0, paddingBottom) })
    }
}
