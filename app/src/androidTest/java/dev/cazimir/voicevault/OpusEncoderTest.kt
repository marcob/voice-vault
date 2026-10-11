package dev.cazimir.voicevault

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cazimir.voicevault.audio.OpusEncoder
import dev.cazimir.voicevault.vault.WavEncoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class OpusEncoderTest {
    @Test
    fun nativeEncoderProducesDecodableMonoOggOpus() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val wav = File.createTempFile("opus-test", ".wav", cache)
        val ogg = File.createTempFile("opus-test", ".ogg", cache)
        val rate = Config.SAMPLE_RATE_HZ
        val pcm = ByteBuffer.allocate(rate * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(rate * 2) { frame ->
            pcm.putShort((sin(2 * PI * 440 * frame / rate) * 8_000).toInt().toShort())
        }
        try {
            wav.writeBytes(WavEncoder.encode(pcm.array(), rate))
            OpusEncoder.encode(wav, ogg)
            assertTrue(ogg.length() in 1 until wav.length())
            val magic = ByteArray(4)
            ogg.inputStream().use { assertEquals(4, it.read(magic)) }
            assertEquals("OggS", String(magic, Charsets.US_ASCII))

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(ogg.absolutePath)
                val format = extractor.getTrackFormat(0)
                assertEquals(MediaFormat.MIMETYPE_AUDIO_OPUS, format.getString(MediaFormat.KEY_MIME))
                assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                extractor.selectTrack(0)
                val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                try {
                    decoder.configure(format, null, null, 0)
                    decoder.start()
                    var inputDone = false
                    var outputDone = false
                    var decodedBytes = 0L
                    var decodedRate = 48_000
                    val info = MediaCodec.BufferInfo()
                    val deadline = System.nanoTime() + 30_000_000_000L
                    while (!outputDone && System.nanoTime() < deadline) {
                        if (!inputDone) {
                            val index = decoder.dequeueInputBuffer(10_000)
                            if (index >= 0) {
                                val buffer = checkNotNull(decoder.getInputBuffer(index))
                                buffer.clear()
                                val size = extractor.readSampleData(buffer, 0)
                                inputDone = size < 0
                                decoder.queueInputBuffer(index, 0, size.coerceAtLeast(0),
                                    if (inputDone) 0 else extractor.sampleTime,
                                    if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                                if (!inputDone) extractor.advance()
                            }
                        }
                        when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                                decodedRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            else -> if (index >= 0) {
                                decodedBytes += info.size
                                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                decoder.releaseOutputBuffer(index, false)
                            }
                        }
                    }
                    assertTrue("decoder did not reach end of stream", outputDone)
                    assertEquals(2.0, decodedBytes.toDouble() / 2 / decodedRate, 0.15)
                } finally {
                    decoder.release()
                }
            } finally {
                extractor.release()
            }
        } finally {
            wav.delete()
            ogg.delete()
        }
    }
}
