package dev.ebookd.engine.core

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Splitting a plain-text book into chapters.
 *
 * This is the hardest heuristic in the app and the one users notice most, because the failure mode
 * is not a crash: it is a table of contents that contains `2. 然后他笑了` and a book where chapter
 * three is missing. Two properties matter more than cleverness.
 *
 * It must be a *pure function* of `(text, options, algorithmVersion)`. The result is cached and the
 * character offsets in it become the reader's only stable anchors, so a result that varied between
 * runs would silently move every saved position.
 *
 * It must prefer missing a heading to inventing one. A missed chapter means the table of contents
 * is coarse; an invented one puts a paragraph of prose in the navigation where the reader will tap
 * it. The scoring in pass 2 and the cross-checks in pass 3 exist to enforce that preference, and
 * `SPLIT_ALGORITHM_VERSION` is bumped whenever that balance changes, because a cached split from an
 * older version has to be discarded rather than trusted.
 */

const val SPLIT_ALGORITHM_VERSION = 1

@Serializable
enum class SplitMode {
    /** Detect headings; fall back to length when the text has none. */
    AUTO,

    /** A chapter per blank-line-separated block. Predictable, and what a user picks when AUTO guessed wrong. */
    BLANK_LINE,

    /** The whole file is one chapter. */
    SINGLE,

    /** The user supplied a regex; only that decides. */
    CUSTOM,
}

@Serializable
data class SplitOptions(
    val mode: SplitMode = SplitMode.AUTO,
    val customPattern: String? = null,
    /**
     * Below this, a heading is treated as having no body and folded away.
     *
     * Kept small on purpose. Its job is to drop a heading followed by nothing — a copyright line, a
     * stray subsection marker — not to enforce a chapter length. A larger threshold looks safer and
     * quietly destroys legitimate books: a collection of short verses, a flash-fiction anthology or
     * a cookbook of one-paragraph recipes has real chapters of a few dozen characters, and folding
     * those into their predecessor leaves the reader with a table of contents that skips them.
     */
    val minChapterChars: Int = 40,
    /** Above this, a chapter is subdivided even if it is the only one. */
    val maxChapterChars: Int = 60_000,
)

@Serializable
data class ChapterMark(
    val title: String,
    val level: Int,
    val startChar: Int,
    /** How the mark was decided, so the UI can explain a surprising table of contents. */
    val source: MarkSource,
)

@Serializable
enum class MarkSource { HEADING, TOC_HINT, BLANK_LINE, LENGTH, CUSTOM, WHOLE_DOCUMENT }

@Serializable
data class SplitResult(
    val marks: List<ChapterMark>,
    val mode: SplitMode,
    val algorithmVersion: Int = SPLIT_ALGORITHM_VERSION,
    val warnings: List<String> = emptyList(),
) {
    /**
     * A hash of the inputs that produced this result. Re-opening a book compares it with the stored
     * value and re-splits only when it differs, which is what makes "重新断章" cheap and also what
     * stops an algorithm upgrade from being applied silently to a position the reader saved.
     */
    fun optsHash(options: SplitOptions): String =
        "${options.mode}:${options.minChapterChars}:${options.maxChapterChars}:${options.customPattern ?: ""}:$algorithmVersion"
}

object TxtChapterSplitter {

    /** Heading families, tried in order. The first that matches decides the level. */
    private val CN_VOLUME = Regex(
        "^\\s*(?:第\\s*[0-9０-９零〇一二三四五六七八九十百千万两]+\\s*[卷部辑篇]|上卷|中卷|下卷|全本)\\s*[:：、·.\\-—\\s]?.{0,20}$",
    )
    private val CN_CHAPTER = Regex(
        "^\\s*(?:第\\s*[0-9０-９零〇一二三四五六七八九十百千万两]+\\s*[章回节话出幕折]|序章|序言|自序|前言|引子|楔子|绪论|导论|导读|尾声|终章|后记|跋|番外|附录|致谢|参考文献|版权页|大结局|结局|全书完)\\s*[:：、·.\\-—\\s]?.{0,30}$",
    )
    private val EN_HEADING = Regex(
        "^\\s*(?:Chapter\\s+\\d+[a-z]?|Book\\s+\\d+|Part\\s+\\w+|Volume\\s+\\w+|Canto\\s+\\d+|Act\\s+\\w+|Scene\\s+\\d+|Lesson\\s+\\d+|Prologue|Preface|Foreword|Introduction|Epilogue|Afterword|Appendix|Conclusion|Acknowledg(?:e)?ments|Copyright)\\b[\\s.:·#-]{0,2}.{0,40}$",
        RegexOption.IGNORE_CASE,
    )
    private val NUM_HEADING = Regex("^\\s*(?:[0-9]{1,4}|[一二三四五六七八九十百零]{1,4})\\s*[、.．:：\\-—)\\]]\\s*\\S{1,30}$")
    private val BRACKET_NUM_HEADING = Regex("^\\s*[（(【\\[<]\\s*(?:[0-9]{1,4}|[一二三四五六七八九十]{1,4})\\s*[)）】\\]>]\\s*\\S{1,30}$")
    private val TOC_TITLE = Regex("^\\s*(?:目\\s*录|Contents|Table of Contents)\\s*$", RegexOption.IGNORE_CASE)

    /**
     * Compiled once, not per call.
     *
     * `Regex(...)` in Kotlin compiles the pattern every time it is constructed, and these two used
     * to be built inside loops — one per candidate chapter, one per title comparison. On a 10 MB
     * book that is a few thousand compilations of the same pattern, and it dominated the split:
     * the cost of the whole pass dropped by an order of magnitude when they were hoisted here.
     */
    private val NUMBERED_TITLE = Regex("第\\s*([0-9０-９]+|[零〇一二三四五六七八九十百千两]+)\\s*[章回节]")

    private val TITLE_NOISE = Regex("[\\s　:：、.·\\-—【】\\[\\]()（）]+")

    private val TOC_LEADER_DOTS = Regex("[.·\\s]{2,}\\d{1,5}\\s*$")

    /**
     * Lines that are page furniture rather than content: a bare number, `第 12 页`, a row of dashes.
     * They are removed before splitting because a page number on its own line satisfies the numeric
     * heading pattern and would otherwise become a chapter called "12".
     */
    private val PAGE_FURNITURE = listOf(
        Regex("^\\s*[-–—\\s]*(?:\\d{1,5}|[IVXLCDM]{1,7})[-–—\\s]*$"),
        Regex("^\\s*第\\s*\\d+\\s*页\\s*$"),
        Regex("^\\s*[-=*~_]{3,}\\s*$"),
    )

    private val SENTENCE_END = charArrayOf('。', '！', '？', '.', '!', '?', '…', '”', '』', '）', ')', '"', '\'')

    /**
     * The entry point. Deterministic: the same inputs always give the same marks.
     */
    fun split(text: String, options: SplitOptions = SplitOptions()): SplitResult {
        if (text.isEmpty()) {
            return SplitResult(listOf(ChapterMark("全书", 1, 0, MarkSource.WHOLE_DOCUMENT)), options.mode)
        }
        return when (options.mode) {
            SplitMode.SINGLE -> SplitResult(
                listOf(ChapterMark("全书", 1, 0, MarkSource.WHOLE_DOCUMENT)),
                options.mode,
            )
            SplitMode.BLANK_LINE -> SplitResult(byBlankLine(text), options.mode)
            SplitMode.CUSTOM -> splitByCustom(text, options)
            SplitMode.AUTO -> splitAuto(text, options)
        }
    }

    // ------------------------------------------------------------------ AUTO

    private fun splitAuto(text: String, options: SplitOptions): SplitResult {
        val warnings = mutableListOf<String>()

        val lines = text.split('\n')
        val offsets = lineOffsets(lines)

        val tocRegion = detectTocRegion(lines)
        val hints = tocRegion?.let { parseTocHints(lines, it) } ?: emptyList()
        if (tocRegion != null) warnings += "检测到目录区（第 ${tocRegion.first + 1}–${tocRegion.last + 1} 行），已从正文中排除"

        val candidates = mutableListOf<Candidate>()
        for (i in lines.indices) {
            if (tocRegion != null && i in tocRegion) continue
            val raw = lines[i]
            if (raw.isEmpty()) continue
            // Scanning for the non-space bounds instead of `trim()` matters at this scale: a
            // 10 MB book is a few hundred thousand lines, and all but a few thousand of them are
            // prose that only needs its length checked. Allocating a trimmed copy of every prose
            // line costs tens of milliseconds on its own.
            val first = firstNonSpace(raw)
            if (first < 0) continue
            val last = lastNonSpace(raw)
            if (last - first + 1 > 60) continue
            // Heading detection first: it is a single-character test for almost every prose line,
            // while the page-furniture patterns are three regular expressions each.
            val level = headingLevel(raw) ?: continue
            val line = if (first == 0 && last == raw.length - 1) raw else raw.substring(first, last + 1)
            if (PAGE_FURNITURE.any { it.matches(line) }) continue
            // A heading almost never ends in sentence punctuation; a sentence that merely starts
            // with 第三章 does. This single guard removes most of the false positives.
            if (line.last() in SENTENCE_END && !line.endsWith("章") && !line.endsWith("节")) continue
            candidates += Candidate(i, line, level, scoreLine(lines, i, line, hints))
        }

        val accepted = candidates.filter { it.score >= SCORE_THRESHOLD }
        val validated = crossValidate(accepted, warnings)

        val marks = if (validated.isEmpty()) {
            warnings += "未识别到章节标题，将按长度切分"
            lengthFallback(text, options)
        } else {
            validated.map { candidate ->
                ChapterMark(
                    title = candidate.title,
                    level = candidate.level,
                    startChar = offsets[candidate.lineIndex],
                    source = if (hints.any { similar(it.title, candidate.title) }) MarkSource.TOC_HINT else MarkSource.HEADING,
                )
            }
        }

        val merged = mergeShortChapters(marks, text.length, options, warnings)
        val subdivided = subdivideLongChapters(merged, text.length, options, warnings)
        return SplitResult(subdivided, options.mode, warnings = warnings)
    }

    private data class Candidate(
        val lineIndex: Int,
        val title: String,
        val level: Int,
        val score: Float,
    )

    /** Weights, and why each exists. A heading is something the *surroundings* agree is a heading. */
    private const val SCORE_THRESHOLD = 2.5f

    private fun scoreLine(lines: List<String>, index: Int, line: String, hints: List<ChapterMark>): Float {
        var score = 1f
        val previous = lines.getOrNull(index - 1)?.trim().orEmpty()
        val next = lines.getOrNull(index + 1)?.trim().orEmpty()

        if (previous.isEmpty()) score += 2f
        if (next.isEmpty()) score += 1.5f
        if (line.length <= 30 && line.none { it in SENTENCE_END }) score += 1f
        if (previous.isNotEmpty() && previous.last() in SENTENCE_END) score += 1f
        // A TOC in the file is strong evidence: the author told us what the chapters are called.
        if (hints.any { similar(it.title, line) }) score += 3f
        return score
    }

    /**
     * Rejects a whole numbering family that does not behave like numbering.
     *
     * The check that earns its keep: `第N章` marks whose N does not mostly increase by one are not
     * chapters. Without this, a text full of "他想起第三章说过的话" produces a plausible-looking but
     * entirely fictional table of contents, and the reader has no way to tell it is wrong.
     */
    private fun crossValidate(candidates: List<Candidate>, warnings: MutableList<String>): List<Candidate> {
        val numbered = candidates.mapNotNull { candidate ->
            val match = NUMBERED_TITLE.find(candidate.title)
            val value = match?.groupValues?.get(1)?.let { parseChapterNumber(it) }
            if (value != null && value > 0) candidate to value else null
        }
        if (numbered.size >= 3) {
            val sequence = numbered.map { it.second }
            val increasing = sequence.zipWithNext().count { (a, b) -> b > a }
            val consecutive = sequence.zipWithNext().count { (a, b) -> b == a + 1 }
            val monotoneEnough = increasing.toFloat() / (sequence.size - 1) >= 0.7f
            val consecutiveEnough = consecutive.toFloat() / (sequence.size - 1) >= 0.5f
            if (!monotoneEnough || !consecutiveEnough) {
                warnings += "章号不连续（${sequence.take(8).joinToString(",")}），已忽略编号标题以避免误判"
                val rejected = numbered.map { it.first }.toSet()
                return candidates.filterNot { it in rejected }
            }
        }

        // Merge duplicates that appear within a few lines of each other: a running header repeated
        // on each page looks exactly like a chapter heading every few lines.
        //
        // Equality here is *exact*, not fuzzy. Fuzzy matching belongs to the contents-block
        // comparison, where a producer may have varied the punctuation; using it for dedupe made
        // `第一章` and `第二章` the same title (one character apart) and silently deleted every
        // chapter but the first, which is how a whole book ends up with a single-entry table of
        // contents that looks deliberate.
        val deduped = mutableListOf<Candidate>()
        for (candidate in candidates) {
            val previous = deduped.lastOrNull()
            if (previous != null && sameTitle(previous.title, candidate.title) &&
                candidate.lineIndex - previous.lineIndex <= 20
            ) {
                continue
            }
            deduped += candidate
        }
        return deduped
    }

    /**
     * Chapter numbers arrive in both scripts — `第3章` and `第三章` are equally common in Chinese
     * texts, and a monotonicity check that only understood digits would skip the whole
     * native-numeral family, which is precisely the family where a mangled sequence is most likely
     * (OCR, concatenated files, hand-typed tables of contents).
     *
     * Only the range chapters actually use is handled: 一 to a few hundred, including 零/〇 inside a
     * number. Anything larger or malformed returns null and is excluded from the check rather than
     * guessed at.
     */
    internal fun parseChapterNumber(raw: String): Int? {
        val digits = raw.filter { !it.isWhitespace() }
        if (digits.isEmpty()) return null
        // Full-width digits are folded so both ASCII and 全角 forms work.
        val ascii = digits.map { if (it in '０'..'９') ('0' + (it - '０')) else it }.joinToString("")
        if (ascii.all { it.isDigit() }) return ascii.toIntOrNull()?.takeIf { it > 0 }

        val units = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        val scales = mapOf('十' to 10, '百' to 100, '千' to 1000)

        var total = 0
        var current = 0
        var sawAnything = false
        for (char in ascii) {
            when {
                units.containsKey(char) -> {
                    current = units.getValue(char)
                    sawAnything = true
                }
                scales.containsKey(char) -> {
                    val scale = scales.getValue(char)
                    // `十二` means 12 with an implied leading one; `二十` means 20 with no multiplicand.
                    total += (if (current == 0) 1 else current) * scale
                    current = 0
                    sawAnything = true
                }
                else -> return null
            }
        }
        val result = total + current
        return if (sawAnything && result > 0) result else null
    }

    private fun sameTitle(a: String, b: String): Boolean = normalizeTitle(a) == normalizeTitle(b)

    /**
     * The first characters any heading family can start with.
     *
     * This exists for speed, and the speed is a feature: the splitter looks at every line of the
     * file, and a 10 MB novel is hundreds of thousands of lines, nearly all of them prose. Running
     * eight regular expressions against each one costs hundreds of milliseconds at open time;
     * checking one character first costs almost nothing, and a line of prose contains none of
     * these characters at its start. Semantics are unchanged — every family's pattern begins with
     * one of these characters, so a line that fails the gate could not have matched anyway.
     */
    private const val CN_STARTERS = "第上中下全序自前引楔绪导尾终后跋番附致参版大结"
    private const val NUM_STARTERS = "0123456789０１２３４５６７８９一二三四五六七八九十百零两"

    internal fun headingLevel(line: String): Int? {
        var index = 0
        while (index < line.length && line[index] <= ' ') index += 1
        if (index >= line.length) return null
        val first = line[index]
        return when {
            first in CN_STARTERS ->
                when {
                    CN_VOLUME.matches(line) -> 0
                    CN_CHAPTER.matches(line) -> 1
                    else -> null
                }
            first in NUM_STARTERS -> if (NUM_HEADING.matches(line)) 2 else null
            first == '（' || first == '(' || first == '【' || first == '[' || first == '<' ->
                if (BRACKET_NUM_HEADING.matches(line)) 3 else null
            first.code < 0x80 && first.isLetter() -> if (EN_HEADING.matches(line)) 1 else null
            else -> null
        }
    }

    // ------------------------------------------------------------------ TOC

    /**
     * A contents block near the top of the file.
     *
     * The density check is what makes this safe. Five heading-like lines in the first 250 lines is
     * also exactly what a normal book looks like at its beginning, so counting headings alone
     * classified the opening chapters as a table of contents and cut them out of the body — leaving
     * a reader with a book that starts at chapter six. A real contents list has almost no text
     * *between* its lines, so that is what is measured.
     */
    private fun detectTocRegion(lines: List<String>): IntRange? {
        val window = lines.take(250)
        val headerAt = window.indexOfFirst { TOC_TITLE.matches(it.trim()) }
        if (headerAt >= 0) return tocRangeFrom(lines, headerAt)

        val headingIndexes = window.withIndex()
            .filter { (_, line) -> line.trim().length in 2..40 && headingLevel(line.trim()) != null }
            .map { it.index }
        if (headingIndexes.size < 5) return null

        // Average non-blank body between consecutive headings: a contents list has a few characters
        // (the title itself), a book has paragraphs.
        val gaps = headingIndexes.zipWithNext().map { (a, b) ->
            lines.subList(a + 1, b).sumOf { it.trim().length }
        }
        val averageGap = if (gaps.isEmpty()) 0.0 else gaps.average()
        if (averageGap > TOC_DENSITY_LIMIT_CHARS) return null
        return tocRangeFrom(lines, headingIndexes.first())
    }

    /** Non-blank characters allowed between two contents lines before it is prose instead. */
    private const val TOC_DENSITY_LIMIT_CHARS = 40.0

    private fun tocRangeFrom(lines: List<String>, from: Int): IntRange? {
        var end = from
        var blanks = 0
        var index = from
        while (index < lines.size && index < from + 300) {
            val line = lines[index].trim()
            if (line.isEmpty()) {
                blanks += 1
                // Two blank lines end a table of contents; three would swallow the first chapter.
                if (blanks >= 2 && end > from) break
            } else {
                blanks = 0
                end = index
            }
            index += 1
        }
        return if (end > from) from..end else null
    }

    private fun parseTocHints(lines: List<String>, region: IntRange): List<ChapterMark> =
        region.mapNotNull { index ->
            val line = lines[index].trim()
            if (line.isEmpty()) return@mapNotNull null
            // Strip a trailing page number and leader dots: "第一章 风雪夜 ....... 12".
            val title = line.replace(TOC_LEADER_DOTS, "").trim()
            if (title.isEmpty()) return@mapNotNull null
            val level = headingLevel(title) ?: return@mapNotNull null
            ChapterMark(title, level, 0, MarkSource.TOC_HINT)
        }

    // ------------------------------------------------------------------ modes

    private fun byBlankLine(text: String): List<ChapterMark> {
        val marks = mutableListOf<ChapterMark>()
        var offset = 0
        var ordinal = 0
        for (block in text.split(Regex("\n\\s*\n"))) {
            if (block.isNotBlank()) {
                ordinal += 1
                val title = block.trim().lineSequence().first().take(40)
                marks += ChapterMark(title, 1, offset, MarkSource.BLANK_LINE)
            }
            offset += block.length + 2
        }
        return marks
    }

    private fun splitByCustom(text: String, options: SplitOptions): SplitResult {
        val pattern = options.customPattern
        if (pattern.isNullOrBlank()) {
            return SplitResult(
                listOf(ChapterMark("全书", 1, 0, MarkSource.WHOLE_DOCUMENT)),
                options.mode,
                warnings = listOf("自定义正则为空，未做切分"),
            )
        }
        val regex = runCatching { Regex(pattern, RegexOption.MULTILINE) }.getOrNull()
            ?: return SplitResult(
                listOf(ChapterMark("全书", 1, 0, MarkSource.WHOLE_DOCUMENT)),
                options.mode,
                warnings = listOf("自定义正则无效：$pattern"),
            )

        val marks = regex.findAll(text)
            .map { match ->
                val start = match.range.first
                val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                ChapterMark(text.substring(start, lineEnd).trim().take(60), 1, start, MarkSource.CUSTOM)
            }
            .toList()
        return if (marks.isEmpty()) {
            SplitResult(
                listOf(ChapterMark("全书", 1, 0, MarkSource.WHOLE_DOCUMENT)),
                options.mode,
                warnings = listOf("自定义正则没有匹配到任何位置"),
            )
        } else {
            SplitResult(marks, options.mode)
        }
    }

    // ------------------------------------------------------------------ post-processing

    private fun mergeShortChapters(
        marks: List<ChapterMark>,
        textLength: Int,
        options: SplitOptions,
        warnings: MutableList<String>,
    ): List<ChapterMark> {
        if (marks.size <= 1) return marks
        val merged = mutableListOf<ChapterMark>()
        var dropped = 0
        for ((index, mark) in marks.withIndex()) {
            val end = marks.getOrNull(index + 1)?.startChar ?: textLength
            val length = end - mark.startChar
            // A "chapter" of a hundred characters is a stray line, not a chapter; folding it into
            // the previous one keeps the navigation honest.
            if (length < options.minChapterChars && merged.isNotEmpty()) {
                dropped += 1
                continue
            }
            merged += mark
        }
        if (dropped > 0) warnings += "合并了 $dropped 个过短的标题"
        return if (merged.isEmpty()) marks else merged
    }

    private fun subdivideLongChapters(
        marks: List<ChapterMark>,
        textLength: Int,
        options: SplitOptions,
        warnings: MutableList<String>,
    ): List<ChapterMark> {
        val out = mutableListOf<ChapterMark>()
        var subdivided = 0
        for ((index, mark) in marks.withIndex()) {
            out += mark
            val end = marks.getOrNull(index + 1)?.startChar ?: textLength
            if (end - mark.startChar <= options.maxChapterChars) continue
            val parts = ((end - mark.startChar) / options.maxChapterChars) + 1
            val step = (end - mark.startChar) / parts
            for (part in 1 until parts) {
                subdivided += 1
                out += ChapterMark(
                    "${mark.title} · 续$part",
                    mark.level,
                    mark.startChar + part * step,
                    MarkSource.LENGTH,
                )
            }
        }
        if (subdivided > 0) warnings += "有 $subdivided 段因过长被自动细分"
        return out.sortedBy { it.startChar }
    }

    /**
     * Chapters for a text with no headings at all — a common case for scraped or converted books,
     * and the one where a reader most needs navigation. Cuts land on paragraph boundaries near an
     * even division rather than mid-sentence, because the fallback is supposed to be unobtrusive.
     */
    private fun lengthFallback(text: String, options: SplitOptions): List<ChapterMark> {
        val target = 20_000.coerceAtLeast(8_000).coerceAtMost(30_000)
        val parts = ((text.length / target) + 1).coerceAtLeast(1)
        if (parts <= 1) return listOf(ChapterMark("全文", 1, 0, MarkSource.LENGTH))

        val step = text.length / parts
        val marks = mutableListOf(ChapterMark("第 1 部分", 1, 0, MarkSource.LENGTH))
        for (part in 1 until parts) {
            val ideal = part * step
            val boundary = findParagraphBoundary(text, ideal)
            marks += ChapterMark("第 ${part + 1} 部分", 1, boundary, MarkSource.LENGTH)
        }
        return marks.distinctBy { it.startChar }
    }

    private fun findParagraphBoundary(text: String, near: Int): Int {
        val searchLimit = 400
        val forward = text.indexOf("\n\n", near)
        val backward = text.lastIndexOf("\n\n", near)
        val forwardDistance = if (forward < 0) Int.MAX_VALUE else forward - near
        val backwardDistance = if (backward < 0) Int.MAX_VALUE else near - backward
        return when {
            forwardDistance.coerceAtMost(backwardDistance) > searchLimit -> near
            forwardDistance <= backwardDistance -> forward + 2
            else -> backward + 2
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun lineOffsets(lines: List<String>): IntArray {
        val offsets = IntArray(lines.size)
        var offset = 0
        for (i in lines.indices) {
            offsets[i] = offset
            offset += lines[i].length + 1 // the newline that was split away
        }
        return offsets
    }

    /** Index of the first non-space character, or -1 — the allocation-free half of `trim()`. */
    private fun firstNonSpace(line: String): Int {
        var index = 0
        while (index < line.length && line[index] <= ' ') index += 1
        return if (index == line.length) -1 else index
    }

    /** Index of the last non-space character; callers know the line has one. */
    private fun lastNonSpace(line: String): Int {
        var index = line.length - 1
        while (index > 0 && line[index] <= ' ') index -= 1
        return index
    }

    /**
     * Strips everything that varies between a contents entry and the heading it refers to:
     * spacing, punctuation style, and the leader dots a contents line carries.
     */
    private fun normalizeTitle(value: String): String = value.replace(TITLE_NOISE, "")

    /**
     * Fuzzy comparison, used only to decide whether a chapter heading matches a *contents entry*.
     * Deliberately not used for duplicate suppression: see the comment in `crossValidate`.
     */
    internal fun similar(a: String, b: String): Boolean {
        val left = normalizeTitle(a)
        val right = normalizeTitle(b)
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true
        if (abs(left.length - right.length) > 2) return false
        return levenshtein(left, right) <= 2
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
