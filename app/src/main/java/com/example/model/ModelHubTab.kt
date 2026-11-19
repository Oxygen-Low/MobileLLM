package com.example.model

/**
 * The kinds of models the Model Hub is split into.
 *
 * Every tab holds one kind of model. Only the tabs that have weights behind
 * them can show something useful today, the other ones tell the user which
 * models are still to come.
 */
enum class ModelHubTab(val title: String) {
    /** Chat models that run on llama.cpp. */
    TEXT("Text"),

    /** Image generation models. */
    IMAGE("Image"),

    /** Text and image models that run on AI Horde instead of the device. */
    HORDE("AI Horde"),

    /** Music generation models. */
    MUSIC("Music"),

    /** Video generation models. */
    VIDEO("Video"),

    /** Piper voices that read the replies of the assistant out loud. */
    TTS("TTS"),

    /** Whisper models that write down what the user says. */
    STT("STT");

    /**
     * True for the tabs whose models are not implemented yet.
     *
     * Those tabs only show a placeholder instead of a list of weights.
     */
    val isWorkInProgress: Boolean
        get() = this == MUSIC

    companion object {
        /**
         * The tab a speech model belongs to.
         */
        fun forSpeech(kind: SpeechModelKind): ModelHubTab = when (kind) {
            SpeechModelKind.SPEECH_TO_TEXT -> STT
            SpeechModelKind.TEXT_TO_SPEECH -> TTS
        }
    }
}
