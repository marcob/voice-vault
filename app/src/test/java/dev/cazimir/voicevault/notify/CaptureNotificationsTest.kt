package dev.cazimir.voicevault.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CaptureNotificationsTest {
    @Test
    fun `failure title identifies the recording`() {
        assertEquals("Recording needs attention · 2026-10-10 1200",
            CaptureNotifications.genericTitle("2026-10-10 1200"))
        assertEquals("Recording needs attention", CaptureNotifications.genericTitle(null))
    }

    @Test
    fun `capture notification ids avoid reserved ids`() {
        for (id in listOf("\u0001", "\u0002", "\u0003", "2026-10-10 1200")) {
            assertFalse(CaptureNotifications.failureNotificationId(id) in 1..3)
        }
    }
}
