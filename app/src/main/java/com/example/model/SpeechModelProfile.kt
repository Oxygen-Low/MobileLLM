package com.example.model

import java.io.File

/** What a local speech model is used for. */
enum class SpeechModelKind {
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH
}

/**
 * A small speech model that is downloaded on demand and loaded into memory
 * only while its feature toggle is enabled.
 *
 * The archives are the official sherpa-onnx releases. Each one extracts into a
 * single directory; [requiredFiles] are the files the runtime needs inside that
 * directory, and [prunedFiles] are extra files shipped in the archive that are
 * not worth keeping on a phone (unquantised copies, test audio).
 */
data class SpeechModelProfile(
    val id: String,
    val name: String,
    val kind: SpeechModelKind,
    val archiveUrl: String,
    /** Compressed download size, used for the progress UI and resume checks. */
    val downloadSizeMb: Int,
    val description: String,
    val requiredFiles: List<String>,
    val prunedFiles: List<String> = emptyList()
) {
    fun modelDir(root: File): File = File(root, id)

    fun isReady(root: File): Boolean =
        requiredFiles.isNotEmpty() && requiredFiles.all { File(modelDir(root), it).isFile }

    companion object {
        /**
         * Whisper Tiny (39M parameters) with the int8 encoder/decoder from the
         * sherpa-onnx release. Small enough for a phone, multilingual, and
         * fully offline.
         */
        val WHISPER_TINY = SpeechModelProfile(
            id = "whisper-tiny",
            name = "Whisper Tiny",
            kind = SpeechModelKind.SPEECH_TO_TEXT,
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                "asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2",
            downloadSizeMb = 113,
            description = "Whisper Tiny (int8) transcribes speech locally. " +
                "Used only while Speech to Text is enabled.",
            requiredFiles = listOf(
                "tiny.en-encoder.int8.onnx",
                "tiny.en-decoder.int8.onnx",
                "tiny.en-tokens.txt"
            ),
            prunedFiles = listOf(
                "tiny.en-encoder.onnx",
                "tiny.en-decoder.onnx",
                "test_wavs"
            )
        )

        /**
         * Piper VITS voice (Amy, low quality) in int8. 20 MB download and a
         * natural-sounding English voice, which keeps TTS comfortable next to
         * a multi-gigabyte language model.
         */
        val PIPER_AMY_LOW = SpeechModelProfile(
            id = "piper-amy-low",
            name = "Piper Amy (low)",
            kind = SpeechModelKind.TEXT_TO_SPEECH,
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                "tts-models/vits-piper-en_US-amy-low-int8.tar.bz2",
            downloadSizeMb = 20,
            description = "Piper VITS voice (int8) speaks replies locally. " +
                "Used only while Text to Speech is enabled.",
            requiredFiles = listOf(
                "en_US-amy-low.onnx",
                "tokens.txt",
                "espeak-ng-data/en_dict"
            )
        )

        val SPEECH_TO_TEXT_MODELS = listOf(WHISPER_TINY)
        val TEXT_TO_SPEECH_MODELS = listOf(PIPER_AMY_LOW)
        val ALL_MODELS = SPEECH_TO_TEXT_MODELS + TEXT_TO_SPEECH_MODELS
    }
}