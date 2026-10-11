package dev.cazimir.voicevault.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioExportQueueTest {
    @get:Rule val temp = TemporaryFolder()

    private fun pending(): File = temp.newFile("capture.wav").apply { writeText("original audio") }

    @Test
    fun `publish succeeds before pending audio and diagnostic are removed`() = runBlocking {
        val wav = pending()
        var diagnosticRemoved = false
        AudioExportQueue.export(wav,
            encode = { original, output ->
                assertTrue(original.exists())
                output.writeText("encoded opus")
            },
            outputExists = { false },
            publish = { id, output ->
                assertEquals("capture", id)
                assertTrue(wav.exists())
                assertEquals("encoded opus", output.readText())
            },
            deleteDiagnostic = { diagnosticRemoved = true })
        assertFalse(wav.exists())
        assertFalse(File(wav.parentFile, "capture.ogg.partial").exists())
        assertTrue(diagnosticRemoved)
    }

    @Test
    fun `encoding failure retains original and cleans partial`() = runBlocking {
        val wav = pending()
        try {
            AudioExportQueue.export(wav,
                encode = { _, output -> output.writeText("partial"); throw IOException("codec failed") },
                outputExists = { false },
                publish = { _, _ -> fail("must not publish") })
            fail("must report failure")
        } catch (_: IOException) { }
        assertEquals("original audio", wav.readText())
        assertFalse(File(wav.parentFile, "capture.ogg.partial").exists())
    }

    @Test
    fun `publication failure retains original for retry`() = runBlocking {
        val wav = pending()
        try {
            AudioExportQueue.export(wav,
                encode = { _, output -> output.writeText("opus") },
                outputExists = { false },
                publish = { _, _ -> throw IOException("folder unavailable") })
            fail("must report failure")
        } catch (_: IOException) { }
        assertTrue(wav.exists())
        assertFalse(File(wav.parentFile, "capture.ogg.partial").exists())
    }

    @Test
    fun `existing output after process interruption is not published again`() = runBlocking {
        val wav = pending()
        AudioExportQueue.export(wav,
            encode = { _, _ -> fail("must not reencode") },
            outputExists = { true },
            publish = { _, _ -> fail("must not republish") })
        assertFalse(wav.exists())
    }

    @Test
    fun `missing pending audio is already handled`() = runBlocking {
        AudioExportQueue.export(File(temp.root, "missing.wav"),
            encode = { _, _ -> fail("must not encode") },
            outputExists = { fail("must not inspect output"); false },
            publish = { _, _ -> fail("must not publish") })
    }

    @Test
    fun `concurrent sweeps publish a capture only once`() = runBlocking {
        val wav = pending()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val publications = AtomicInteger()
        val first = async(Dispatchers.Default) {
            AudioExportQueue.export(wav,
                encode = { _, output ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    output.writeText("opus")
                },
                outputExists = { false },
                publish = { _, _ -> publications.incrementAndGet() })
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) {
                AudioExportQueue.export(wav,
                    encode = { _, _ -> fail("must not duplicate encoding") },
                    outputExists = { false },
                    publish = { _, _ -> fail("must not duplicate publication") })
            }
            release.countDown()
            first.await()
            second.await()
            assertEquals(1, publications.get())
        } finally {
            release.countDown()
        }
    }
}
