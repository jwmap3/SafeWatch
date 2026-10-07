package com.safewatch.app.detect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import com.safewatch.core.NudeLabels
import com.safewatch.core.Yolo
import java.io.File
import java.nio.FloatBuffer

/**
 * Looks at one video frame and says how much nudity is in it, as a filter
 * level from 0 (none) to 3 (explicit). Runs the NudeNet detector on the phone;
 * frames never leave the device.
 *
 * The model file is not part of the app. It is imported once from the Filters
 * screen and kept in the app's private storage.
 */
class NudityDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {

    private val inputName = session.inputNames.first()
    private val square = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(square)
    private val pixels = IntArray(SIZE * SIZE)
    private val input = FloatBuffer.allocate(3 * SIZE * SIZE)

    @Synchronized
    fun maxLevel(frame: Bitmap, facesCount: Boolean = false, scoreThreshold: Float = 0.35f): Int {
        // The model wants a square picture: fit the frame in the top-left corner, black elsewhere.
        val scale = SIZE.toFloat() / maxOf(frame.width, frame.height)
        val w = (frame.width * scale).toInt().coerceAtLeast(1)
        val h = (frame.height * scale).toInt().coerceAtLeast(1)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(frame, null, Rect(0, 0, w, h), null)
        square.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)

        val plane = SIZE * SIZE
        for (i in 0 until plane) {
            val p = pixels[i]
            input.put(i, ((p shr 16) and 0xFF) / 255f)
            input.put(plane + i, ((p shr 8) and 0xFF) / 255f)
            input.put(2 * plane + i, (p and 0xFF) / 255f)
        }
        input.rewind()

        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val shape = out.info.shape // [1, 4 + classes, anchors]
                val buffer = out.floatBuffer
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                val detections = Yolo.decode(values, shape[1].toInt() - 4, shape[2].toInt(), scoreThreshold)
                return NudeLabels.maxLevel(detections, facesCount)
            }
        }
    }

    @Synchronized
    override fun close() {
        session.close()
    }

    companion object {
        private const val SIZE = 320

        fun modelFile(ctx: Context): File = File(File(ctx.filesDir, "models").apply { mkdirs() }, "nudenet.onnx")

        fun isInstalled(ctx: Context): Boolean = modelFile(ctx).length() > 0

        /** Returns null when no model has been imported or it cannot be read. */
        fun open(ctx: Context): NudityDetector? {
            if (!isInstalled(ctx)) return null
            return try {
                val env = OrtEnvironment.getEnvironment()
                NudityDetector(env, env.createSession(modelFile(ctx).absolutePath, OrtSession.SessionOptions()))
            } catch (e: Exception) {
                null
            }
        }

        /** Copies a model file the viewer picked into the app. Returns whether it loads. */
        fun install(ctx: Context, source: Uri): Boolean =
            install(ctx) { ctx.contentResolver.openInputStream(source)!! }

        /** Puts a downloaded model file in place. Returns whether it loads. */
        fun install(ctx: Context, source: File): Boolean = install(ctx) { source.inputStream() }

        private fun install(ctx: Context, open: () -> java.io.InputStream): Boolean {
            val target = modelFile(ctx)
            return try {
                open().use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                val detector = open(ctx)
                detector?.close()
                if (detector == null) target.delete()
                detector != null
            } catch (e: Exception) {
                target.delete()
                false
            }
        }
    }
}
