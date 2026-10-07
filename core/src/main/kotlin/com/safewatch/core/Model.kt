package com.safewatch.core

/** What a filter is about. Gore and others can be added here later. */
enum class Category { LANGUAGE, NUDITY }

/** What the player does while a tag is active. */
enum class Action { MUTE, SKIP, BLUR }

/**
 * How much gets filtered. Every tag and every word carries a level from
 * 1 (mild) to 3 (strong); a strictness filters everything at or above its
 * [minLevel].
 */
enum class Strictness(val minLevel: Int) {
    OFF(Int.MAX_VALUE), LOW(3), MEDIUM(2), HIGH(1);

    fun filters(level: Int): Boolean = level >= minLevel
}

/** A stretch of a video that should be muted, skipped or blurred. */
data class Tag(
    val startMs: Long,
    val endMs: Long,
    val category: Category,
    val action: Action,
    val level: Int = 3,
    val source: String = SOURCE_MANUAL,
) {
    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_CAPTIONS = "captions"
        const val SOURCE_SCAN = "scan"
    }
}

data class FilterSettings(
    val language: Strictness = Strictness.MEDIUM,
    val blasphemy: Boolean = true,
    val nudity: Strictness = Strictness.MEDIUM,
    /** What to do with detected nudity: [Action.BLUR] or [Action.SKIP]. */
    val nudityAction: Action = Action.BLUR,
    val customWords: Set<String> = emptySet(),
    val allowedWords: Set<String> = emptySet(),
) {
    fun strictnessFor(category: Category): Strictness = when (category) {
        Category.LANGUAGE -> language
        Category.NUDITY -> nudity
    }
}
