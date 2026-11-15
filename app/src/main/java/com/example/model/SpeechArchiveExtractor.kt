package com.example.model

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Unpacks a speech archive into the app's private storage.
 *
 * The archives are tar.bz2 files that hold one directory with the weights of
 * a model. Every Piper voice also ships the same espeak-ng data, so that data
 * is lifted out and kept in one place that all of the voices share.
 */
class SpeechArchiveExtractor(
    private val speechDir: File,
    private val espeakDir: File
) {

    /**
     * Unpacks [archive] into `speech/<profile.id>`.
     *
     * Entries are checked against the extraction directory, so a malicious
     * archive cannot write outside of the app's private storage.
     */
    fun extract(archive: File, profile: SpeechModelProfile) {
        val temp = File(speechDir, "${profile.id}.extracting")
        temp.deleteRecursively()
        if (!temp.mkdirs()) throw IOException("Could not create ${temp.name}")

        val root = temp.canonicalFile
        try {
            unpack(archive, temp, root)

            val children = temp.listFiles().orEmpty()
            val source = children.singleOrNull()?.takeIf { it.isDirectory } ?: temp

            // Drop unquantised duplicates and sample audio that the runtime
            // never reads; speech storage is scarce next to the LLM weights.
            for (relative in profile.prunedFiles) {
                File(source, relative).deleteRecursively()
            }

            // The espeak-ng data is the same in every voice archive, so it is
            // lifted out of the voice directory and kept in one place.
            if (profile.needsEspeakData) {
                moveEspeakData(source)
            }

            val destination = File(speechDir, profile.id)
            destination.deleteRecursively()
            if (!source.renameTo(destination)) {
                source.copyRecursively(destination, overwrite = true)
                source.deleteRecursively()
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    /**
     * Moves the espeak-ng data of a freshly unpacked voice archive into the
     * place where all of the voices share it.
     */
    private fun moveEspeakData(source: File) {
        val extracted = File(source, SpeechModelProfile.ESPEAK_DIR_NAME)
        if (!extracted.isDirectory) return

        // A complete copy is already shared by the other voices.
        if (SpeechModelProfile.espeakIsReady(speechDir)) {
            extracted.deleteRecursively()
            return
        }

        // A half extracted copy is replaced by the fresh one.
        espeakDir.deleteRecursively()
        espeakDir.parentFile?.mkdirs()
        if (!extracted.renameTo(espeakDir)) {
            extracted.copyRecursively(espeakDir, overwrite = true)
            extracted.deleteRecursively()
        }
    }

    private fun unpack(archive: File, target: File, root: File) {
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        ).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val output = File(target, entry.name).canonicalFile
                if (!output.path.startsWith(root.path + File.separator) && output != root) {
                    throw IOException("Unsafe path in speech archive: ${entry.name}")
                }

                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { stream -> tar.copyTo(stream) }
                }
                entry = tar.nextEntry
            }
        }
    }
}
