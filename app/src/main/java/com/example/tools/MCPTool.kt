package com.example.tools

data class MCPTool(
    val name: String,
    val description: String,
    val parametersDescription: String
)

/**
 * A human readable name for a tool.
 *
 * The model calls tools by their raw name, the UI shows this one - "Used Web
 * & News Search" reads better than "Used duckduckgo_search".
 */
fun toolDisplayName(toolName: String?): String = when (toolName) {
    "duckduckgo_search" -> "Web & News Search"
    "get_current_datetime" -> "Device Clock"
    "calculate_expression" -> "Calculator"
    null -> "Tool"
    else -> toolName
}

data class ToolCallRequest(
    val toolName: String,
    val argument: String
)

data class ToolCallResponse(
    val toolName: String,
    val argument: String,
    val result: String,
    val success: Boolean,
    val sourceUrl: String? = null
)
