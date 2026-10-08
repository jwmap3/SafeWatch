package com.safewatch.app.tv

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.C
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.safewatch.core.CleanPlan
import java.nio.ByteBuffer

/**
 * Silences the sound wherever the plan says, while a clean copy is being made. Each muted
 * stretch fades out and in over a few milliseconds, so the cut makes no click.
 *
 * [startMs] is where in the original video this piece begins (pieces follow cut scenes). The
 * position is counted from the sound itself, so it is exact whatever the video's own timestamps say.
 */
@UnstableApi
class SilenceProcessor(private val plan: CleanPlan, private val startMs: Long) : BaseAudioProcessor() {
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val channels = format.channelCount
        val rate = format.sampleRate.toLong()
        val out = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.remaining() >= 2 * channels) {
            val ms = startMs + frames * 1000 / rate
            val gain = when {
                plan.mutedAt(ms) -> 0f
                plan.mutedAt(ms + FADE_MS) || plan.mutedAt(ms - FADE_MS) -> 0.35f
                else -> 1f
            }
            for (c in 0 until channels) {
                val sample = inputBuffer.short
                out.putShort(if (gain == 1f) sample else (sample * gain).toInt().toShort())
            }
            frames++
        }
        // An incomplete frame cannot be judged; pass it on as it is.
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    override fun onFlush() {
        frames = 0
    }

    private companion object {
        const val FADE_MS = 12L
    }
}

/**
 * Blurs the picture wherever the plan says, while a clean copy is being made: the frame is
 * broken into a coarse grid of softened blocks, so shapes and colours move but nothing can be
 * made out. Frames outside the plan's stretches are copied untouched.
 *
 * Like [SilenceProcessor], [startMs] is where this piece begins in the original video; the
 * first frame this piece receives is taken to be at that moment.
 */
@UnstableApi
class StretchBlur(private val plan: CleanPlan, private val startMs: Long) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(plan, startMs, useHdr)

    private class Program(private val plan: CleanPlan, private val startMs: Long, useHdr: Boolean) :
        BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

        private val program = try {
            GlProgram(VERTEX, FRAGMENT).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        private var width = 1
        private var height = 1
        private var firstUs = -1L

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            if (firstUs < 0) firstUs = presentationTimeUs
            val ms = startMs + (presentationTimeUs - firstUs) / 1000
            // About 28 blocks across, whatever the picture's size; square blocks.
            val across = if (plan.blurredAt(ms)) BLOCKS_ACROSS else 0f
            val down = if (across == 0f) 0f else maxOf(1f, across * height / maxOf(1, width))
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                program.setFloatsUniform("uGrid", floatArrayOf(across, down))
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try { program.delete() } catch (e: GlUtil.GlException) { /* already gone */ }
        }
    }

    private companion object {
        const val BLOCKS_ACROSS = 28f

        const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """

        const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uGrid;
            varying vec2 vTexSamplingCoord;
            void main() {
              if (uGrid.x < 1.0) {
                gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
                return;
              }
              vec2 cell = (floor(vTexSamplingCoord * uGrid) + 0.5) / uGrid;
              vec2 offs = 0.5 / uGrid;
              vec4 sum = texture2D(uTexSampler, cell) * 0.2;
              sum += texture2D(uTexSampler, cell + vec2(offs.x, offs.y)) * 0.2;
              sum += texture2D(uTexSampler, cell + vec2(-offs.x, offs.y)) * 0.2;
              sum += texture2D(uTexSampler, cell + vec2(offs.x, -offs.y)) * 0.2;
              sum += texture2D(uTexSampler, cell + vec2(-offs.x, -offs.y)) * 0.2;
              gl_FragColor = vec4(sum.rgb, 1.0);
            }
        """
    }
}
