/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.parser

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.commonmark.node.Emphasis
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import ua.acclorite.book_story.core.helpers.clearMarkdown
import javax.inject.Inject

class MarkdownParser @Inject constructor(
    private val commonmarkParser: Parser
) {

    /** Characters CommonMark treats as syntax; a line without any of them has nothing to parse. */
    private val markdownMarkers = charArrayOf(
        '#', '*', '_', '`', '[', ']', '(', ')', '>', '!', '~', '\\', '<', '&'
    )

    /**
     * Parses markdown text to [androidx.compose.ui.text.AnnotatedString].
     *
     * @return Parsed annotated string.
     */
    fun parse(markdown: String): AnnotatedString {
        // Most lines of a novel are prose with nothing in them for CommonMark to do, and running the
        // parser over each one is what made opening a book slow: a thirty-seven megabyte novel is
        // about half a million lines, every one of them parsed into a document tree and walked right
        // back out, on every open. A line with no marker characters parses to itself.
        if (!hasMarkdownSyntax(markdown)) return AnnotatedString(markdown.trim())

        return try {
            val annotatedString = buildAnnotatedString {
                parseNode(commonmarkParser.parse(markdown))
            }.ifBlank { buildAnnotatedString { append(markdown) } }
                .trim() as AnnotatedString

            annotatedString
        } catch (e: Exception) {
            e.printStackTrace()
            buildAnnotatedString { append(markdown) }
        }
    }

    /** Whether CommonMark could do anything with this line — markers, or a list start. */
    private fun hasMarkdownSyntax(line: String): Boolean {
        for (char in line) {
            if (char in markdownMarkers) return true
        }

        val trimmed = line.trimStart()
        if (trimmed.startsWith("- ") || trimmed.startsWith("+ ")) return true

        var digits = 0
        while (digits < trimmed.length && trimmed[digits].isDigit()) digits += 1
        return digits > 0 && digits < trimmed.length && (trimmed[digits] == '.' || trimmed[digits] == ')')
    }

    /**
     * Parses [Node].
     * Appends text and applies styles to the target [AnnotatedString.Builder].
     */
    private fun AnnotatedString.Builder.parseNode(node: Node) {
        when (node) {
            is Heading, is StrongEmphasis -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                    parseChildren(node)
                }
            }

            is Emphasis -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    parseChildren(node)
                }
            }

            is Link -> {
                withLink(
                    LinkAnnotation.Url(
                        node.destination,
                        styles = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))
                    )
                ) {
                    parseChildren(node)
                    append(" (${node.destination})")
                }
            }

            is Text -> {
                append(node.literal.clearMarkdown())
                parseChildren(node)
            }

            else -> {
                parseChildren(node)
            }
        }
    }

    private fun AnnotatedString.Builder.parseChildren(node: Node) {
        var child = node.firstChild
        while (child != null) {
            parseNode(child)
            child = child.next
        }
    }
}