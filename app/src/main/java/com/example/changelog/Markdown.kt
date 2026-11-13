package com.example.changelog

/**
 * A block of a markdown document.
 */
sealed interface MarkdownBlock {
    /** A heading, `# Title` up to `### Title`. */
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    /** A list item, `- Item` or `* Item`. */
    data class Bullet(val text: String) : MarkdownBlock

    /** A run of plain text. */
    data class Paragraph(val text: String) : MarkdownBlock
}

/**
 * A very small markdown reader for the changelog files.
 *
 * The changelogs are written by hand and only use headings, bullets and plain
 * text, so only those are understood. Everything else is shown as it is.
 */
object Markdown {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^[-*+]\\s+(.*)$")

    /** Splits a markdown document into the blocks it is made of. */
    fun parse(text: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                blocks.add(MarkdownBlock.Paragraph(paragraph.toString().trim()))
                paragraph.setLength(0)
            }
        }

        for (rawLine in text.lines()) {
            val line = rawLine.trim()

            if (line.isEmpty()) {
                flushParagraph()
                continue
            }

            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                flushParagraph()
                blocks.add(
                    MarkdownBlock.Heading(
                        level = heading.groupValues[1].length,
                        text = heading.groupValues[2].trim()
                    )
                )
                continue
            }

            val bullet = BULLET.matchEntire(line)
            if (bullet != null) {
                flushParagraph()
                blocks.add(MarkdownBlock.Bullet(bullet.groupValues[1].trim()))
                continue
            }

            if (paragraph.isNotEmpty()) paragraph.append(' ')
            paragraph.append(line)
        }

        flushParagraph()
        return blocks
    }
}
