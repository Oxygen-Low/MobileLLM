package com.example.tools

import org.w3c.dom.Element
import java.io.StringReader
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** One result of a web search. */
internal data class WebResult(
    val title: String,
    val url: String,
    val snippet: String
)

/** One headline of a news feed. */
internal data class NewsItem(
    val title: String,
    val link: String,
    val source: String,
    val date: String
)

/**
 * Turns a snippet of HTML into plain text.
 */
internal object HtmlText {

    private val TAGS = Regex("<[^>]+>")
    private val WHITESPACE = Regex("\\s+")
    private val NUMERIC_ENTITY = Regex("&#(\\d+);")
    private val HEX_ENTITY = Regex("&#x([0-9a-fA-F]+);")

    private val ENTITIES = mapOf(
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&apos;" to "'",
        "&nbsp;" to " ",
        "&hellip;" to "...",
        "&rsquo;" to "'",
        "&lsquo;" to "'",
        "&ldquo;" to "\"",
        "&rdquo;" to "\"",
        "&mdash;" to "-",
        "&ndash;" to "-"
    )

    fun clean(html: String): String {
        var text = TAGS.replace(html, " ")
        for ((entity, replacement) in ENTITIES) {
            text = text.replace(entity, replacement)
        }
        text = NUMERIC_ENTITY.replace(text) { match ->
            character(match.groupValues[1].toIntOrNull(), match.value)
        }
        text = HEX_ENTITY.replace(text) { match ->
            character(match.groupValues[1].toIntOrNull(16), match.value)
        }
        return text.replace(WHITESPACE, " ").trim()
    }

    private fun character(code: Int?, fallback: String): String {
        if (code == null || code < 1 || code > 0x10FFFF) return fallback
        return String(Character.toChars(code))
    }
}

/**
 * Reads the result list of DuckDuckGo's HTML page.
 *
 * The page puts every organic result in a block that starts with the title
 * link and is followed by the target link and the snippet. Reading the block
 * after a title keeps the three of them together - and, unlike picking the
 * first snippet on the page, it does not hand the model the marketing copy
 * of whichever brand happened to rank first.
 */
internal object DuckDuckGoHtmlParser {

    /**
     * The opening tag of a result block. The class list tells an organic
     * result apart from a sponsored one.
     */
    private val CONTAINER = Regex("""<div[^>]*class="([^"]*\bresult\b[^"]*)""")
    private val ANCHOR = Regex("""<a([^>]*)>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val HREF = Regex("""href="([^"]*)"""")

    private const val CLASS_TITLE = "result__a"
    private const val CLASS_URL = "result__url"
    private const val CLASS_SNIPPET = "result__snippet"

    fun parse(html: String): List<WebResult> {
        val containers = CONTAINER.findAll(html).toList()
        if (containers.isEmpty()) return emptyList()

        val results = mutableListOf<WebResult>()
        for (index in containers.indices) {
            val classes = containers[index].groupValues[1]
            // Sponsored rows and "related searches" are not results.
            if (classes.contains("result--ad") || classes.contains("badge--ad")) continue

            val start = containers[index].range.last + 1
            val end = containers.getOrNull(index + 1)?.range?.first ?: html.length
            if (end <= start) continue
            val block = html.substring(start, end)

            val titleAnchor = ANCHOR.findAll(block)
                .firstOrNull { it.groupValues[1].contains(CLASS_TITLE) } ?: continue
            val title = HtmlText.clean(titleAnchor.groupValues[2])
            if (title.isEmpty()) continue

            val target = ANCHOR.findAll(block)
                .firstOrNull { it.groupValues[1].contains(CLASS_URL) }
            val snippet = ANCHOR.findAll(block)
                .firstOrNull { it.groupValues[1].contains(CLASS_SNIPPET) }

            val href = target?.groupValues?.get(1)?.let { HREF.find(it)?.groupValues?.get(1) }
                ?: HREF.find(titleAnchor.groupValues[1])?.groupValues?.get(1).orEmpty()

            results.add(
                WebResult(
                    title = title,
                    url = unwrap(href),
                    snippet = HtmlText.clean(snippet?.groupValues?.get(2).orEmpty())
                )
            )
        }
        return results
    }

    /**
     * DuckDuckGo links to its own redirector, which carries the real target
     * in the `uddg` parameter.
     */
    private fun unwrap(href: String): String {
        val absolute = if (href.startsWith("//")) "https:$href" else href
        val marker = absolute.indexOf("uddg=")
        if (marker < 0) return absolute
        val encoded = absolute.substring(marker + 5).substringBefore('&')
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(absolute)
    }
}

/**
 * Reads the items of an RSS feed.
 *
 * The parser is the one of the platform, so it works the same on the device
 * and in a unit test.
 */
internal object RssParser {

    fun parse(xml: String): List<NewsItem> {
        val items = runCatching { parseDocument(xml) }.getOrDefault(emptyList())
        return items
    }

    private fun parseDocument(xml: String): List<NewsItem> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // A feed must not be able to pull in another document.
        runCatching {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }

        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }

        val document = builder.parse(InputSource(StringReader(xml)))
        val nodes = document.getElementsByTagName("item")

        val items = mutableListOf<NewsItem>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val title = childText(element, "title")?.takeIf { it.isNotBlank() } ?: continue
            items.add(
                NewsItem(
                    title = title,
                    link = childText(element, "link").orEmpty(),
                    source = childText(element, "source").orEmpty(),
                    date = childText(element, "pubDate").orEmpty()
                )
            )
        }
        return items
    }

    /** The text of a direct child element. */
    private fun childText(element: Element, name: String): String? {
        var node = element.firstChild
        while (node != null) {
            if (node is Element && node.tagName.equals(name, ignoreCase = true)) {
                return node.textContent?.trim()
            }
            node = node.nextSibling
        }
        return null
    }
}
