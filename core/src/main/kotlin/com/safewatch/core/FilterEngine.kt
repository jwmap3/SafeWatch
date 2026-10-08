package com.safewatch.core

/** What the player should be doing at one moment of playback. */
data class PlaybackState(val mute: Boolean, val blur: Boolean, val skipToMs: Long?) {
    companion object {
        val CLEAR = PlaybackState(mute = false, blur = false, skipToMs = null)
    }
}

/** Decides, for any playback position, whether to mute, blur or skip. */
class FilterEngine(tags: List<Tag>, private val settings: FilterSettings) {

    /** Tags the current settings filter, with the action that will be applied. */
    val activeTags: List<Tag> = tags
        .filter { it.endMs > it.startMs && settings.strictnessFor(it.category).filters(it.level) }
        .map { it.copy(action = effectiveAction(it)) }
        .sortedBy { it.startMs }

    // Nudity and gore that were found (not marked by hand) follow the current "blur or skip" setting;
    // scenes marked by hand keep the choice made then.
    private fun effectiveAction(tag: Tag): Action = when {
        tag.category == Category.GORE -> settings.nudityAction
        tag.category == Category.NUDITY && (tag.source == Tag.SOURCE_SCAN || tag.source == Tag.SOURCE_CLAUDE) -> settings.nudityAction
        else -> tag.action
    }

    fun stateAt(positionMs: Long): PlaybackState {
        var mute = false
        var blur = false
        var skipTo = -1L
        for (t in activeTags) {
            if (t.startMs > positionMs) break
            if (positionMs >= t.endMs) continue
            when (t.action) {
                Action.MUTE -> mute = true
                Action.BLUR -> blur = true
                Action.SKIP -> skipTo = maxOf(skipTo, t.endMs)
            }
        }
        if (skipTo < 0) return if (mute || blur) PlaybackState(mute, blur, null) else PlaybackState.CLEAR
        // Land past any skip range that starts before this one ends.
        var extended = true
        while (extended) {
            extended = false
            for (t in activeTags) {
                if (t.action == Action.SKIP && t.startMs <= skipTo && t.endMs > skipTo) {
                    skipTo = t.endMs
                    extended = true
                }
            }
        }
        return PlaybackState(mute, blur, skipTo)
    }
}
