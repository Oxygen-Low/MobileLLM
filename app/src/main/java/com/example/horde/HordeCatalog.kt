package com.example.horde

import com.example.model.HORDE_ID_PREFIX
import com.example.model.ImageModelProfile
import com.example.model.ModelBackend
import com.example.model.ModelFormat
import com.example.model.ModelProfile

/** A text model advertised by the AI Horde OpenAI-compatible front. */
data class HordeTextInfo(
    val id: String,
    val contextLength: Int,
    val maxTokens: Int,
    val workers: Int,
    val queued: Int,
    val supportsReasoning: Boolean
)

/** An image model advertised by the native AI Horde status endpoint. */
data class HordeImageInfo(
    val name: String,
    val workers: Int,
    val queued: Int,
    val eta: Int
)

/**
 * Turns the Horde catalogues into the profile types the rest of the app uses.
 *
 * A remote model is a profile with a [ModelBackend.HORDE] backend and an id
 * prefixed with [HORDE_ID_PREFIX]. That lets the existing selection and
 * streaming code work unchanged: only the engine branches on the backend.
 */
object HordeCatalog {

    fun textProfile(info: HordeTextInfo): ModelProfile {
        val status = buildString {
            if (info.workers > 0) append("${info.workers} workers")
            if (info.queued > 0) {
                if (isNotEmpty()) append(" · ")
                append("${info.queued} queued")
            }
        }
        return ModelProfile(
            id = "$HORDE_ID_PREFIX${info.id}",
            name = info.id,
            shortName = info.id,
            format = ModelFormat.SAFETENSORS,
            quantization = "Hosted",
            parameters = "Remote",
            contextLength = info.contextLength.coerceIn(512, 131072),
            memoryMb = 0,
            description = if (status.isEmpty()) "Runs on AI Horde" else "Runs on AI Horde · $status",
            supportsReasoning = info.supportsReasoning,
            backend = ModelBackend.HORDE,
            remoteModelId = info.id
        )
    }

    fun imageProfile(info: HordeImageInfo): ImageModelProfile {
        val status = buildString {
            if (info.workers > 0) append("${info.workers} workers")
            if (info.queued > 0) {
                if (isNotEmpty()) append(" · ")
                append("${info.queued} queued")
            }
        }
        return ImageModelProfile(
            id = "$HORDE_ID_PREFIX${info.name}",
            name = info.name,
            shortName = info.name,
            quantization = "Hosted",
            parameters = "Remote",
            resolution = 512,
            steps = 30,
            memoryMb = 0,
            description = if (status.isEmpty()) {
                "High quality image generation on AI Horde."
            } else {
                "High quality image generation on AI Horde · $status."
            },
            modelScopeRepo = null,
            modelScopeFile = null,
            modelScopeUrl = null,
            hfRepo = "",
            hfFile = "",
            downloadSizeMb = 0,
            backend = ModelBackend.HORDE,
            remoteModelId = info.name
        )
    }

    /**
     * Rebuilds the profile of a Horde model from a remembered id.
     *
     * The full catalogue is fetched when the hub opens; until then the id is
     * enough to send requests and to keep startup from replacing the selection
     * with a local model.
     */
    fun rememberedTextProfile(id: String): ModelProfile {
        val name = id.removePrefix(HORDE_ID_PREFIX)
        return ModelProfile(
            id = id,
            name = name,
            shortName = name,
            format = ModelFormat.SAFETENSORS,
            quantization = "Hosted",
            parameters = "Remote",
            contextLength = 8192,
            memoryMb = 0,
            description = "Runs on AI Horde",
            supportsReasoning = false,
            backend = ModelBackend.HORDE,
            remoteModelId = name
        )
    }

    /** Rebuilds the image profile of a remembered Horde selection. */
    fun rememberedImageProfile(id: String): ImageModelProfile {
        val name = id.removePrefix(HORDE_ID_PREFIX)
        return ImageModelProfile(
            id = id,
            name = name,
            shortName = name,
            quantization = "Hosted",
            parameters = "Remote",
            resolution = 512,
            steps = 30,
            memoryMb = 0,
            description = "High quality image generation on AI Horde.",
            modelScopeRepo = null,
            modelScopeFile = null,
            modelScopeUrl = null,
            hfRepo = "",
            hfFile = "",
            downloadSizeMb = 0,
            backend = ModelBackend.HORDE,
            remoteModelId = name
        )
    }

    fun isHordeId(id: String): Boolean = id.startsWith(HORDE_ID_PREFIX)
}
