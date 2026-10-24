/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.core.helpers

fun String.removeTrailingZero(): String {
    if (!this.contains('.'))
        return this
    return this
        .dropLastWhile { it == '0' }
        .dropLastWhile { it == '.' }
}

fun Double.removeDigits(digits: Int) = "%.${digits}f".format(this).replace(",", ".")

fun Float.calculateProgress(digits: Int): String {
    return (this * 100)
        .toDouble()
        .removeDigits(digits)
        .removeTrailingZero()
        .dropWhile { it == '-' }
}

fun Float?.coerceAndPreventNaN(): Float {
    if (this == null) return 0f
    if (isNaN()) return 0f
    return this.coerceIn(0f, 1f)
}

// Compiled once. These are called once per line of every chapter, and building the Regex inside
// the call meant compiling it a million times to open one novel.
private val MARKDOWN_MARKS = Regex("(_+)|(\\*+)")
private val MARKDOWN_MARKS_WITH_HEADING = Regex("(_+)|(\\*+)|(#+)")

fun String.clearMarkdown(): String {
    // Prose has none of these characters, and `indexOf` is a scan with no allocation where the
    // regex is a match plus a rebuilt string. Called per line of every chapter, that adds up.
    if (indexOf('_') < 0 && indexOf('*') < 0) return this
    return replace(MARKDOWN_MARKS, "")
}

fun String.clearAllMarkdown(): String {
    if (indexOf('_') < 0 && indexOf('*') < 0 && indexOf('#') < 0) return trim()
    return replace(MARKDOWN_MARKS_WITH_HEADING, "").trim()
}

fun String.containsVisibleText(): Boolean {
    return any { it.isVisibleCharacter() }
}

fun Char.isVisibleCharacter(): Boolean {
    return when (this.category) {
        CharCategory.UPPERCASE_LETTER,
        CharCategory.LOWERCASE_LETTER,
        CharCategory.TITLECASE_LETTER,
        CharCategory.MODIFIER_LETTER,
        CharCategory.OTHER_LETTER,
        CharCategory.DECIMAL_DIGIT_NUMBER,
        CharCategory.LETTER_NUMBER,
        CharCategory.OTHER_NUMBER,
        CharCategory.MATH_SYMBOL,
        CharCategory.CURRENCY_SYMBOL,
        CharCategory.OTHER_SYMBOL,
        CharCategory.INITIAL_QUOTE_PUNCTUATION,
        CharCategory.FINAL_QUOTE_PUNCTUATION,
        CharCategory.CONNECTOR_PUNCTUATION,
        CharCategory.DASH_PUNCTUATION,
        CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION,
        CharCategory.OTHER_PUNCTUATION -> true

        else -> false
    }
}