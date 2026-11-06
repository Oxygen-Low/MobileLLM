package com.example.model

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SafeTensorsHeaderInfo(
    val isValid: Boolean,
    val headerSize: Long,
    val tensorCount: Int,
    val totalParamsEstimate: String,
    val metadata: Map<String, String>,
    val tensorNames: List<String>,
    val errorMessage: String? = null
)

object SafeTensorsParser {
    fun parse(inputStream: InputStream): SafeTensorsHeaderInfo {
        return try {
            val sizeBytes = ByteArray(8)
            var read = 0
            while (read < 8) {
                val r = inputStream.read(sizeBytes, read, 8 - read)
                if (r == -1) break
                read += r
            }

            if (read < 8) {
                return SafeTensorsHeaderInfo(
                    isValid = false,
                    headerSize = 0,
                    tensorCount = 0,
                    totalParamsEstimate = "0",
                    metadata = emptyMap(),
                    tensorNames = emptyList(),
                    errorMessage = "File too small to be valid SafeTensors"
                )
            }

            val buffer = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN)
            val headerSize = buffer.long

            if (headerSize <= 0 || headerSize > 100 * 1024 * 1024) {
                return SafeTensorsHeaderInfo(
                    isValid = false,
                    headerSize = headerSize,
                    tensorCount = 0,
                    totalParamsEstimate = "0",
                    metadata = emptyMap(),
                    tensorNames = emptyList(),
                    errorMessage = "Invalid SafeTensors header size: $headerSize"
                )
            }

            val bytesToRead = headerSize.toInt().coerceAtMost(1024 * 1024)
            val jsonBytes = ByteArray(bytesToRead)
            var jsonRead = 0
            while (jsonRead < jsonBytes.size) {
                val r = inputStream.read(jsonBytes, jsonRead, jsonBytes.size - jsonRead)
                if (r == -1) break
                jsonRead += r
            }

            val jsonString = String(jsonBytes, 0, jsonRead, Charsets.UTF_8).trim()
            if (!jsonString.startsWith("{") || !jsonString.endsWith("}")) {
                // Header might be larger than 1MB buffer, check start
                if (!jsonString.startsWith("{")) {
                    return SafeTensorsHeaderInfo(
                        isValid = false,
                        headerSize = headerSize,
                        tensorCount = 0,
                        totalParamsEstimate = "0",
                        metadata = emptyMap(),
                        tensorNames = emptyList(),
                        errorMessage = "Header does not start with JSON object '{'"
                    )
                }
            }

            val metadataMap = mutableMapOf<String, String>()
            // Extract __metadata__ if present
            val metaMatch = Regex("""("__metadata__"\s*:\s*\{([^}]*)\})""").find(jsonString)
            if (metaMatch != null) {
                val innerMeta = metaMatch.groupValues[2]
                val pairRegex = Regex(""""([^"]+)"\s*:\s*"([^"]*)"""")
                pairRegex.findAll(innerMeta).forEach { p ->
                    metadataMap[p.groupValues[1]] = p.groupValues[2]
                }
            }

            // Extract tensor keys: "tensor_name": { "dtype": ...
            val tensorKeyRegex = Regex(""""([^"]+)"\s*:\s*\{\s*"dtype"""")
            val tensorNames = mutableListOf<String>()
            var tensorCount = 0

            tensorKeyRegex.findAll(jsonString).forEach { match ->
                val name = match.groupValues[1]
                if (name != "__metadata__") {
                    tensorCount++
                    if (tensorNames.size < 20) {
                        tensorNames.add(name)
                    }
                }
            }

            // Fallback estimation if standard format
            val paramEstimate = when {
                tensorCount > 100 -> "Estimated ~1.7B"
                tensorCount > 0 -> "Estimated ~${tensorCount * 12}M"
                else -> "Unknown"
            }

            SafeTensorsHeaderInfo(
                isValid = true,
                headerSize = headerSize,
                tensorCount = tensorCount,
                totalParamsEstimate = paramEstimate,
                metadata = metadataMap,
                tensorNames = tensorNames
            )
        } catch (e: Exception) {
            SafeTensorsHeaderInfo(
                isValid = false,
                headerSize = 0,
                tensorCount = 0,
                totalParamsEstimate = "0",
                metadata = emptyMap(),
                tensorNames = emptyList(),
                errorMessage = e.localizedMessage ?: "Failed to parse SafeTensors header"
            )
        }
    }
}
