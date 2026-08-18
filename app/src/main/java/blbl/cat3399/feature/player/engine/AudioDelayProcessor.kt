package blbl.cat3399.feature.player.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.min

@UnstableApi
internal class AudioDelayProcessor(initialDelayMs: Int = 0) : BaseAudioProcessor() {
    @Volatile
    private var delayMs: Int = initialDelayMs.coerceIn(0, MAX_DELAY_MS)

    private var sampleRateHz: Int = 0
    private var channelCount: Int = 0
    private var bytesPerSample: Int = 0
    private var frameSize: Int = 0
    private var delayByteCount: Int = 0

    private var ringBuffer: ByteArray? = null
    private var ringReadPos: Int = 0
    private var ringWritePos: Int = 0
    private var ringUsed: Int = 0

    fun setDelayMs(ms: Int) {
        val newMs = ms.coerceIn(0, MAX_DELAY_MS)
        if (newMs == delayMs) return
        delayMs = newMs
        resetRingBuffer()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        bytesPerSample = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        frameSize = channelCount * bytesPerSample
        resetRingBuffer()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (delayMs <= 0 || !inputBuffer.hasRemaining()) {
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            return
        }

        val rb = ringBuffer
        if (rb == null || delayByteCount <= 0) {
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            return
        }

        val inputRemaining = inputBuffer.remaining()
        val canOutput = min(inputRemaining, ringUsed)
        val out = replaceOutputBuffer(canOutput)

        // Read delayed samples from ring buffer.
        for (i in 0 until canOutput) {
            out.put(rb[ringReadPos])
            ringReadPos = (ringReadPos + 1) % delayByteCount
            ringUsed--
        }
        out.flip()

        // Write new samples into ring buffer.
        for (i in 0 until inputRemaining) {
            rb[ringWritePos] = inputBuffer.get()
            ringWritePos = (ringWritePos + 1) % delayByteCount
            if (ringUsed < delayByteCount) ringUsed++
        }
    }

    override fun onFlush() {
        ringReadPos = 0
        ringWritePos = 0
        ringUsed = 0
    }

    override fun onReset() {
        onFlush()
    }

    private fun resetRingBuffer() {
        if (sampleRateHz <= 0 || channelCount <= 0 || frameSize <= 0) return
        val delayFrames = (sampleRateHz * delayMs / 1000.0).toInt()
        delayByteCount = delayFrames * frameSize
        ringBuffer = if (delayByteCount > 0) ByteArray(delayByteCount) else null
        ringReadPos = 0
        ringWritePos = 0
        ringUsed = 0
    }

    companion object {
        const val MAX_DELAY_MS = 500
    }
}
