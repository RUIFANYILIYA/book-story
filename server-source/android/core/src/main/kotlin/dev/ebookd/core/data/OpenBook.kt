package dev.ebookd.core.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The book that is on the glass right now, if any.
 *
 * Sync is a background process and the reader is a foreground one, and they must not fight over the
 * same row. A pull that adopted another device's position while a book was open would look like
 * nothing at all — until the reader closed the book and opened it again, and found themselves
 * wherever the other device had been, having read none of it. The reader owns its book while it is
 * open; the sync takes it back the moment the screen goes away.
 */
@Singleton
class OpenBook @Inject constructor() {

    @Volatile
    var bookId: String? = null
}
