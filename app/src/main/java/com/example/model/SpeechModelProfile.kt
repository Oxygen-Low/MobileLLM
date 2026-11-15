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
 *
 * Text to Speech models are Piper voices. They all share the same layout - the
 * ONNX weights named after the voice, a token table and the espeak-ng data
 * that turns text into phonemes - so [voiceFile] and [locale] are enough to
 * load any of them.
 */
data class SpeechModelProfile(
    val id: String,
    val name: String,
    val kind: SpeechModelKind,
    val archiveUrl: String,
    /** Compressed download size, used for the progress UI and resume checks. */
    val downloadSizeMb: Int,
    /**
     * The exact size of the archive in bytes.
     *
     * The download uses this to tell a complete file from a truncated one, so
     * it has to be the real size of the release and not a rounded guess.
     */
    val archiveSizeBytes: Long = 0L,
    val description: String,
    val requiredFiles: List<String>,
    val prunedFiles: List<String> = emptyList(),
    /** The ONNX weights of a Piper voice, e.g. "en_US-amy-low.onnx". */
    val voiceFile: String? = null,
    /** The locale the voice speaks, e.g. "en_US". */
    val locale: String = "",
    /** The language of the voice in the user's language, e.g. "English (US)". */
    val language: String = "",
    /** The quality of the voice weights: "low", "medium" or "x_low". */
    val quality: String = "",
    /** A sentence in the voice's own language, used for the preview button. */
    val previewText: String = "",
    /**
     * True when the model needs the espeak-ng data to turn text into phonemes.
     *
     * Every Piper archive ships the very same data set, so the app keeps one
     * copy for all of the voices instead of one copy per voice.
     */
    val needsEspeakData: Boolean = false
) {
    fun modelDir(root: File): File = File(root, id)

    /**
     * True when the model can be used: its own files are there and, for a
     * voice, the shared espeak-ng data is there as well.
     */
    fun isReady(root: File): Boolean {
        if (requiredFiles.isEmpty()) return false
        if (!requiredFiles.all { File(modelDir(root), it).isFile }) return false
        if (!needsEspeakData) return true
        return espeakIsReady(root)
    }

    companion object {
        /**
         * The directory that holds the espeak-ng data. Every voice points at
         * the same one, so the 17 MB of phoneme tables are stored once.
         */
        const val ESPEAK_DIR_NAME = "espeak-ng-data"

        /** The tables of the espeak-ng data that every voice needs. */
        val ESPEAK_CORE_FILES = listOf("phondata", "intonations")

        /** The espeak-ng data, shared by all of the voices. */
        fun espeakDir(root: File): File = File(root, ESPEAK_DIR_NAME)

        /**
         * True when the shared espeak-ng data is usable. A directory that was
         * left half extracted is as bad as a missing one.
         */
        fun espeakIsReady(root: File): Boolean =
            ESPEAK_CORE_FILES.all { File(espeakDir(root), it).isFile }

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
            archiveSizeBytes = 118_071_777L,
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
         *
         * The id predates the voice picker and is kept so that installations
         * that already downloaded Amy do not have to fetch it again.
         */
        val PIPER_AMY_LOW = piper(
            id = "piper-amy-low",
            name = "Amy",
            slug = "amy",
            locale = "en_US",
            language = "English (US)",
            quality = "low",
            archiveSizeBytes = 21_099_246L,
            description = "A warm female American English voice.",
            previewText = "Hello! This is how the assistant sounds when it reads " +
                "a reply to you."
        )

        /** Every Piper voice the app can speak with, in the order they are shown. */
        val TEXT_TO_SPEECH_MODELS = listOf(
            PIPER_AMY_LOW,
            piper(
                name = "Ryan", slug = "ryan", locale = "en_US",
                language = "English (US)", quality = "low",
                archiveSizeBytes = 21_212_659L,
                description = "A calm male American English voice.",
                previewText = "Hello! This is how the assistant sounds when it " +
                    "reads a reply to you."
            ),
            piper(
                name = "Danny", slug = "danny", locale = "en_US",
                language = "English (US)", quality = "low",
                archiveSizeBytes = 21_184_916L,
                description = "A relaxed male American English voice.",
                previewText = "Hello! This is how the assistant sounds when it " +
                    "reads a reply to you."
            ),
            piper(
                name = "Lessac", slug = "lessac", locale = "en_US",
                language = "English (US)", quality = "medium",
                archiveSizeBytes = 20_969_179L,
                description = "A clear female American English voice.",
                previewText = "Hello! This is how the assistant sounds when it " +
                    "reads a reply to you."
            ),
            piper(
                name = "John", slug = "john", locale = "en_US",
                language = "English (US)", quality = "medium",
                archiveSizeBytes = 20_800_096L,
                description = "A steady male American English voice.",
                previewText = "Hello! This is how the assistant sounds when it " +
                    "reads a reply to you."
            ),
            piper(
                name = "Joe", slug = "joe", locale = "en_US",
                language = "English (US)", quality = "medium",
                archiveSizeBytes = 21_230_019L,
                description = "A friendly male American English voice.",
                previewText = "Hello! This is how the assistant sounds when it " +
                    "reads a reply to you."
            ),
            piper(
                name = "Sam", slug = "sam", locale = "en_US",
                language = "English (US)", quality = "medium",
                archiveSizeBytes = 20_874_946L,
                description = "A bright male American English voice.",
                previewText = "Hello! This is how the assistant sounds when it " +
                    "reads a reply to you."
            ),
            piper(
                name = "Kristin", slug = "kristin", locale = "en_US",
                language = "English (US)", quality = "medium",
                archiveSizeBytes = 20_882_061L,
                description = "A soft female American English voice.",
                previewText = "Hello! This is how the assistant sounds when it " +
                    "reads a reply to you."
            ),
            piper(
                name = "Alan", slug = "alan", locale = "en_GB",
                language = "English (UK)", quality = "medium",
                archiveSizeBytes = 21_103_831L,
                description = "A male British English voice.",
                previewText = "Hello! This is how the assistant sounds when it " +
                    "reads a reply to you."
            ),
            piper(
                name = "Jenny", slug = "jenny_dioco", locale = "en_GB",
                language = "English (UK)", quality = "medium",
                archiveSizeBytes = 20_950_036L,
                description = "A female British English voice.",
                previewText = "Hello! This is how the assistant sounds when it " +
                    "reads a reply to you."
            ),
            piper(
                name = "Northern English", slug = "northern_english_male", locale = "en_GB",
                language = "English (UK)", quality = "medium",
                archiveSizeBytes = 21_100_291L,
                description = "A male voice with a northern British accent.",
                previewText = "Hello! This is how the assistant sounds when it " +
                    "reads a reply to you."
            ),
            piper(
                name = "Southern English", slug = "southern_english_female", locale = "en_GB",
                language = "English (UK)", quality = "medium",
                archiveSizeBytes = 23_714_066L,
                description = "A female voice with a southern British accent.",
                previewText = "Hello! This is how the assistant sounds when it " +
                    "reads a reply to you."
            ),
            piper(
                name = "Thorsten", slug = "thorsten", locale = "de_DE",
                language = "German", quality = "medium",
                archiveSizeBytes = 20_949_833L,
                description = "A male German voice.",
                previewText = "Hallo! So klingt die Stimme, wenn die App eine " +
                    "Antwort vorliest."
            ),
            piper(
                name = "Siwis", slug = "siwis", locale = "fr_FR",
                language = "French", quality = "low",
                archiveSizeBytes = 13_317_962L,
                description = "A male French voice.",
                previewText = "Bonjour ! Voici comment l'assistant sonne quand " +
                    "il lit une réponse."
            ),
            piper(
                name = "Davefx", slug = "davefx", locale = "es_ES",
                language = "Spanish", quality = "medium",
                archiveSizeBytes = 21_171_632L,
                description = "A male Spanish voice.",
                previewText = "¡Hola! Así suena el asistente cuando lee una " +
                    "respuesta."
            ),
            piper(
                name = "Riccardo", slug = "riccardo", locale = "it_IT",
                language = "Italian", quality = "x_low",
                archiveSizeBytes = 13_329_285L,
                description = "A male Italian voice.",
                previewText = "Ciao! Ecco come suona l'assistente quando legge " +
                    "una risposta."
            ),
            piper(
                name = "Darkman", slug = "darkman", locale = "pl_PL",
                language = "Polish", quality = "medium",
                archiveSizeBytes = 21_078_264L,
                description = "A male Polish voice.",
                previewText = "Cześć! Tak brzmi głos asystenta, gdy czyta " +
                    "odpowiedź."
            ),
            piper(
                name = "Denis", slug = "denis", locale = "ru_RU",
                language = "Russian", quality = "medium",
                archiveSizeBytes = 21_058_905L,
                description = "A male Russian voice.",
                previewText = "Привет! Так звучит голос ассистента, когда он " +
                    "читает ответ."
            ),
            piper(
                name = "Tugao", slug = "tugao", locale = "pt_PT",
                language = "Portuguese", quality = "medium",
                archiveSizeBytes = 21_253_211L,
                description = "A male European Portuguese voice.",
                previewText = "Olá! É assim que o assistente soa quando lê uma " +
                    "resposta."
            )
        )

        val SPEECH_TO_TEXT_MODELS = listOf(WHISPER_TINY)
        val ALL_MODELS = SPEECH_TO_TEXT_MODELS + TEXT_TO_SPEECH_MODELS

        /** The voice used when the user has not picked one yet. */
        val DEFAULT_TTS_VOICE = PIPER_AMY_LOW

        /**
         * Builds the profile of a Piper voice.
         *
         * Every voice is published as its own archive whose name follows the
         * `<locale>-<slug>-<quality>` pattern, and the same name is used for
         * the ONNX weights inside it.
         */
        private fun piper(
            name: String,
            slug: String,
            locale: String,
            language: String,
            quality: String,
            archiveSizeBytes: Long,
            description: String,
            previewText: String,
            id: String = "piper-$locale-$slug-$quality".lowercase()
        ): SpeechModelProfile {
            val voiceFile = "$locale-$slug-$quality.onnx"
            return SpeechModelProfile(
                id = id,
                name = name,
                kind = SpeechModelKind.TEXT_TO_SPEECH,
                archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                    "tts-models/vits-piper-$locale-$slug-$quality-int8.tar.bz2",
                downloadSizeMb = ((archiveSizeBytes + 1024L * 1024L - 1) / (1024L * 1024L)).toInt(),
                archiveSizeBytes = archiveSizeBytes,
                description = description,
                requiredFiles = listOf(voiceFile, "tokens.txt"),
                voiceFile = voiceFile,
                locale = locale,
                language = language,
                quality = quality,
                previewText = previewText,
                needsEspeakData = true
            )
        }
    }
}
