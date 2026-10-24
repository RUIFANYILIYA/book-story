/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.parser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.yield
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import ua.acclorite.book_story.core.helpers.clearAllMarkdown
import ua.acclorite.book_story.core.helpers.clearMarkdown
import ua.acclorite.book_story.core.helpers.containsVisibleText
import ua.acclorite.book_story.domain.model.reader.ReaderText
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject

/** The widest an illustration is ever drawn: a phone column is ~1080 px, a tablet a bit more. */
private const val MAX_IMAGE_EDGE = 1440

/** Runs of newlines inside a paragraph are the source file's own wrapping, not a line break. */
private val NEWLINE_RUN = Regex("\\n+")

// Hoisted out of the per-line loop. These were built inside it, once per line — a novel of half a
// million lines compiled them a million times, which is where its opening time actually went.
private val TRIPLE_EMPHASIS = Regex("""\*\*\*\s*(.*?)\s*\*\*\*""")
private val DOUBLE_EMPHASIS = Regex("""\*\*\s*(.*?)\s*\*\*""")
private val UNDERSCORE_EMPHASIS = Regex("""_\s*(.*?)\s*_""")
private val IMAGE_MARKER = Regex("""\[\[(.*?)\|(.*?)]]""")

class DocumentParser @Inject constructor(
    private val markdownParser: MarkdownParser
) {
    /**
     * Parses document to get it's text.
     * Fixes issues such as manual line breaking in <p>.
     * Applies Markdown to the text: Bold(**), Italic(_), Section separator(---), and Links(a > href).
     *
     * @return Parsed text line by line with Markdown(all lines are not blank).
     */
    suspend fun parseDocument(
        document: Document,
        zipFile: ZipFile? = null,
        imageEntries: List<ZipEntry>? = null,
        includeChapter: Boolean = true
    ): List<ReaderText> {
        yield()

        val readerText = mutableListOf<ReaderText>()
        var chapterAdded = false

        document.selectFirst("body")
            .run { this ?: document.body() }
            .apply {
                // Remove manual line breaks from all <p>, <a>.
                //
                // This used to go through `element.html(element.html()...)`, which re-parses the
                // fragment: two full parses per paragraph. A 580-chapter novel has twenty thousand
                // paragraphs, and that alone was most of the six seconds the book took to open.
                // Editing the text nodes in place does the same thing without the parser.
                select("p").forEach { element ->
                    element.collapseNewlines(" ")
                    element.append("\n")
                }
                select("a").forEach { element ->
                    element.collapseNewlines("")
                }

                // Remove <head>'s title
                select("title").remove()

                // Markdown
                select("hr").append("\n---\n")
                select("b").append("**").prepend("**")
                select("h1").append("**").prepend("**")
                select("h2").append("**").prepend("**")
                select("h3").append("**").prepend("**")
                select("strong").append("**").prepend("**")
                select("em").append("_").prepend("_")
                select("a").forEach { element ->
                    var link = element.attr("href")
                    if (!link.startsWith("http") || element.wholeText().isBlank()) return@forEach

                    if (link.startsWith("http://")) {
                        link = link.replaceFirst("http://", "https://")
                    }

                    element.prepend("[")
                    element.append("]($link)")
                }

                // Image (<img>)
                select("img").forEach { element ->
                    val src = element.attr("src")
                        .trim()
                        .substringAfterLast(File.separator)
                        .lowercase()
                        .let { src -> URLDecoder.decode(src, StandardCharsets.UTF_8.name()) }
                        .takeIf {
                            it.containsVisibleText() && imageEntries?.any { image ->
                                it == image.name.substringAfterLast(File.separator).lowercase()
                            } == true
                        } ?: return@forEach

                    val alt = element.attr("alt").trim().takeIf {
                        it.clearMarkdown().containsVisibleText()
                    } ?: "Image"

                    element.append("\n[[$src|$alt]]\n")
                }

                // Image (<image>)
                select("image").forEach { element ->
                    val src = element.attr("xlink:href")
                        .trim()
                        .substringAfterLast(File.separator)
                        .lowercase()
                        .let { src -> URLDecoder.decode(src, StandardCharsets.UTF_8.name()) }
                        .takeIf {
                            it.containsVisibleText() && imageEntries?.any { image ->
                                it == image.name.substringAfterLast(File.separator).lowercase()
                            } == true
                        } ?: return@forEach

                    val alt = "Image"

                    element.append("\n[[$src|$alt]]\n")
                }
            }.wholeText().lines().forEach { line ->
                yield()

                if (line.containsVisibleText()) {
                    // Formatted inside the branch: a chapter is mostly blank lines and whitespace
                    // between elements, and formatting them was wasted work.
                    val formattedLine = line.replace(TRIPLE_EMPHASIS, "_**$1**_")
                        .replace(DOUBLE_EMPHASIS, "**$1**")
                        .replace(UNDERSCORE_EMPHASIS, "_$1_")
                        .trim()

                    when {
                        IMAGE_MARKER.matches(line) -> {
                            val trimmedLine = line.removeSurrounding("[[", "]]")
                            val src = trimmedLine.substringBefore("|")
                            val alt = "_${trimmedLine.substringAfter("|")}_"

                            val image = try {
                                val imageEntry = imageEntries?.find { image ->
                                    src == image.name.substringAfterLast(File.separator).lowercase()
                                } ?: return@forEach

                                zipFile?.getImage(imageEntry)?.asImageBitmap()
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            } ?: return@forEach

                            image.prepareToDraw()
                            readerText.add( // Adding image
                                ReaderText.Image(
                                    imageBitmap = image
                                )
                            )
                            readerText.add( // Adding alternative text (caption) for image
                                ReaderText.Text(
                                    markdownParser.parse(alt)
                                )
                            )
                        }

                        line == "---" || line == "***" -> readerText.add(ReaderText.Separator)

                        else -> {
                            if (
                                !chapterAdded &&
                                formattedLine.clearAllMarkdown().containsVisibleText() &&
                                includeChapter
                            ) {
                                readerText.add(
                                    0, ReaderText.Chapter(
                                        title = formattedLine.clearAllMarkdown(),
                                        nested = false
                                    )
                                )
                                chapterAdded = true
                            } else if (
                                formattedLine.clearMarkdown().containsVisibleText()
                            ) {
                                readerText.add(
                                    ReaderText.Text(
                                        line = markdownParser.parse(formattedLine)
                                    )
                                )
                            }
                        }
                    }
                }
            }

        yield()

        if (
            readerText.filterIsInstance<ReaderText.Text>().isEmpty() ||
            (includeChapter && readerText.filterIsInstance<ReaderText.Chapter>().isEmpty())
        ) {
            return emptyList()
        }

        return readerText
    }

    /**
     * Getting bitmap from [ZipFile] with compression
     * that depends on the [imageEntry] size.
     */
    private fun ZipFile.getImage(imageEntry: ZipEntry): Bitmap? {
        // Bounds first, then decode. A 3000 px plate is drawn a thousand pixels wide, and decoding
        // it at full size is the single most expensive thing this parser does — a book with sixty
        // of them spends most of its opening time right here, and then holds 300 MB of bitmap.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        getInputStream(imageEntry).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            bounds.outWidth / sample > MAX_IMAGE_EDGE ||
            bounds.outHeight / sample > MAX_IMAGE_EDGE
        ) {
            sample *= 2
        }

        return getInputStream(imageEntry).use { inputStream ->
            BitmapFactory.decodeStream(
                inputStream,
                null,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = sample
                }
            )
        }
    }
}

/**
 * Replaces every run of newlines in the text under this element, in place.
 *
 * Walking text nodes is what `html(String)` used to do the expensive way: it serialized the subtree
 * and parsed it back, so a chapter's paragraphs each cost two HTML parses.
 */
private fun Element.collapseNewlines(replacement: String) {
    val pending = ArrayDeque<Node>()
    pending.addLast(this)
    while (pending.isNotEmpty()) {
        when (val node = pending.removeLast()) {
            is TextNode -> node.text(node.text().replace(NEWLINE_RUN, replacement))
            is Element -> node.childNodes().forEach { pending.addLast(it) }
            else -> Unit
        }
    }
}