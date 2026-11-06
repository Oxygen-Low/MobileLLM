package com.example.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MCPToolExecutor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    val availableTools = listOf(
        MCPTool(
            name = "duckduckgo_search",
            description = "Searches the live web using DuckDuckGo to obtain up-to-date facts, current events, documentation, or links.",
            parametersDescription = "query: string (the search terms to look up)"
        ),
        MCPTool(
            name = "get_current_datetime",
            description = "Returns current system date, day of the week, and local time.",
            parametersDescription = "none"
        ),
        MCPTool(
            name = "calculate_expression",
            description = "Evaluates basic arithmetic expressions accurately.",
            parametersDescription = "expression: string (e.g. '128 * 1024')"
        )
    )

    fun getCurrentFormattedDate(): String {
        val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)
        return sdf.format(Date())
    }

    fun getCurrentFormattedDateTime(): String {
        val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy HH:mm:ss z", Locale.US)
        return sdf.format(Date())
    }

    suspend fun executeTool(toolName: String, argument: String): ToolCallResponse {
        return withContext(Dispatchers.IO) {
            when (toolName) {
                "duckduckgo_search" -> performDuckDuckGoSearch(argument)
                "get_current_datetime" -> {
                    ToolCallResponse(
                        toolName = toolName,
                        argument = argument,
                        result = "Current system date and time: ${getCurrentFormattedDateTime()}",
                        success = true
                    )
                }
                "calculate_expression" -> {
                    val result = evaluateMath(argument)
                    ToolCallResponse(
                        toolName = toolName,
                        argument = argument,
                        result = result,
                        success = true
                    )
                }
                else -> {
                    ToolCallResponse(
                        toolName = toolName,
                        argument = argument,
                        result = "Unknown tool: $toolName",
                        success = false
                    )
                }
            }
        }
    }

    suspend fun performDuckDuckGoSearch(query: String): ToolCallResponse {
        return withContext(Dispatchers.IO) {
            try {
                val cleanQuery = query.trim().removeSurrounding("\"").removeSurrounding("'")
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                
                // 1. Try DuckDuckGo Instant Answer API
                val apiUrl = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
                val apiRequest = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                    .build()

                val apiResponse = client.newCall(apiRequest).execute()
                val body = apiResponse.body?.string() ?: ""

                var snippet = ""
                var sourceUrl: String? = null

                if (body.isNotEmpty() && body.startsWith("{")) {
                    val json = JSONObject(body)
                    val abstractText = json.optString("AbstractText", "")
                    val abstractUrl = json.optString("AbstractURL", "")
                    val heading = json.optString("Heading", "")

                    if (abstractText.isNotEmpty()) {
                        snippet = "[$heading] $abstractText"
                        sourceUrl = abstractUrl.ifEmpty { null }
                    } else {
                        val relatedTopics = json.optJSONArray("RelatedTopics")
                        if (relatedTopics != null && relatedTopics.length() > 0) {
                            val snippets = mutableListOf<String>()
                            for (i in 0 until relatedTopics.length().coerceAtMost(3)) {
                                val item = relatedTopics.optJSONObject(i)
                                if (item != null) {
                                    val text = item.optString("Text", "")
                                    val url = item.optString("FirstURL", "")
                                    if (text.isNotEmpty()) {
                                        snippets.add("• $text (Source: $url)")
                                        if (sourceUrl == null && url.isNotEmpty()) {
                                            sourceUrl = url
                                        }
                                    }
                                }
                            }
                            if (snippets.isNotEmpty()) {
                                snippet = snippets.joinToString("\n")
                            }
                        }
                    }
                }

                // 2. If API yielded empty abstract (common for specific long-tail queries), query HTML lite
                if (snippet.isEmpty()) {
                    val htmlUrl = "https://html.duckduckgo.com/html/?q=$encoded"
                    val htmlRequest = Request.Builder()
                        .url(htmlUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build()

                    val htmlResponse = client.newCall(htmlRequest).execute()
                    val htmlBody = htmlResponse.body?.string() ?: ""

                    // Extract snippets and titles with lightweight regex parsing
                    val snippetRegex = Regex("""<a class="result__snippet[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                    val urlRegex = Regex("""<a class="result__url"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                    
                    val matches = snippetRegex.findAll(htmlBody).take(3).map {
                        it.groupValues[1].replace(Regex("<[^>]*>"), "").trim()
                    }.filter { it.isNotEmpty() }.toList()

                    val firstUrl = urlRegex.find(htmlBody)?.groupValues?.get(1)?.trim()

                    if (matches.isNotEmpty()) {
                        snippet = matches.mapIndexed { idx, s -> "[Result ${idx + 1}] $s" }.joinToString("\n\n")
                        sourceUrl = firstUrl
                    }
                }

                if (snippet.isNotEmpty()) {
                    ToolCallResponse(
                        toolName = "duckduckgo_search",
                        argument = query,
                        result = "DuckDuckGo Web Search Results for '$query':\n$snippet",
                        success = true,
                        sourceUrl = sourceUrl
                    )
                } else {
                    ToolCallResponse(
                        toolName = "duckduckgo_search",
                        argument = query,
                        result = "DuckDuckGo search completed for '$query'. No instant answer found. The model will reason based on context and verified knowledge.",
                        success = true
                    )
                }
            } catch (e: Exception) {
                ToolCallResponse(
                    toolName = "duckduckgo_search",
                    argument = query,
                    result = "Search network call error: ${e.message ?: "Unable to contact DuckDuckGo"}. Operating in offline grounding mode.",
                    success = false
                )
            }
        }
    }

    private fun evaluateMath(expr: String): String {
        return try {
            val clean = expr.replace(" ", "").replace("x", "*").replace("X", "*")
            // Simple arithmetic evaluator
            val parts = clean.split("+", "-", "*", "/")
            if (parts.size == 2) {
                val a = parts[0].toDouble()
                val b = parts[1].toDouble()
                val res = when {
                    clean.contains("+") -> a + b
                    clean.contains("-") -> a - b
                    clean.contains("*") -> a * b
                    clean.contains("/") -> if (b != 0.0) a / b else Double.NaN
                    else -> Double.NaN
                }
                "$expr = $res"
            } else {
                "Expression: $expr"
            }
        } catch (e: Exception) {
            "Could not parse arithmetic expression: $expr"
        }
    }
}
