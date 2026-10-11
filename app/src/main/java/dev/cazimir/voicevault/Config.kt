package dev.cazimir.voicevault

object Config {
    /**
     * App-private durability checkpoint. WAV stays here until compressed audio is
     * successfully published to the selected output folder.
     */
    const val PENDING_AUDIO_DIR = "pending"

    const val SAMPLE_RATE_HZ = 16_000
    const val OPUS_BIT_RATE = 24_000
    const val SILENCE_RMS_THRESHOLD = 700.0
    const val SILENCE_DURATION_MS = 5_000L
    const val MAX_RECORDING_DURATION_MS = 180_000L

    /** Tag for every `Log` call in the app, so a tethered logcat session can filter on one string. */
    const val LOG_TAG = "VoiceVault"

    /** Extension for the per-capture diagnostic record written beside orphan audio in the pending folder. */
    const val DIAGNOSTIC_RECORD_EXTENSION = "log"

    /** Byte size of the fixed PCM16 mono header `WavEncoder` writes, used to derive duration from file size. */
    const val WAV_HEADER_SIZE_BYTES = 44

    /**
     * YAML frontmatter prepended to every note, with `{{created}}` replaced by the capture
     * date (yyyy-MM-dd). Deliberately minimal so notes drop cleanly into any vault. There is
     * no settings UI: to tailor this to your own vault (add Priority, Area, tags, whatever),
     * edit this template and rebuild. Set it to an empty string to write notes with no
     * frontmatter at all.
     */
    const val NOTE_FRONTMATTER_TEMPLATE = "---\ncreated: {{created}}\n---\n"

}
