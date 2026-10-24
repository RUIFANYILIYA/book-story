/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.reader

/**
 * Page breaks are worked out a chapter at a time, so only the chapters that were
 * actually read are ever measured. Keeping those around means going back a page,
 * reopening a chapter or coming back from the menu costs nothing.
 *
 * The entries belong to one book at one set of reading settings; changing any of
 * them starts a new session and drops what no longer applies.
 */
internal object ReaderPageCache {

    private const val MAX_ENTRIES = 64

    private val entries = object : LinkedHashMap<String, List<ReaderPage>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ReaderPage>>?) =
            size > MAX_ENTRIES
    }

    private var session: String = ""

    @Synchronized
    fun sessionKey(bookId: Int, textSize: Int, config: ReaderPaginationConfig): String =
        "$bookId:$textSize:${config.hashCode()}"

    @Synchronized
    fun startSession(sessionKey: String) {
        if (sessionKey == session) return
        session = sessionKey
        entries.keys.retainAll { it.startsWith("$sessionKey:") }
    }

    @Synchronized
    fun key(segment: ReaderSegment): String = "$session:${segment.index}"

    @Synchronized
    fun get(key: String): List<ReaderPage>? = entries[key]

    @Synchronized
    fun put(key: String, pages: List<ReaderPage>) {
        if (session.isEmpty() || !key.startsWith("$session:")) return
        entries[key] = pages
    }
}
