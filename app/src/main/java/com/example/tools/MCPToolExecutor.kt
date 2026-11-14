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
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class MCPToolExecutor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    var htmlSandboxManager: HtmlSandboxManager? = null
) {
    var activeSessionId: String? = null

    val availableTools = listOf(
        MCPTool(
            name = "duckduckgo_search",
            description = "Searches the live web for pages and the news wire for headlines, to obtain up-to-date facts, current events, documentation, or links.",
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
        ),
        MCPTool(
            name = "edit_html_sandbox",
            description = "Modifies the current session's HTML sandbox file for interactive mobile apps or games using exact old_string to new_string replacement. If the sandbox is empty (0 bytes), pass an empty string for old_string (\"\") to write the initial HTML code. For non-empty files, old_string must match exactly once in the file.",
            parametersDescription = "JSON object with \"old_string\" and \"new_string\", e.g. {\"old_string\": \"\", \"new_string\": \"<!DOCTYPE html><html>...</html>\"}"
        ),
        MCPTool(
            name = "read_html_sandbox",
            description = "Reads a numbered section of lines from the current session's HTML sandbox file to inspect existing code before editing.",
            parametersDescription = "JSON object with \"start_line\" (1-based integer) and \"limit\" (line count, default 50), e.g. {\"start_line\": 1, \"limit\": 100}"
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
                "edit_html_sandbox" -> performEditHtmlSandbox(argument)
                "read_html_sandbox" -> performReadHtmlSandbox(argument)
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

    private fun performEditHtmlSandbox(argument: String): ToolCallResponse {
        val sessionId = activeSessionId
            ?: return ToolCallResponse(
                toolName = "edit_html_sandbox",
                argument = argument,
                result = "Error: No active chat session found for HTML sandbox.",
                success = false
            )
        val manager = htmlSandboxManager
            ?: return ToolCallResponse(
                toolName = "edit_html_sandbox",
                argument = argument,
                result = "Error: HTML sandbox manager is not initialized.",
                success = false
            )

        val trimmed = argument.trim()
        val oldString = MiniJson.getString(trimmed, "old_string", "target", "old")
        val newString = MiniJson.getString(trimmed, "new_string", "replacement", "new")

        if (!trimmed.contains("\"new_string\"") && !trimmed.contains("\"replacement\"") && !trimmed.contains("\"new\"")) {
            return ToolCallResponse(
                toolName = "edit_html_sandbox",
                argument = argument,
                result = "Invalid argument for edit_html_sandbox: missing \"new_string\". Expected JSON format: {\"old_string\": \"...\", \"new_string\": \"...\"}",
                success = false
            )
        }

        val (success, message) = manager.editSandbox(sessionId, oldString, newString)
        return ToolCallResponse(
            toolName = "edit_html_sandbox",
            argument = argument,
            result = message,
            success = success
        )
    }

    private fun performReadHtmlSandbox(argument: String): ToolCallResponse {
        val sessionId = activeSessionId
            ?: return ToolCallResponse(
                toolName = "read_html_sandbox",
                argument = argument,
                result = "Error: No active chat session found for HTML sandbox.",
                success = false
            )
        val manager = htmlSandboxManager
            ?: return ToolCallResponse(
                toolName = "read_html_sandbox",
                argument = argument,
                result = "Error: HTML sandbox manager is not initialized.",
                success = false
            )

        val trimmed = argument.trim()
        val startLine = MiniJson.getInt(trimmed, "start_line", "start", default = 1)
        val limit = MiniJson.getInt(trimmed, "limit", "line_count", "count", default = 50)

        val (success, message) = manager.readSandbox(sessionId, startLine, limit)
        return ToolCallResponse(
            toolName = "read_html_sandbox",
            argument = argument,
            result = message,
            success = success
        )
    }

    /**
     * Searches the web for [query].
     *
     * A question about what is happening right now is answered by the news
     * wire. A plain web search would rank the news channels themselves
     * first and hand back their marketing copy instead of a headline.
     */
    suspend fun performDuckDuckGoSearch(query: String): ToolCallResponse {
        return withContext(Dispatchers.IO) {
            val cleanQuery = query.trim().removeSurrounding("\"").removeSurrounding("'")
            try {
                if (isNewsQuery(cleanQuery)) {
                    val headlines = fetchNews(cleanQuery)
                    if (headlines.isNotEmpty()) {
                        return@withContext newsResponse(cleanQuery, headlines)
                    }
                }

                fetchInstantAnswer(cleanQuery)?.let { return@withContext it }

                fetchWebResults(cleanQuery)?.let { return@withContext it }

                // Nothing came back from the web - the news wire is the last
                // try, it also covers questions that were phrased too oddly
                // to be recognised as a news question.
                val headlines = fetchNews(cleanQuery)
                if (headlines.isNotEmpty()) {
                    return@withContext newsResponse(cleanQuery, headlines)
                }

                ToolCallResponse(
                    toolName = "duckduckgo_search",
                    argument = cleanQuery,
                    result = "No results for '$cleanQuery'. The search providers did not return anything usable.",
                    success = true
                )
            } catch (e: Exception) {
                ToolCallResponse(
                    toolName = "duckduckgo_search",
                    argument = cleanQuery,
                    result = "Search network call error: ${e.message ?: "Unable to reach the search providers"}. Operating in offline grounding mode.",
                    success = false
                )
            }
        }
    }

    /**
     * DuckDuckGo's instant answer: one encyclopedic answer with a link.
     *
     * It is the quickest source and it is never rate limited, so it is tried
     * first for everything that is not a question about current events.
     */
    private fun fetchInstantAnswer(query: String): ToolCallResponse? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = get("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
            ?: return null

        // A malformed answer must not take the whole search down with it.
        return runCatching { parseInstantAnswer(query, body) }.getOrNull()
    }

    private fun parseInstantAnswer(query: String, body: String): ToolCallResponse? {
        val json = JSONObject(body)

        val abstractText = json.optString("AbstractText", "")
        val abstractUrl = json.optString("AbstractURL", "")
        val heading = json.optString("Heading", "")

        if (abstractText.isNotEmpty()) {
            val answer = if (heading.isNotEmpty()) "$heading: $abstractText" else abstractText
            val text = buildString {
                append("Search result for '").append(query).append("':\n")
                append(answer)
                if (abstractUrl.isNotEmpty()) {
                    append("\n").append(abstractUrl)
                }
            }
            return ToolCallResponse(
                toolName = "duckduckgo_search",
                argument = query,
                result = text,
                success = true,
                sourceUrl = abstractUrl.ifEmpty { null }
            )
        }

        val related = json.optJSONArray("RelatedTopics")
        if (related != null && related.length() > 0) {
            val snippets = mutableListOf<String>()
            for (index in 0 until minOf(related.length(), MAX_RESULTS)) {
                val item = related.optJSONObject(index) ?: continue
                val text = item.optString("Text", "")
                if (text.isNotEmpty()) {
                    snippets.add("• $text (${item.optString("FirstURL", "")})")
                }
            }
            if (snippets.isNotEmpty()) {
                return ToolCallResponse(
                    toolName = "duckduckgo_search",
                    argument = query,
                    result = "Search results for '$query':\n${snippets.joinToString("\n")}",
                    success = true
                )
            }
        }

        return null
    }

    /**
     * DuckDuckGo's result page: the pages of the web with their title, their
     * link and the snippet under them.
     */
    private fun fetchWebResults(query: String): ToolCallResponse? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = get("https://html.duckduckgo.com/html/?q=$encoded") ?: return null

        val results = DuckDuckGoHtmlParser.parse(html)
        if (results.isEmpty()) return null

        val text = buildString {
            append("Search results for '").append(query).append("':\n")
            results.take(MAX_RESULTS).forEachIndexed { index, result ->
                append(index + 1).append(". ").append(result.title).append('\n')
                if (result.url.isNotEmpty()) {
                    append("   ").append(result.url).append('\n')
                }
                if (result.snippet.isNotEmpty()) {
                    append("   ").append(result.snippet).append('\n')
                }
            }
        }

        return ToolCallResponse(
            toolName = "duckduckgo_search",
            argument = query,
            result = text.trim(),
            success = true,
            sourceUrl = results.first().url.ifEmpty { null }
        )
    }

    /**
     * The headlines of the news wire.
     *
     * Every headline comes with the outlet that published it and the date it
     * was published on, which is what makes "the latest news" answerable.
     */
    private fun fetchNews(query: String): List<NewsItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://news.google.com/rss/search?q=$encoded&hl=en-US&gl=US&ceid=US:en"
        val body = get(url) ?: return emptyList()
        return runCatching { RssParser.parse(body) }.getOrDefault(emptyList())
    }

    private fun newsResponse(query: String, headlines: List<NewsItem>): ToolCallResponse {
        val shown = headlines.take(MAX_RESULTS)
        val text = buildString {
            append("Latest news for '").append(query).append("':\n")
            shown.forEachIndexed { index, item ->
                append(index + 1).append(". ").append(cleanHeadline(item)).append('\n')
            }
        }

        return ToolCallResponse(
            toolName = "duckduckgo_search",
            argument = query,
            result = text.trim(),
            success = true,
            sourceUrl = shown.firstOrNull()?.link?.ifEmpty { null }
        )
    }

    /**
     * A headline as "the story - the outlet", without the date the feed
     * already repeats in the title.
     */
    private fun cleanHeadline(item: NewsItem): String {
        var title = item.title
        val source = item.source
        if (source.isNotEmpty()) {
            title = title.removeSuffix(" - $source")
        }
        val date = formatNewsDate(item.date)
        return if (source.isNotEmpty() && date.isNotEmpty()) {
            "$title ($source, $date)"
        } else if (source.isNotEmpty()) {
            "$title ($source)"
        } else if (date.isNotEmpty()) {
            "$title ($date)"
        } else {
            title
        }
    }

    /** "Thu, 12 Nov 2026 08:49:00 GMT" becomes "12 Nov 2026". */
    private fun formatNewsDate(raw: String): String {
        if (raw.isBlank()) return ""
        val parsed = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT") }
                .parse(raw)
        }.getOrNull() ?: return raw
        return SimpleDateFormat("d MMM yyyy", Locale.US).format(parsed)
    }

    /** Performs a GET request and returns the body, or null when it failed. */
    private fun get(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", MOBILE_USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (e: Exception) {
            null
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

    private companion object {
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0"

        /** Results handed to the model at most. */
        const val MAX_RESULTS = 5
    }
}

/**
 * True when the question is about what is happening right now.
 *
 * Those questions belong to the news wire. A plain web search answers "latest
 * news" with the marketing copy of whichever news channel ranked first
 * instead of with a headline.
 */
internal fun isNewsQuery(query: String): Boolean {
    val text = query.lowercase(Locale.US)
    if (NEWS_WORDS.any { text.contains(it) }) return true

    val fresh = text.contains("latest") ||
        text.contains("recent") ||
        text.contains("up to date") ||
        text.contains("right now")
    val now = text.contains("today") ||
        text.contains("currently") ||
        text.contains("these days") ||
        text.contains("this week")
    return fresh && now
}

private val NEWS_WORDS = listOf(
    "news",
    "headline",
    "breaking",
    "current event",
    "top stories",
    "happening"
)

internal object MiniJson {
    fun getString(json: String, vararg keys: String): String {
        for (key in keys) {
            val pattern = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            val match = pattern.find(json)
            if (match != null) {
                return unescape(match.groupValues[1])
            }
        }
        return ""
    }

    fun getInt(json: String, vararg keys: String, default: Int): Int {
        for (key in keys) {
            val pattern = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\\d+)")
            val match = pattern.find(json)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: default
            }
        }
        return default
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(c)
                        i++
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
