package dev.ebookd.engine.core

/**
 * Turns a normalized TXT and its chapter marks into the document model the paginator consumes.
 *
 * This is the seam between "where the chapters are" and "what a page is made of", and the one
 * judgement it makes is about indentation. The normalizer leaves a single U+3000 at the start of a
 * paragraph as a marker that the *author* indented it; here that marker is stripped and turned
 * into `firstLineIndentEm`. Two books that looked different in the file — one indented, one not —
 * therefore stay different on screen, instead of the reader applying a two-character indent to
 * text whose author deliberately left it flush.
 *
 * The chapter's `charStart`/`charEnd` are offsets into the normalized *document*; the blocks carry
 * display text with the markers and blank lines removed. They are not the same string, and they
 * are not meant to be: a reading position that has to survive re-parsing is stored as a percentage
 * of the chapter's characters, not as an offset into either.
 */
object TxtDocumentBuilder {

    /** Chinese prose indents two characters; that is what the marker means, not a setting. */
    private const val INDENT_EM = 2f

    /**
     * A chapter as a range, with no claim about how the range was found.
     *
     * The cached chapter list read back from disk is exactly this: title, level and two offsets.
     * Passing slices instead of a [SplitResult] means a re-opened book cannot be handed marks
     * whose `source` had to be invented, which matters because the source is what tells the UI
     * "these chapters were guessed by length, check them".
     */
    data class ChapterSlice(val title: String, val level: Int, val charStart: Int, val charEnd: Int)

    fun build(
        text: String,
        split: SplitResult,
        title: String,
        author: String? = null,
    ): BookDoc {
        val starts = split.marks.sortedBy { it.startChar }
        return build(
            text,
            slices = starts.mapIndexed { index, mark ->
                ChapterSlice(
                    title = mark.title,
                    level = mark.level,
                    charStart = mark.startChar,
                    charEnd = starts.getOrNull(index + 1)?.startChar ?: text.length,
                )
            },
            title = title,
            author = author,
        )
    }

    fun build(
        text: String,
        slices: List<ChapterSlice>,
        title: String,
        author: String? = null,
    ): BookDoc {
        val ordered = slices.sortedBy { it.charStart }.ifEmpty {
            listOf(ChapterSlice(title, 1, 0, text.length))
        }
        return BookDoc(
            format = BookFormat.TXT,
            title = title,
            author = author,
            chapters = ordered.mapIndexed { index, slice -> chapter(text, slice, index) },
            toc = ordered.mapIndexed { index, slice -> TocEntry(title = slice.title, chapterIndex = index, level = slice.level) },
            pageSource = PageSourceKind.REFLOWABLE,
        )
    }

    /**
     * One chapter, on demand.
     *
     * A 20 MB novel with 9 500 chapters needs the blocks of exactly one of them at a time. Building
     * the whole document up front cost 1.1 s of the 1.4 s a warm reopen took on a Pixel 7 emulator —
     * every paragraph object, span and substring of a book the reader will spend hours inside, made
     * before the first page can be drawn.
     */
    fun chapter(
        text: String,
        slice: ChapterSlice,
        index: Int,
    ): Chapter {
        val start = slice.charStart.coerceIn(0, text.length)
        val end = slice.charEnd.coerceIn(start, text.length)
        return Chapter(
            index = index,
            title = slice.title,
            level = slice.level.coerceAtLeast(1),
            blocks = blocksFor(text.substring(start, end), slice.title),
            charStart = start,
            charEnd = end,
        )
    }

    /**
     * One slice of the document, as blocks.
     *
     * The heading is only added when the title is genuinely the first line of the slice: a mark
     * produced by the length fallback has a generated name ("第 3 部分") that appears nowhere in
     * the text, and inventing a heading line for it would put a sentence in the page that the book
     * does not contain.
     */
    private fun blocksFor(slice: String, title: String): List<Block> {
        val hasTitleLine = title.isNotBlank() && (slice == title || slice.startsWith("$title\n"))
        val body = if (hasTitleLine) slice.substring(title.length).trimStart('\n') else slice

        val blocks = mutableListOf<Block>()
        if (hasTitleLine) {
            blocks += Block.Heading(level = 1, spans = listOf(Span(title)), centered = false)
        }
        body.split(Regex("\n[ \t]*\n")).forEach { paragraph ->
            // U+3000 counts as whitespace to `trim()`, so the indent marker has to be read before
            // any trimming happens. Doing it afterwards would leave indented and flush paragraphs
            // indistinguishable, which is the one thing the normalizer kept the marker for.
            val lead = paragraph.trimStart('\n', '\t', ' ')
            val indented = lead.startsWith(TextNormalizer.INDENT)
            val text = lead.trimStart(TextNormalizer.INDENT, ' ', '\t', '\n').trimEnd()
            if (text.isEmpty()) return@forEach
            blocks += Block.Paragraph(
                spans = listOf(Span(text)),
                firstLineIndentEm = if (indented) INDENT_EM else 0f,
            )
        }
        return blocks
    }
}
