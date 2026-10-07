package com.safewatch.core

/** One detected region, in the pixel coordinates of the model's input image. */
data class Detection(val classId: Int, val score: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/** Reads the raw output of a YOLOv8-style detector (the format NudeNet ships in). */
object Yolo {
    /**
     * @param output the flattened `[1, 4 + numClasses, numAnchors]` tensor: for each
     * anchor, centre x, centre y, width, height, then one score per class.
     */
    fun decode(
        output: FloatArray,
        numClasses: Int,
        numAnchors: Int,
        scoreThreshold: Float,
        iouThreshold: Float = 0.45f,
    ): List<Detection> {
        require(output.size >= (4 + numClasses) * numAnchors) { "output is smaller than its declared shape" }
        val found = ArrayList<Detection>()
        for (i in 0 until numAnchors) {
            var best = -1
            var bestScore = scoreThreshold
            for (c in 0 until numClasses) {
                val s = output[(4 + c) * numAnchors + i]
                if (s >= bestScore) {
                    bestScore = s
                    best = c
                }
            }
            if (best < 0) continue
            val cx = output[i]
            val cy = output[numAnchors + i]
            val w = output[2 * numAnchors + i]
            val h = output[3 * numAnchors + i]
            found += Detection(best, bestScore, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        }
        // Keep the strongest box of each overlapping group.
        val kept = ArrayList<Detection>()
        for (d in found.sortedByDescending { it.score }) {
            if (kept.none { it.classId == d.classId && iou(it, d) > iouThreshold }) kept += d
        }
        return kept
    }

    fun iou(a: Detection, b: Detection): Float {
        val w = minOf(a.x2, b.x2) - maxOf(a.x1, b.x1)
        val h = minOf(a.y2, b.y2) - maxOf(a.y1, b.y1)
        if (w <= 0 || h <= 0) return 0f
        val inter = w * h
        val union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (union <= 0) 0f else inter / union
    }
}

/**
 * The 18 classes of the NudeNet v3 detector, in model output order, and the
 * filter level each maps to. The order must match the model file in use.
 */
object NudeLabels {
    val names = listOf(
        "FEMALE_GENITALIA_COVERED", "FACE_FEMALE", "BUTTOCKS_EXPOSED", "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED", "MALE_BREAST_EXPOSED", "ANUS_EXPOSED", "FEET_EXPOSED",
        "BELLY_COVERED", "FEET_COVERED", "ARMPITS_COVERED", "ARMPITS_EXPOSED", "FACE_MALE",
        "BELLY_EXPOSED", "MALE_GENITALIA_EXPOSED", "ANUS_COVERED", "FEMALE_BREAST_COVERED",
        "BUTTOCKS_COVERED",
    )

    private val levels = mapOf(
        // Low strictness and up: explicit nudity.
        "FEMALE_GENITALIA_EXPOSED" to 3, "MALE_GENITALIA_EXPOSED" to 3, "ANUS_EXPOSED" to 3,
        // Medium and up: exposed breasts and buttocks.
        "FEMALE_BREAST_EXPOSED" to 2, "BUTTOCKS_EXPOSED" to 2,
        // High only: swimwear, underwear and shirtless scenes.
        "FEMALE_BREAST_COVERED" to 1, "FEMALE_GENITALIA_COVERED" to 1, "BUTTOCKS_COVERED" to 1,
        "MALE_BREAST_EXPOSED" to 1, "BELLY_EXPOSED" to 1,
    )

    /** 0 means the class is never filtered (faces, feet and so on). */
    fun levelFor(classId: Int): Int = names.getOrNull(classId)?.let { levels[it] } ?: 0

    fun maxLevel(detections: List<Detection>): Int = detections.maxOfOrNull { levelFor(it.classId) } ?: 0
}
