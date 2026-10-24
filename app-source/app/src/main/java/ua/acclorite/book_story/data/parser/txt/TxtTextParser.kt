/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.parser.txt

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import ua.acclorite.book_story.core.helpers.clearAllMarkdown
import ua.acclorite.book_story.data.model.file.CachedFile
import ua.acclorite.book_story.data.parser.MarkdownParser
import ua.acclorite.book_story.data.parser.TextParser
import ua.acclorite.book_story.data.settings.SettingsManager
import ua.acclorite.book_story.domain.model.reader.ReaderText
import ua.acclorite.book_story.presentation.reader.model.ReaderChapterSplitting
import javax.inject.Inject

private const val TXT_TAG = "TXT Parser"

/**
 * Chapter headings as they appear in a plain-text book: "第12章", "第十二节", "第三卷",
 * "楔子", "Chapter 4", "Part II" and the like. Whatever follows the heading is allowed
 * to carry the chapter's title, but only for a short tail, so that a sentence merely
 * mentioning a chapter is not mistaken for a heading.
 */
private val CHAPTER_HEADING = Regex(
    "(?:第\\s*[0-9０-９一二三四五六七八九十百千万零两〇]+\\s*[章节節回卷部篇集话話]" +
        "|序章|序言|序曲|楔子|前言|引子|后记|後記|尾声|尾聲|终章|終章|番外|外传|外傳|附录|附錄" +
        "|chapter\\s*[0-9ivxlcdm]+" +
        "|part\\s*[0-9ivxlcdm]+" +
        "|prologue|epilogue|foreword|afterword|interlude)" +
        ".{0,40}",
    RegexOption.IGNORE_CASE
)

class TxtTextParser @Inject constructor(
    private val markdownParser: MarkdownParser,
    private val settings: SettingsManager
) : TextParser {

    override suspend fun parse(cachedFile: CachedFile): List<ReaderText> {
        Log.i(TXT_TAG, "Started TXT parsing: ${cachedFile.name}.")

        return try {
            val readerText = mutableListOf<ReaderText>()
            var firstTextIndex = -1
            var firstTextLine: String? = null

            val splitChapters =
                settings.chapterSplitting.lastValue == ReaderChapterSplitting.AUTOMATIC

            withContext(Dispatchers.IO) {
                // The charset has to be settled before a single line is read: a GBK novel decoded
                // as UTF-8 parses without error and renders as noise.
                val encoding = cachedFile.openInputStream()?.use { TxtCharset.detect(it) }
                    ?: return@withContext

                cachedFile.openInputStream()?.use { raw ->
                    skipBytes(raw, encoding.bomBytes)

                    raw.bufferedReader(encoding.charset).use { reader ->
                        reader.forEachLine { line ->
                            if (line.isNotBlank()) {
                                when {
                                    line == "***" || line == "---" -> readerText.add(
                                        ReaderText.Separator
                                    )

                                    splitChapters && isChapterHeading(line) -> readerText.add(
                                        ReaderText.Chapter(
                                            title = line.clearAllMarkdown().trim(),
                                            nested = false
                                        )
                                    )

                                    else -> {
                                        if (
                                            firstTextIndex == -1 &&
                                            line.clearAllMarkdown().isNotBlank()
                                        ) {
                                            firstTextIndex = readerText.size
                                            firstTextLine = line
                                        }

                                        readerText.add(
                                            ReaderText.Text(
                                                line = markdownParser.parse(line)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            yield()

            // Text sitting before the first chapter would have no chapter to belong to, so
            // the opening line doubles as the title of one. Without chapter headings at all
            // this is what turns a TXT file into a readable single-chapter book.
            if (readerText.firstOrNull() !is ReaderText.Chapter && firstTextIndex >= 0) {
                val title = firstTextLine?.clearAllMarkdown()?.trim()
                if (!title.isNullOrBlank()) {
                    readerText.removeAt(firstTextIndex)
                    readerText.add(0, ReaderText.Chapter(title = title, nested = false))
                }
            }

            if (
                readerText.filterIsInstance<ReaderText.Text>().isEmpty() ||
                readerText.filterIsInstance<ReaderText.Chapter>().isEmpty()
            ) {
                Log.e(TXT_TAG, "Could not extract text from TXT.")
                return emptyList()
            }

            Log.i(TXT_TAG, "Successfully finished TXT parsing.")
            readerText
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun isChapterHeading(line: String): Boolean {
        return CHAPTER_HEADING.matches(line.trim())
    }

    private fun skipBytes(input: java.io.InputStream, count: Int) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left.toLong())
            if (skipped <= 0) return
            left -= skipped.toInt()
        }
    }
}
