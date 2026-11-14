package com.example.tools

import java.io.File
import java.io.IOException

/**
 * Manages the isolated HTML sandbox files for chat sessions.
 *
 * Each session has an associated HTML file under [sandboxesDir]. The file is
 * auto-created as an empty 0-byte file when the session is initialized, and
 * deleted when the session is removed.
 *
 * The model modifies the sandbox using an "old string -> new string" exact
 * search & replace system.
 */
class HtmlSandboxManager(
    val sandboxesDir: File
) {
    init {
        if (!sandboxesDir.exists()) {
            sandboxesDir.mkdirs()
        }
    }

    /**
     * Returns the File pointer for [sessionId]'s HTML sandbox.
     */
    fun getSandboxFile(sessionId: String): File {
        return File(sandboxesDir, "${sessionId}.html")
    }

    /**
     * Ensures an empty 0-byte sandbox file exists for [sessionId].
     * If the file does not exist, creates it.
     */
    fun ensureSandboxExists(sessionId: String): File {
        val file = getSandboxFile(sessionId)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        return file
    }

    /**
     * Deletes the sandbox file for [sessionId] when the chat session is removed.
     */
    fun deleteSandbox(sessionId: String): Boolean {
        val file = getSandboxFile(sessionId)
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }

    /**
     * Returns the full raw HTML content of the sandbox for [sessionId], or empty string if none.
     */
    fun getSandboxContent(sessionId: String): String {
        val file = getSandboxFile(sessionId)
        if (!file.exists()) return ""
        return runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
    }

    /**
     * Returns true if [sessionId] has a sandbox file with non-blank content.
     */
    fun hasSandboxContent(sessionId: String): Boolean {
        val content = getSandboxContent(sessionId)
        return content.isNotBlank()
    }

    /**
     * Reads a slice of lines from the sandbox file for [sessionId].
     *
     * [startLine] is 1-based.
     * [limit] is the maximum number of lines to return.
     *
     * Returns a pair of (success, formattedOutput).
     */
    fun readSandbox(sessionId: String, startLine: Int, limit: Int): Pair<Boolean, String> {
        val file = getSandboxFile(sessionId)
        if (!file.exists()) {
            return false to "HTML sandbox file does not exist for session $sessionId."
        }

        val content = file.readText(Charsets.UTF_8)
        if (content.isEmpty()) {
            return true to "HTML sandbox is empty (0 bytes). Use edit_html_sandbox with empty old_string to initialize it."
        }

        val lines = content.lines()
        val totalLines = lines.size
        val effectiveStart = if (startLine < 1) 1 else startLine
        val effectiveLimit = if (limit < 1) 50 else limit

        if (effectiveStart > totalLines) {
            return false to "Start line $startLine is beyond the end of file (file has $totalLines lines)."
        }

        val endIndex = minOf(effectiveStart - 1 + effectiveLimit, totalLines)
        val selectedLines = lines.subList(effectiveStart - 1, endIndex)

        val formatted = buildString {
            append("Showing lines $effectiveStart-$endIndex of $totalLines:\n")
            selectedLines.forEachIndexed { index, line ->
                val lineNum = effectiveStart + index
                append(String.format("%4d | %s\n", lineNum, line))
            }
        }.trimEnd()

        return true to formatted
    }

    /**
     * Modifies the sandbox file for [sessionId] using old string -> new string replacement.
     *
     * If the file is currently empty (0 bytes):
     * - [oldString] MUST be empty (""), in which case the entire file content becomes [newString].
     * - If [oldString] is not empty, fails with an informative error.
     *
     * If the file is not empty:
     * - [oldString] must appear exactly once in the file.
     * - If not found, fails with an error.
     * - If found multiple times, fails with an ambiguity error.
     * - If exactly one match, replaces it with [newString].
     *
     * Returns a pair of (success, message).
     */
    fun editSandbox(sessionId: String, oldString: String, newString: String): Pair<Boolean, String> {
        val file = ensureSandboxExists(sessionId)
        val currentContent = file.readText(Charsets.UTF_8)

        if (currentContent.isEmpty()) {
            if (oldString.isNotEmpty()) {
                return false to "Failed to edit HTML sandbox: the file is currently empty (0 bytes). Use an empty old_string (\"\") to write the initial HTML code."
            }
            return try {
                file.writeText(newString, Charsets.UTF_8)
                val lineCount = newString.lines().size
                true to "Successfully initialized HTML sandbox ($lineCount lines, ${newString.length} chars written)."
            } catch (e: IOException) {
                false to "I/O error writing to HTML sandbox: ${e.message}"
            }
        }

        // File is non-empty
        if (oldString.isEmpty()) {
            return false to "Failed to edit HTML sandbox: old_string is empty, but the sandbox already contains code. Specify the exact target text to replace."
        }

        val occurrences = countOccurrences(currentContent, oldString)
        if (occurrences == 0) {
            return false to "Failed to edit HTML sandbox: old_string was not found in the file. Ensure the string matches the existing content exactly (including whitespace and indentation). You can use read_html_sandbox to view current lines."
        }

        if (occurrences > 1) {
            return false to "Failed to edit HTML sandbox: old_string was found $occurrences times. The target string must be unique within the file. Include more surrounding lines in old_string to make it unique."
        }

        val updatedContent = currentContent.replace(oldString, newString)
        return try {
            file.writeText(updatedContent, Charsets.UTF_8)
            val lineCount = updatedContent.lines().size
            true to "Successfully updated HTML sandbox (now $lineCount lines, ${updatedContent.length} chars)."
        } catch (e: IOException) {
            false to "I/O error writing to HTML sandbox: ${e.message}"
        }
    }

    private fun countOccurrences(text: String, target: String): Int {
        if (target.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            index = text.indexOf(target, index)
            if (index == -1) break
            count++
            index += target.length
        }
        return count
    }
}
