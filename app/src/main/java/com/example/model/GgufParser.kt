package com.example.model

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GgufHeaderInfo(
    val isValid: Boolean,
    val version: Int,
    val tensorCount: Long,
    val metadataKvCount: Long,
    val architecture: String,
    val contextLength: Int,
    val modelName: String,
    val quantizationType: String,
    val metadata: Map<String, String>,
    val errorMessage: String? = null
)

object GgufParser {
    private const val GGUF_MAGIC = 0x46554747 // "GGUF" in little-endian

    fun parse(inputStream: InputStream): GgufHeaderInfo {
        return try {
            val headerBytes = ByteArray(1024 * 16) // read first 16KB for header
            var bytesRead = 0
            while (bytesRead < headerBytes.size) {
                val read = inputStream.read(headerBytes, bytesRead, headerBytes.size - bytesRead)
                if (read == -1) break
                bytesRead += read
            }

            if (bytesRead < 24) {
                return GgufHeaderInfo(
                    isValid = false,
                    version = 0,
                    tensorCount = 0,
                    metadataKvCount = 0,
                    architecture = "Unknown",
                    contextLength = 4096,
                    modelName = "Unknown",
                    quantizationType = "Unknown",
                    metadata = emptyMap(),
                    errorMessage = "File too small to be valid GGUF"
                )
            }

            val buffer = ByteBuffer.wrap(headerBytes, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buffer.int
            if (magic != GGUF_MAGIC) {
                return GgufHeaderInfo(
                    isValid = false,
                    version = 0,
                    tensorCount = 0,
                    metadataKvCount = 0,
                    architecture = "Unknown",
                    contextLength = 4096,
                    modelName = "Unknown",
                    quantizationType = "Unknown",
                    metadata = emptyMap(),
                    errorMessage = "Invalid GGUF magic header signature"
                )
            }

            val version = buffer.int
            val tensorCount = buffer.long
            val metadataKvCount = buffer.long

            val metadataMap = mutableMapOf<String, String>()
            var architecture = "llama"
            var contextLength = 4096
            var modelName = "GGUF Model"
            var quantType = "Q4_K_M"

            // Parse key-value pairs safely
            var kvParsed = 0
            while (kvParsed < metadataKvCount && buffer.remaining() > 10) {
                try {
                    val keyLen = buffer.long.toInt()
                    if (keyLen <= 0 || keyLen > buffer.remaining()) break
                    val keyBytes = ByteArray(keyLen)
                    buffer.get(keyBytes)
                    val key = String(keyBytes, Charsets.UTF_8)

                    val valType = buffer.int
                    val valueStr = parseGgufValue(buffer, valType)
                    if (valueStr != null) {
                        metadataMap[key] = valueStr
                        if (key.endsWith(".context_length") || key == "general.context_length") {
                            valueStr.toIntOrNull()?.let { contextLength = it }
                        } else if (key == "general.architecture") {
                            architecture = valueStr
                        } else if (key == "general.name") {
                            modelName = valueStr
                        } else if (key.contains("quantization") || key == "general.file_type") {
                            quantType = valueStr
                        }
                    }
                    kvParsed++
                } catch (e: Exception) {
                    break
                }
            }

            GgufHeaderInfo(
                isValid = true,
                version = version,
                tensorCount = tensorCount,
                metadataKvCount = metadataKvCount,
                architecture = architecture,
                contextLength = contextLength,
                modelName = modelName,
                quantizationType = quantType,
                metadata = metadataMap
            )
        } catch (e: Exception) {
            GgufHeaderInfo(
                isValid = false,
                version = 0,
                tensorCount = 0,
                metadataKvCount = 0,
                architecture = "Unknown",
                contextLength = 4096,
                modelName = "Unknown",
                quantizationType = "Unknown",
                metadata = emptyMap(),
                errorMessage = e.localizedMessage ?: "Failed to parse GGUF file"
            )
        }
    }

    private fun parseGgufValue(buffer: ByteBuffer, type: Int): String? {
        return when (type) {
            0 -> buffer.get().toInt().toString() // UINT8
            1 -> buffer.get().toInt().toString() // INT8
            2 -> buffer.short.toString() // UINT16
            3 -> buffer.short.toString() // INT16
            4 -> buffer.int.toLong().and(0xFFFFFFFFL).toString() // UINT32
            5 -> buffer.int.toString() // INT32
            6 -> buffer.float.toString() // FLOAT32
            7 -> (buffer.get() != 0.toByte()).toString() // BOOL
            8 -> { // STRING
                val strLen = buffer.long.toInt()
                if (strLen in 1..buffer.remaining()) {
                    val strBytes = ByteArray(strLen)
                    buffer.get(strBytes)
                    String(strBytes, Charsets.UTF_8)
                } else null
            }
            9 -> { // ARRAY
                val itemType = buffer.int
                val arrayLen = buffer.long.toInt()
                "Array[$arrayLen items of type $itemType]"
            }
            10 -> buffer.long.toString() // UINT64
            11 -> buffer.long.toString() // INT64
            12 -> buffer.double.toString() // FLOAT64
            else -> null
        }
    }
}
