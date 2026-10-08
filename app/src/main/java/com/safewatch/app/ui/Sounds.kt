package com.safewatch.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.safewatch.app.R
import com.safewatch.app.data.Prefs

/**
 * Short sounds for taps, opening a title and starting playback. There are a
 * few sets to choose from in Settings; all of them were made for this app.
 * The "Starship" set stays hidden until the name on the home screen is
 * tapped seven times.
 */
object Sounds {
    const val TAP = 0
    const val OPEN = 1
    const val PLAY = 2

    class Pack(val id: String, val name: String, val files: IntArray, val hidden: Boolean = false)

    val packs = listOf(
        Pack("off", "Off", intArrayOf()),
        Pack("soft", "Soft", intArrayOf(R.raw.soft_tap, R.raw.soft_open, R.raw.soft_play)),
        Pack("glass", "Glass", intArrayOf(R.raw.glass_tap, R.raw.glass_open, R.raw.glass_play)),
        Pack("arcade", "Arcade", intArrayOf(R.raw.arcade_tap, R.raw.arcade_open, R.raw.arcade_play)),
        Pack("starship", "Starship", intArrayOf(R.raw.starship_tap, R.raw.starship_open, R.raw.starship_play), hidden = true),
    )

    fun available(ctx: Context): List<Pack> = packs.filter { !it.hidden || Prefs.starshipUnlocked(ctx) }

    private var pool: SoundPool? = null
    private var loadedPack = ""
    private var ids = IntArray(0)

    @Synchronized
    fun play(ctx: Context, which: Int) {
        val pack = packs.firstOrNull { it.id == Prefs.soundPack(ctx) } ?: return
        if (pack.files.isEmpty()) return
        if (loadedPack != pack.id) {
            pool?.release()
            val fresh = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(AudioAttributes.Builder()
                    // Played with the media volume, which is up while watching, not the system volume, which is often off.
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .build()
            ids = IntArray(pack.files.size) { fresh.load(ctx.applicationContext, pack.files[it], 1) }
            pool = fresh
            loadedPack = pack.id
            // A sound cannot play until it has loaded, so the one asked for plays as soon as it is ready.
            val wanted = ids.getOrNull(which)
            val volume = Prefs.soundVolume(ctx) / 100f
            fresh.setOnLoadCompleteListener { p, id, status -> if (status == 0 && id == wanted) p.play(id, volume, volume, 1, 0, 1f) }
            return
        }
        val volume = Prefs.soundVolume(ctx) / 100f
        if (volume > 0f) ids.getOrNull(which)?.let { pool?.play(it, volume, volume, 1, 0, 1f) }
    }
}
