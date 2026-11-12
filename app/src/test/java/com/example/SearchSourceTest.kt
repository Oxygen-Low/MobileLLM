package com.example

import com.example.tools.DuckDuckGoHtmlParser
import com.example.tools.HtmlText
import com.example.tools.RssParser
import com.example.tools.isNewsQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSourceTest {

    /**
     * A cut down version of the page DuckDuckGo serves for a web search.
     * The first entry is a sponsored row, the second one a result.
     */
    private val resultPage = """
        <div class="result results_links results_links_deep result--ad">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fads.example.com%2Fbuy">Sponsored headline</a>
          </h2>
          <a class="result__url" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fads.example.com%2Fbuy">ads.example.com</a>
          <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fads.example.com%2Fbuy">Buy things now</a>
        </div>
        <div class="result results_links results_links_deep web-result ">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fblog.rust-lang.org%2F2025%2F09%2FRust-1.90.0.html&amp;rut=abc">Rust 1.90.0 released</a>
          </h2>
          <a class="result__url" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fblog.rust-lang.org%2F2025%2F09%2FRust-1.90.0.html&amp;rut=abc">blog.rust-lang.org &rsaquo; 2025 &rsaquo; 09</a>
          <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fblog.rust-lang.org%2F2025%2F09%2FRust-1.90.0.html&amp;rut=abc">Rust 1.90.0 has been released &amp; is ready to use.</a>
        </div>
    """.trimIndent()

    private val newsFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
        <item>
          <title>City building collapse leaves two dead - NBC News</title>
          <link>https://news.google.com/rss/articles/abc123?oc=5</link>
          <pubDate>Thu, 12 Nov 2026 08:49:00 GMT</pubDate>
          <description>&lt;a href="x"&gt;City building collapse&lt;/a&gt;</description>
          <source url="https://www.nbcnews.com">NBC News</source>
        </item>
        <item>
          <title>Storm warning issued for the coast</title>
          <link>https://news.google.com/rss/articles/def456?oc=5</link>
          <pubDate>Wed, 11 Nov 2026 22:15:00 GMT</pubDate>
          <source url="https://example.com">Example Times</source>
        </item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun `a result page yields the title, the real link and the snippet`() {
        val results = DuckDuckGoHtmlParser.parse(resultPage)

        assertEquals(1, results.size)
        val result = results.first()
        assertEquals("Rust 1.90.0 released", result.title)
        assertEquals("https://blog.rust-lang.org/2025/09/Rust-1.90.0.html", result.url)
        assertEquals("Rust 1.90.0 has been released & is ready to use.", result.snippet)
    }

    @Test
    fun `sponsored rows are not results`() {
        val results = DuckDuckGoHtmlParser.parse(resultPage)

        assertTrue(results.none { it.title.contains("Sponsored") })
    }

    @Test
    fun `a page without results yields nothing`() {
        assertTrue(DuckDuckGoHtmlParser.parse("<html><body>no results here</body></html>").isEmpty())
    }

    @Test
    fun `a news feed yields the headline, the outlet and the date`() {
        val items = RssParser.parse(newsFeed)

        assertEquals(2, items.size)
        assertEquals("City building collapse leaves two dead - NBC News", items[0].title)
        assertEquals("https://news.google.com/rss/articles/abc123?oc=5", items[0].link)
        assertEquals("NBC News", items[0].source)
        assertEquals("Thu, 12 Nov 2026 08:49:00 GMT", items[0].date)
        assertEquals("Example Times", items[1].source)
    }

    @Test
    fun `a broken feed yields nothing instead of crashing`() {
        assertTrue(RssParser.parse("<rss><channel><item></channel>").isEmpty())
    }

    @Test
    fun `html is turned into readable text`() {
        assertEquals(
            "Sky & Co <b>runs</b> things",
            HtmlText.clean("<b>Sky &amp; Co</b> &lt;b&gt;runs&lt;/b&gt; things")
        )
    }

    @Test
    fun `a question about the news goes to the news wire`() {
        assertTrue(isNewsQuery("latest news"))
        assertTrue(isNewsQuery("What's the news today?"))
        assertTrue(isNewsQuery("breaking news in Berlin"))
        assertTrue(isNewsQuery("top stories"))
        assertTrue(isNewsQuery("what's happening today"))
    }

    @Test
    fun `a question that is not about the news stays on the web`() {
        assertFalse(isNewsQuery("rust 1.90 release date"))
        assertFalse(isNewsQuery("capital of France"))
        assertFalse(isNewsQuery("current time"))
        assertFalse(isNewsQuery("latest rust version"))
        assertFalse(isNewsQuery("how to sort a list in kotlin"))
    }
}
