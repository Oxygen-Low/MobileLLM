package com.example

import com.example.model.SpeechArchiveExtractor
import com.example.model.SpeechModelProfile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

/**
 * The voice archives hold the weights of one voice and the espeak-ng data
 * that turns text into phonemes. The data is the same in every archive, so
 * the extractor puts it in one place that all of the voices share.
 */
class SpeechArchiveExtractorTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a voice archive is unpacked into its own directory`() {
        val speech = folder.newFolder("speech")
        val archive = writeArchive(
            name = "vits-piper-en_US-amy-low-int8",
            files = mapOf(
                "en_US-amy-low.onnx" to "weights",
                "tokens.txt" to "tokens"
            )
        )

        SpeechArchiveExtractor(speech, File(speech, "espeak-ng-data"))
            .extract(archive, SpeechModelProfile.PIPER_AMY_LOW)

        val dir = File(speech, SpeechModelProfile.PIPER_AMY_LOW.id)
        assertTrue(File(dir, "en_US-amy-low.onnx").isFile)
        assertTrue(File(dir, "tokens.txt").isFile)
    }

    @Test
    fun `the espeak data ends up in the shared place`() {
        val speech = folder.newFolder("speech")
        val archive = writeArchive(
            name = "vits-piper-en_US-amy-low-int8",
            files = mapOf(
                "en_US-amy-low.onnx" to "weights",
                "tokens.txt" to "tokens",
                "espeak-ng-data/phondata" to "phonemes",
                "espeak-ng-data/intonations" to "intonations"
            )
        )

        SpeechArchiveExtractor(speech, File(speech, "espeak-ng-data"))
            .extract(archive, SpeechModelProfile.PIPER_AMY_LOW)

        assertTrue(File(speech, "espeak-ng-data/phondata").isFile)
        // The voice directory does not keep a second copy of the data.
        val voiceEspeak = File(File(speech, SpeechModelProfile.PIPER_AMY_LOW.id), "espeak-ng-data")
        assertFalse(voiceEspeak.exists())
    }

    @Test
    fun `the second voice reuses the data of the first one`() {
        val speech = folder.newFolder("speech")
        val amy = SpeechModelProfile.PIPER_AMY_LOW
        val ryan = SpeechModelProfile.TEXT_TO_SPEECH_MODELS
            .first { it.id != amy.id && it.locale == "en_US" }

        val amyArchive = writeArchive(
            name = "vits-piper-en_US-amy-low-int8",
            files = mapOf(
                "en_US-amy-low.onnx" to "weights",
                "tokens.txt" to "tokens",
                "espeak-ng-data/phondata" to "phonemes",
                "espeak-ng-data/intonations" to "intonations"
            )
        )
        val ryanArchive = writeArchive(
            name = "vits-piper-en_US-ryan-low-int8",
            files = mapOf(
                "en_US-ryan-low.onnx" to "weights",
                "tokens.txt" to "tokens",
                "espeak-ng-data/phondata" to "phonemes",
                "espeak-ng-data/intonations" to "intonations"
            )
        )

        val extractor = SpeechArchiveExtractor(speech, File(speech, "espeak-ng-data"))
        extractor.extract(amyArchive, amy)
        extractor.extract(ryanArchive, ryan)

        assertTrue(amy.isReady(speech))
        assertTrue(ryan.isReady(speech))
        // One copy of the data serves both voices.
        assertEquals(
            1,
            speech.listFiles()?.count { it.name == "espeak-ng-data" }
        )
    }

    @Test
    fun `an archive that tries to escape its directory is refused`() {
        val speech = folder.newFolder("speech")
        val archive = writeArchive(
            name = "vits-piper-en_US-amy-low-int8",
            files = mapOf("../../escaped.onnx" to "weights")
        )

        val failure = runCatching {
            SpeechArchiveExtractor(speech, File(speech, "espeak-ng-data"))
                .extract(archive, SpeechModelProfile.PIPER_AMY_LOW)
        }

        assertTrue(failure.isFailure)
        assertFalse(File(speech.parentFile, "escaped.onnx").exists())
    }

    /** Builds a tar.bz2 with one directory and the given files in it. */
    private fun writeArchive(name: String, files: Map<String, String>): File {
        val archive = folder.newFile("${name.replace("/", "-")}.tar.bz2")
        FileOutputStream(archive).use { output ->
            BZip2CompressorOutputStream(output).use { compressed ->
                TarArchiveOutputStream(compressed).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    files.forEach { (path, content) ->
                        val entry = TarArchiveEntry("$name/$path")
                        entry.size = content.toByteArray().size.toLong()
                        tar.putArchiveEntry(entry)
                        tar.write(content.toByteArray())
                        tar.closeArchiveEntry()
                    }
                }
            }
        }
        return archive
    }
}
