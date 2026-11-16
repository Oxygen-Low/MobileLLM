package com.example.video

import com.example.image.Base64Codec
import java.io.File
import java.util.UUID

/**
 * A video that was generated for a chat.
 *
 * The [token] is the short reference the text model writes into a reply or into
 * the HTML sandbox, for example `[clip](vid:1a2b3c4d5e6f)`. The clip itself is
 * an MP4 file with an AAC soundtrack, so the player gets the picture and the
 * sound from the same file.
 */
data class GeneratedVideo(
    val id: String,
    val sessionId: String,
    val prompt: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val hasAudio: Boolean,
    val createdAt: Long,
    val file: File
) {
    val token: String get() = tokenFor(id)

    companion object {
        /** The prefix that marks a reference to a generated clip. */
        const val PREFIX = "vid:"

        fun tokenFor(id: String): String = "$PREFIX$id"
    }
}

/** A file that is ready to receive an encoded clip but is not committed yet. */
data class PendingVideo(val id: String, val file: File)

/**
 * Stores the clips a chat generated, one folder per session.
 *
 * Clips are per-chat files, not database rows: a chat that is deleted takes its
 * folder with it, and the text stays readable even when a clip is gone. The
 * names are random, so a reply can safely reference one without the model ever
 * seeing a path.
 */
class GeneratedVideoStore(
    val rootDir: File
) {
    init {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
    }

    /** The folder that holds the clips of [sessionId]. */
    fun sessionDir(sessionId: String): File = File(rootDir, sessionId)

    /**
     * Reserves a path for a clip that is about to be encoded into the session
     * folder. The encoder writes straight into [PendingVideo.file], so a large
     * clip never has to be held in a Kotlin byte array first.
     */
    fun newFile(sessionId: String): PendingVideo {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val dir = sessionDir(sessionId)
        dir.mkdirs()
        return PendingVideo(id, File(dir, "$id.mp4"))
    }

    /**
     * Finishes a clip that was written to [pending] and returns it. A short
     * prompt is kept next to it so the transcript can describe what it shows.
     */
    fun complete(
        sessionId: String,
        pending: PendingVideo,
        prompt: String,
        width: Int,
        height: Int,
        durationMs: Long,
        hasAudio: Boolean
    ): GeneratedVideo {
        val file = pending.file
        runCatching { File(file.parentFile, "${pending.id}.txt").writeText(prompt, Charsets.UTF_8) }
        return GeneratedVideo(
            id = pending.id,
            sessionId = sessionId,
            prompt = prompt,
            width = width,
            height = height,
            durationMs = durationMs,
            hasAudio = hasAudio,
            createdAt = System.currentTimeMillis(),
            file = file
        )
    }

    /** Writes [mp4] into the session folder and returns the clip. */
    fun save(
        sessionId: String,
        mp4: ByteArray,
        prompt: String,
        width: Int,
        height: Int,
        durationMs: Long,
        hasAudio: Boolean
    ): GeneratedVideo {
        val pending = newFile(sessionId)
        pending.file.writeBytes(mp4)
        return complete(sessionId, pending, prompt, width, height, durationMs, hasAudio)
    }

    /** The clip file of [id], or null when it is unknown or gone. */
    fun fileFor(sessionId: String, id: String): File? {
        if (!isSafeId(id)) return null
        if (!isSafeSessionId(sessionId)) return null
        val file = File(sessionDir(sessionId), "$id.mp4")
        return if (file.isFile) file else null
    }

    /** The prompt that produced [id], or an empty string when there is none. */
    fun promptFor(sessionId: String, id: String): String {
        if (!isSafeId(id) || !isSafeSessionId(sessionId)) return ""
        val file = File(sessionDir(sessionId), "$id.txt")
        return if (file.isFile) runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("") else ""
    }

    /** Every clip of [sessionId], oldest first. */
    fun list(sessionId: String): List<GeneratedVideo> {
        if (!isSafeSessionId(sessionId)) return emptyList()
        val dir = sessionDir(sessionId)
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".mp4") }
            ?: return emptyList()
        return files.sortedBy { it.name }.map { file ->
            val id = file.name.removeSuffix(".mp4")
            GeneratedVideo(
                id = id,
                sessionId = sessionId,
                prompt = promptFor(sessionId, id),
                width = 0,
                height = 0,
                durationMs = 0,
                hasAudio = false,
                createdAt = file.lastModified(),
                file = file
            )
        }
    }

    /** A `data:` URI for [id], or null when the clip is gone. */
    fun dataUri(sessionId: String, id: String): String? {
        val file = fileFor(sessionId, id) ?: return null
        return "data:video/mp4;base64," + Base64Codec.encode(file.readBytes())
    }

    /** Removes every clip of [sessionId]. Called when the chat is deleted. */
    fun deleteSession(sessionId: String): Boolean {
        if (!isSafeSessionId(sessionId)) return false
        return sessionDir(sessionId).deleteRecursively()
    }

    /** Removes every clip the app stores. */
    fun deleteAll(): Boolean {
        val deleted = rootDir.deleteRecursively()
        rootDir.mkdirs()
        return deleted
    }

    companion object {
        /**
         * A clip id is generated by the app, so it only ever holds the
         * characters the random name is built from. Anything else is refused
         * so a forged reference cannot point outside the session folder.
         */
        fun isSafeId(id: String): Boolean =
            id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        /** Session ids are UUIDs, but a stricter check keeps paths predictable. */
        fun isSafeSessionId(sessionId: String): Boolean =
            sessionId.isNotEmpty() && sessionId.length <= 128 &&
                sessionId.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}