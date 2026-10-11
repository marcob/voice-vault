package dev.cazimir.voicevault.capture

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import dev.cazimir.voicevault.Config
import dev.cazimir.voicevault.notify.CaptureNotifications
import dev.cazimir.voicevault.audio.AudioExportQueue
import dev.cazimir.voicevault.vault.VaultWriter
import dev.cazimir.voicevault.vault.WavEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns one capture end to end: acquire the microphone, signal via haptics, stop on
 * silence/tap/screen-off/cap, persist audio before anything else, then export compressed audio.
 * Runs as a foreground service so recording survives the screen locking or the
 * phone being pocketed - the whole point of an eyes-free capture flow.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START_CAPTURE = "dev.cazimir.voicevault.action.START_CAPTURE"
        const val ACTION_RETRY_PENDING = "dev.cazimir.voicevault.action.RETRY_PENDING"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var haptics: Haptics
    private lateinit var notifications: CaptureNotifications
    private val audioRecorder = AudioRecorder()

    override fun onCreate() {
        super.onCreate()
        haptics = Haptics(this)
        notifications = CaptureNotifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RETRY_PENDING -> serviceScope.launch { retryPending(startId) }
            else -> serviceScope.launch { runCapture(startId) }
        }
        return START_NOT_STICKY
    }

    private suspend fun runCapture(startId: Int) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            haptics.atRisk()
            notifications.postFailure("Microphone permission is required. Open MB-VoiceVault to grant it.")
            stopSelf(startId)
            return
        }
        startForeground(CaptureNotifications.STATUS_NOTIFICATION_ID, notifications.listeningNotification())

        if (!VaultWriter.isVaultReady(this)) {
            haptics.atRisk()
            notifications.postVaultAccessLost()
            stopSelf(startId)
            return
        }

        serviceScope.launch { retryPending(startId = -1) }

        val stop = ExternalStop()
        CaptureController.activeStop = stop
        // Per-capture, not a service field: captures can overlap, and each one must stop
        // on its own screen-off rather than sharing a registration with another.
        val screenOffStop = registerScreenOffStop(stop)
        CaptureAudioFocus.acquire(this) { stop.request() }
        val audioFocus = Registration { CaptureAudioFocus.release() }
        startTapToStopOverlay()

        val result = try {
            audioRecorder.record(
                externalStop = stop,
                onCapturing = { haptics.started() },
                onAmplitude = { CaptureEvents.publishAmplitude(it) },
            )
        } catch (t: Throwable) {
            Log.w(Config.LOG_TAG, "recording failed to start", t)
            screenOffStop.release()
            audioFocus.release()
            CaptureController.activeStop = null
            CaptureEvents.notifyRecordingEnded()
            haptics.atRisk()
            notifications.postFailure("Could not start recording.")
            stopSelf(startId)
            return
        }

        screenOffStop.release()
        audioFocus.release()
        CaptureController.activeStop = null
        haptics.stopped()
        CaptureEvents.notifyRecordingEnded()

        if (result.pcm.isEmpty()) {
            haptics.atRisk()
            notifications.postFailure("No audio was captured.")
            stopSelf(startId)
            return
        }

        val wav = WavEncoder.encode(result.pcm, result.sampleRateHz)
        val pendingFile = try {
            VaultWriter.persistCapture(this, wav)
        } catch (t: Throwable) {
            Log.w(Config.LOG_TAG, "could not save recording", t)
            haptics.atRisk()
            notifications.postFailure("Could not save the recording.")
            stopSelf(startId)
            return
        }

        notifications.updateToSaving()
        exportPending(pendingFile)
        stopSelf(startId)
    }

    private suspend fun retryPending(startId: Int) {
        if (!VaultWriter.isVaultReady(this)) {
            if (startId >= 0) stopSelf(startId)
            return
        }
        val pendingFiles = VaultWriter.pendingAudioFiles(this)
        // Clear alerts for captures that are no longer pending.
        notifications.clearStaleFailures(pendingFiles.mapTo(mutableSetOf()) { it.nameWithoutExtension })
        for (file in pendingFiles) {
            exportPending(file)
        }
        if (startId >= 0) stopSelf(startId)
    }

    private suspend fun exportPending(file: File) {
        val captureId = file.nameWithoutExtension
        try {
            AudioExportQueue.export(applicationContext, file)
            notifications.cancelFailure(captureId)
        } catch (t: Throwable) {
            Log.w(Config.LOG_TAG, "audio export failed for $captureId", t)
            if (!file.exists()) {
                notifications.cancelFailure(captureId)
                return
            }
            try {
                VaultWriter.writeDiagnostic(this, captureId, "Audio export failed for $captureId\n${t.stackTraceToString()}")
            } catch (diagnosticError: Throwable) {
                Log.w(Config.LOG_TAG, "could not save diagnostic for $captureId", diagnosticError)
            }
            val seconds = VaultWriter.wavDurationSeconds(file).toInt()
            notifications.postFailure(
                "Could not export the ${seconds}s recording. Audio is saved locally and retries automatically.",
                captureId
            )
            haptics.atRisk()
        }
    }

    /**
     * Ends the capture when the user turns off the screen. Pressing the power button and
     * pocketing the phone is the real "I'm done" gesture, and it is also what fills the
     * microphone with fabric noise - so on the app's primary flow the silence threshold is
     * never reached. This is a stop, not a cancel: it sets the same flag tap-to-stop uses,
     * so everything downstream of the recording loop is unchanged.
     *
     * Owned by the service rather than the overlay, so it still works if the overlay never
     * launched - nothing in the capture flow may depend on that activity.
     * `ACTION_SCREEN_OFF` is not deliverable to a manifest-declared receiver.
     */
    private fun registerScreenOffStop(stop: ExternalStop): Registration {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) stop.request()
            }
        }
        return try {
            registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            Registration { unregisterReceiver(receiver) }
        } catch (t: Throwable) {
            // A capture that cannot listen for screen-off is still a valid capture; it just
            // falls back to the silence and hard-cap stop conditions.
            Log.w(Config.LOG_TAG, "could not listen for screen-off", t)
            Registration { }
        }
    }

    /** Idempotent teardown - `unregisterReceiver` throws if it is called a second time. */
    private class Registration(private val undo: () -> Unit) {
        private var released = false

        fun release() {
            if (released) return
            released = true
            undo()
        }
    }

    private fun startTapToStopOverlay() {
        val intent = Intent(this, TapToStopActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        startActivity(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
