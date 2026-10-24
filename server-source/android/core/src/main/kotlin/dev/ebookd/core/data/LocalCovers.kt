package dev.ebookd.core.data

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Covers the reader picked on *this* device, as files.
 *
 * Deliberately not a Room table. The set of overrides is exactly "what is in this directory", so a
 * table would be a second answer to a question the filesystem already answers — and adding one would
 * bump the schema, whose only migration today is destructive, which would cost the offline list and
 * every reading position on the phone to remember a list of files.
 *
 * The in-memory set exists so the shelf does not `stat()` a directory per card during composition;
 * it is re-read around each change and once at startup, which is the only time the I/O happens.
 */
@Singleton
class LocalCovers @Inject constructor(
    private val dir: File,
) {

    private val _overrides = MutableStateFlow<Set<String>>(emptySet())

    /** Book ids with a local cover. The shelf and the detail page draw from this. */
    val overrides: StateFlow<Set<String>> = _overrides.asStateFlow()

    /** The file to draw instead of the server's URL, or null when this device has no override. */
    fun fileFor(bookId: String): File? =
        if (bookId in _overrides.value) File(dir, fileName(bookId)).takeIf { it.exists() } else null

    /** Re-reads the directory. Cheap, and called only at startup and around a write. */
    fun refresh() {
        _overrides.value = dir.list()
            ?.filter { it.endsWith(SUFFIX) }
            ?.map { it.removeSuffix(SUFFIX) }
            ?.toSet()
            .orEmpty()
    }

    suspend fun write(
        bookId: String,
        bytes: ByteArray,
    ): File {
        dir.mkdirs()
        val target = File(dir, fileName(bookId))
        // Written through a sibling then renamed, so a process killed mid-write cannot leave a
        // half-file that the next launch would happily draw as a cover.
        val temp = File(dir, "${fileName(bookId)}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
        refresh()
        return target
    }

    suspend fun clear(bookId: String) {
        File(dir, fileName(bookId)).delete()
        File(dir, "${fileName(bookId)}.tmp").delete()
        refresh()
    }

    /**
     * The id is server-issued, and this is a path.
     *
     * A ULID is `[0-9A-Za-z]` only, so anything else — a slash, a dot-dot — is not an id, and the
     * cost of refusing it here is one line against the cost of writing outside the directory.
     */
    private fun fileName(bookId: String): String {
        require(ID.matches(bookId)) { "not a book id: $bookId" }
        return "$bookId$SUFFIX"
    }

    private companion object {
        const val SUFFIX = ".img"
        val ID = Regex("[0-9A-Za-z_-]{1,64}")
    }
}
