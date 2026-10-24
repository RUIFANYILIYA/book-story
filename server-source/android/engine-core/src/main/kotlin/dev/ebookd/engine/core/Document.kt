package dev.ebookd.engine.core

import kotlinx.serialization.Serializable

/**
 * The document model every format is parsed into.
 *
 * This is the seam that makes the reader format-agnostic: EPUB, TXT and anything added later all
 * produce `Chapter`/`Block` lists, and the paginator consumes only that. Nothing here refers to
 * Android, a file system, or a specific container — the module is pure JVM precisely so the
 * pagination and chapter-splitting logic can be tested without a device or an emulator.
 *
 * The model is deliberately *lossy about presentation*. Colors, fonts, sizes and spacing are not
 * part of it, because they are reader settings rather than properties of the book: a theme change
 * must not invalidate the parsed document, only the layout. That is also why `LayoutKey` exists
 * separately, and why changing the font size never re-parses anything.
 */

@Serializable
enum class BlockAlign { START, CENTER, JUSTIFY, END }

@Serializable
data class SpanStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    /** Relative size, for `font-size: 1.2em` and `<h2>`-ish emphasis inside a paragraph. */
    val scaleEm: Float = 1f,
    val linkHref: String? = null,
)

@Serializable
data class Span(
    val text: String,
    val style: SpanStyle = SpanStyle(),
)

/**
 * A run of content the paginator treats as one unit for line breaking.
 *
 * `firstLineIndentEm` is a property of the paragraph rather than of the theme because it is a
 * convention of the *text* — Chinese prose indents two characters, English does not — and a reader
 * setting that silently applied the wrong convention to half a library would be worse than no
 * setting at all.
 */
@Serializable
sealed interface Block {
    @Serializable
    data class Paragraph(
        val spans: List<Span>,
        val align: BlockAlign = BlockAlign.JUSTIFY,
        val firstLineIndentEm: Float = 2f,
        val marginBeforeEm: Float = 0f,
        val marginAfterEm: Float = 0.6f,
        /** Null means inherit the reader's line spacing; a value overrides it for this block. */
        val lineHeightMultiplier: Float? = null,
        /** Set on headings and captions: never leave this block alone at the foot of a page. */
        val keepWithNext: Boolean = false,
    ) : Block

    @Serializable
    data class Heading(
        val level: Int,
        val spans: List<Span>,
        val centered: Boolean = false,
    ) : Block

    @Serializable
    data class ImageBlock(
        val locator: String,
        val mime: String,
        val widthEm: Float? = null,
        val heightEm: Float? = null,
        val isCover: Boolean = false,
        val alt: String? = null,
    ) : Block

    @Serializable
    data class Quote(val blocks: List<Block>, val indentEm: Float = 1.5f) : Block

    @Serializable
    data class BulletList(val ordered: Boolean, val items: List<List<Block>>) : Block

    /** Preformatted text: line breaks are content, and it is never re-wrapped. */
    @Serializable
    data class Preformatted(val text: String) : Block

    @Serializable
    data object HorizontalRule : Block

    /** An explicit break from the source, which the paginator must honour even mid-page. */
    @Serializable
    data object PageBreak : Block

    /**
     * A link into the chapter's own footnotes. Kept as a block rather than a span because tapping
     * it opens something; a span alone would lose the target on the way through layout.
     */
    @Serializable
    data class FootnoteRef(val id: String, val label: String, val text: String?) : Block
}

/**
 * One chapter, with the character range it occupies in the document's normalized text.
 *
 * `charStart`/`charEnd` are the *only* reading-position anchors that survive a settings change or
 * a re-parse, which is why they are recorded here rather than derived later. A page number cannot
 * serve that purpose: it changes with the font size.
 */
@Serializable
data class Chapter(
    val index: Int,
    val title: String,
    val level: Int = 1,
    val blocks: List<Block> = emptyList(),
    val charStart: Int = 0,
    val charEnd: Int = 0,
    /** Container-relative resource path for EPUB; null for formats without one. */
    val href: String? = null,
) {
    val charCount: Int get() = (charEnd - charStart).coerceAtLeast(0)

    /**
     * Characters of display text in this chapter's blocks: the coordinate space the paginator works
     * in, and therefore what [Anchor.percent] must be divided by.
     *
     * This is deliberately separate from [charCount], which measures the chapter's slice of the
     * normalized document. Building blocks drops indent markers and blank lines, so the laid-out
     * text is shorter than the slice, and feeding one where the other belongs biases every saved
     * reading position — the bug a reader would report as "it says 40% but I'm at 45%".
     */
    val layoutCharCount: Int get() = blocks.sumOf { blockChars(it) }
}

private fun blockChars(block: Block): Int = when (block) {
    is Block.Paragraph -> block.spans.sumOf { it.text.length }
    is Block.Heading -> block.spans.sumOf { it.text.length }
    is Block.Preformatted -> block.text.length
    is Block.Quote -> block.blocks.sumOf { blockChars(it) }
    is Block.BulletList -> block.items.sumOf { item -> item.sumOf { blockChars(it) } }
    is Block.FootnoteRef -> block.text?.length ?: block.label.length
    else -> 0
}

@Serializable
data class TocEntry(
    val title: String,
    val chapterIndex: Int,
    val level: Int = 1,
)

@Serializable
enum class BookFormat { EPUB, TXT, PDF, CBZ, FB2, MOBI, AZW3, UNKNOWN }

/** Fixed-layout sources produce pages, not reflowed text; the reader branches on this. */
@Serializable
enum class PageSourceKind { REFLOWABLE, FIXED }

@Serializable
data class BookDoc(
    val format: BookFormat,
    val title: String,
    val author: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val toc: List<TocEntry> = emptyList(),
    val pageSource: PageSourceKind = PageSourceKind.REFLOWABLE,
    /** Rendering engine version, so a layout cache from an older build is discarded rather than trusted. */
    val layoutVersion: Int = 1,
) {
    val charTotal: Int get() = chapters.lastOrNull()?.charEnd ?: 0
}

/** A dense run of text with no styling, which is what the TXT path produces. */
fun plainChapter(index: Int, title: String, text: String, charStart: Int, indentEm: Float = 2f): Chapter {
    val paragraphs = text
        .split(Regex("\n\\s*\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { paragraph ->
            Block.Paragraph(
                spans = listOf(Span(paragraph.replace(Regex("\\s*\\n\\s*"), " "))),
                firstLineIndentEm = indentEm,
            )
        }
    return Chapter(
        index = index,
        title = title,
        level = 1,
        blocks = paragraphs,
        charStart = charStart,
        charEnd = charStart + text.length,
    )
}
