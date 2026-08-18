package blbl.cat3399.feature.player.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        val inputRemaining = inputBuffer.remaining()
        if (inputRemaining == 0) {
            return
        }

        val out = replaceOutputBuffer(inputRemaining)
        out.order(ByteOrder.nativeOrder())

        if (delayMs <= 0 || delayByteCount <= 0) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val rb = ringBuffer ?: run {
            out.put(inputBuffer)
            out.flip()
            return
        }

        // Byte-by-byte delay line: newest sample goes into the ring, oldest sample (if available)
        // comes out. Until the ring is fully primed we emit silence.
        for (i in 0 until inputRemaining) {
            val inputByte = inputBuffer.get()
            if (ringUsed >= delayByteCount) {
                out.put(rb[ringReadPos])
                ringReadPos = (ringReadPos + 1) % delayByteCount
                ringUsed--
            } else {
                out.put(0)
            }
            rb[ringWritePos] = inputByte
            ringWritePos = (ringWritePos + 1) % delayByteCount
            if (ringUsed < delayByteCount) {
                ringUsed++
            }
        }
        out.flip()
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
