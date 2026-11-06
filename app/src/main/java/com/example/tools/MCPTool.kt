package com.example.tools

data class MCPTool(
    val name: String,
    val description: String,
    val parametersDescription: String
)

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
