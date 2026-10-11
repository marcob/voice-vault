package dev.cazimir.voicevault.vault

/**
 * Whether the app currently holds a usable grant on the vault folder. The three states
 * are kept distinct so first-run (never picked a folder) is never confused with recovery
 * (picked one, but the grant no longer resolves to a writable folder).
 */
sealed interface VaultAccess {
    /** A folder is selected and its persisted grant resolves to a writable folder. */
    data object Available : VaultAccess

    /** The user has never selected a vault folder. */
    data object NotConfigured : VaultAccess

    /**
     * A folder was selected, but its grant no longer resolves to a writable folder -
     * moved, deleted, renamed, owning app uninstalled, permission revoked in Settings,
     * or the persisted preference cleared.
     */
    data object Lost : VaultAccess
}

/** A vault note read back for display: its base name (no `.md`), raw content, and mtime. */
data class RawNote(val baseName: String, val content: String, val lastModified: Long)

/**
 * The selected folder, described for the setup screen's confirmation line.
 *
 * @param displayPath the folder's path relative to its storage volume, e.g.
 *   `Recordings/Inbox`, so the user can confirm the selected output location.
 */
data class VaultFolderInfo(val displayPath: String)

/**
 * The SAF operations `VaultWriter` needs against the user-selected inbox folder. Kept to
 * plain `String` file names and value returns (no `DocumentFile`/`Uri` in the signatures)
 * so a JVM fake can stand in for it in unit tests. Pending audio and diagnostics do NOT
 * go through here - they stay `java.io.File`-based in `filesDir`.
 */
interface VaultStorage {

    /** Atomically publishes a complete Ogg Opus file, without replacing existing output. */
    fun writeAudio(fileName: String, source: java.io.File)

    fun audioExists(fileName: String): Boolean

    fun audioBaseNames(): List<String>

    fun access(): VaultAccess

    /** Describes the selected folder for the setup screen; null if no valid grant is held. */
    fun folderInfo(): VaultFolderInfo?

    /**
     * Writes [content] to [fileName] (e.g. `"2026-08-29 1200.md"`) atomically: a temp
     * document is written in full, then renamed into place, so a sync client watching the
     * folder never observes a partial file. Throws on any failure - callers treat a throw
     * as "note not saved".
     */
    fun writeNote(fileName: String, content: String)

    fun noteExists(fileName: String): Boolean

    /** Base names (no `.md`) of every note currently in the folder. Cheap: no content read. */
    fun noteBaseNames(): List<String>

    /** Every `.md` note with its content and mtime, unsorted. */
    fun listNotes(): List<RawNote>
}
