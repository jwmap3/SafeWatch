package com.safewatch.app.tv

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.safewatch.app.R
import com.safewatch.app.ui.Ui
import com.safewatch.core.tv.LoungeClient
import com.safewatch.core.tv.LoungeScreen
import org.json.JSONArray

/**
 * YouTube on the TV, with no download: EdenOS starts the video in the TV's own YouTube app (the way the
 * YouTube phone app does when you cast) and works it like a remote, muting the sound for each curse word
 * it found in the captions and jumping past marked scenes. The phone can be locked; it has to stay on
 * the internet to send the mutes.
 */
object YouTubeTv {
    /** A YouTube video to play on the TV, with what to mute and skip, in milliseconds. */
    data class Video(val id: String, val title: String, val atMs: Long, val mute: List<LongRange>, val skips: List<LongRange>)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)

    /** The TV whose YouTube app was linked, if any. */
    fun linked(ctx: Context): LoungeScreen? {
        val p = prefs(ctx)
        val id = p.getString("ytScreen", null) ?: return null
        return LoungeScreen(id, p.getString("ytToken", "").orEmpty(), p.getString("ytName", "TV").orEmpty())
    }

    fun save(ctx: Context, screen: LoungeScreen) = prefs(ctx).edit()
        .putString("ytScreen", screen.screenId).putString("ytToken", screen.token).putString("ytName", screen.name).apply()

    fun unlink(ctx: Context) = prefs(ctx).edit().remove("ytScreen").remove("ytToken").remove("ytName").apply()

    /** Asks for the code the TV's YouTube app shows, and links the TV with it. */
    fun link(activity: Activity, then: ((LoungeScreen) -> Unit)? = null) {
        val pad = Ui.dp(activity, 22)
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "123 456 789 012"
            textSize = 22f
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(activity, 6), pad, 0)
            addView(TextView(activity).apply {
                text = "On the TV, open YouTube, then Settings > Link with TV code. Type the code the TV shows."
                textSize = 15f
                setTextColor(Ui.color(activity, R.color.text_secondary))
                setPadding(0, 0, 0, Ui.dp(activity, 8))
            })
            addView(field)
        }
        AlertDialog.Builder(activity)
            .setTitle("Link your TV's YouTube")
            .setView(box)
            .setPositiveButton("Link") { _, _ -> pair(activity, field.text.toString(), then) }
            .setNegativeButton("Cancel", null)
            .show()
        field.requestFocus()
    }

    private fun pair(activity: Activity, code: String, then: ((LoungeScreen) -> Unit)?) {
        val wait = AlertDialog.Builder(activity).setMessage("Linking…").setCancelable(false).show()
        Thread {
            val result = runCatching { LoungeClient().pair(code) }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                wait.dismiss()
                result.onSuccess {
                    save(activity, it)
                    Ui.toast(activity, "Linked to ${it.name}")
                    then?.invoke(it)
                }.onFailure {
                    AlertDialog.Builder(activity)
                        .setTitle("Not linked")
                        .setMessage(it.message ?: "YouTube could not be reached.")
                        .setPositiveButton("Try again") { _, _ -> link(activity, then) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }.start()
    }

    /** Plays the video on the linked TV, linking one first if there is none. */
    fun send(activity: Activity, video: Video) {
        val screen = linked(activity) ?: return link(activity) { send(activity, video) }
        TvService.youtube(activity, video, screen)
        Ui.toast(activity, "Starting on ${screen.name}. Your phone can be locked.")
    }

    fun rangesToJson(ranges: List<LongRange>): String =
        JSONArray().apply { ranges.forEach { put(JSONArray().put(it.first).put(it.last)) } }.toString()

    fun rangesFromJson(text: String?): List<LongRange> = try {
        val list = JSONArray(text ?: "[]")
        (0 until list.length()).map { val r = list.getJSONArray(it); r.getLong(0)..r.getLong(1) }
    } catch (e: Exception) {
        emptyList()
    }
}
