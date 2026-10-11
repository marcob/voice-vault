package dev.cazimir.voicevault.audio

import android.content.Context
import dev.cazimir.voicevault.vault.VaultWriter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Serializes export, not recording. Pending WAV is removed only after publication. */
object AudioExportQueue {
    private val mutex = Mutex()

    suspend fun export(context: Context, wav: File): Unit = export(
        wav,
        encode = OpusEncoder::encode,
        outputExists = { VaultWriter.audioExists(context, it) },
        publish = { id, file -> VaultWriter.writeAudio(context, file, id) },
        deleteDiagnostic = { VaultWriter.deleteDiagnostic(context, it) }
    )

    internal suspend fun export(
        wav: File,
        encode: (File, File) -> Unit,
        outputExists: (String) -> Boolean,
        publish: (String, File) -> Unit,
        deleteDiagnostic: (String) -> Unit = {}
    ) {
        mutex.withLock {
            if (!wav.exists()) return@withLock
            val id = wav.nameWithoutExtension
            val encoded = File(wav.parentFile, "$id.ogg.partial")
            try {
                if (!outputExists(id)) {
                    encode(wav, encoded)
                    publish(id, encoded)
                }
                check(wav.delete()) { "Could not remove exported pending audio" }
                deleteDiagnostic(id)
            } finally {
                encoded.delete()
            }
        }
    }
}
