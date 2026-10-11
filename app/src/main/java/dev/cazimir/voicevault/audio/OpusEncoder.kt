package dev.cazimir.voicevault.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.cazimir.voicevault.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encodes the app's PCM16 mono WAV checkpoint using Android's native Opus codec. */
object OpusEncoder {
    fun encode(wav: File, output: File) {
        wav.inputStream().use { input ->
            val header = ByteArray(Config.WAV_HEADER_SIZE_BYTES)
            check(input.read(header) == header.size) { "Truncated WAV header" }
            val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            check(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE" &&
                String(header, 12, 4, Charsets.US_ASCII) == "fmt " &&
                fields.getInt(16) == 16 && fields.getShort(20).toInt() == 1 &&
                fields.getShort(22).toInt() == 1 && fields.getShort(34).toInt() == 16 &&
                String(header, 36, 4, Charsets.US_ASCII) == "data") { "Unsupported WAV format" }
            val sampleRate = fields.getInt(24)
            val dataSize = fields.getInt(40)
            check(sampleRate > 0 && dataSize > 0 && dataSize % 2 == 0 &&
                wav.length() == header.size.toLong() + dataSize) { "Invalid WAV data" }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1)
                format.setInteger(MediaFormat.KEY_BIT_RATE, Config.OPUS_BIT_RATE)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
                try {
                    var track = -1
                    var bytesQueued = 0L
                    var inputEnded = false
                    var outputEnded = false
                    var lastProgress = System.nanoTime()
                    val info = MediaCodec.BufferInfo()
                    while (!outputEnded) {
                        if (!inputEnded) {
                            val index = codec.dequeueInputBuffer(10_000)
                            if (index >= 0) {
                                val buffer = checkNotNull(codec.getInputBuffer(index))
                                buffer.clear()
                                val remaining = dataSize.toLong() - bytesQueued
                                val size = minOf(buffer.remaining().toLong(), remaining).toInt() / 2 * 2
                                val pcm = ByteArray(size)
                                var read = 0
                                while (read < size) {
                                    val count = input.read(pcm, read, size - read)
                                    check(count > 0) { "Truncated WAV data" }
                                    read += count
                                }
                                buffer.put(pcm)
                                val timestampUs = bytesQueued / 2 * 1_000_000 / sampleRate
                                inputEnded = size == 0
                                codec.queueInputBuffer(index, 0, size, timestampUs,
                                    if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                                bytesQueued += size
                                lastProgress = System.nanoTime()
                            }
                        }
                        when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                check(track == -1) { "Encoder format changed twice" }
                                track = muxer.addTrack(codec.outputFormat)
                                muxer.start()
                                lastProgress = System.nanoTime()
                            }
                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            else -> if (index >= 0) {
                                try {
                                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                        check(track >= 0) { "Encoder produced audio before format" }
                                        val buffer = checkNotNull(codec.getOutputBuffer(index))
                                        buffer.position(info.offset)
                                        buffer.limit(info.offset + info.size)
                                        muxer.writeSampleData(track, buffer, info)
                                    }
                                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                } finally {
                                    codec.releaseOutputBuffer(index, false)
                                }
                                lastProgress = System.nanoTime()
                            }
                        }
                        check(System.nanoTime() - lastProgress < 30_000_000_000L) { "Opus encoder stalled" }
                    }
                    check(track >= 0) { "Encoder produced no audio track" }
                    muxer.stop()
                } finally {
                    muxer.release()
                }
            } finally {
                codec.release()
            }
        }
    }
}
