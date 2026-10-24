package dev.ebookd.core.data

import dev.ebookd.core.db.ReadingPositionEntity
import dev.ebookd.core.net.SyncDeltaItemDto
import dev.ebookd.core.net.SyncPositionDto
import kotlinx.serialization.json.Json

/**
 * A position as it arrived from the server: the anchor (when the sender had one), the percentage,
 * and the revision that makes it orderable.
 */
data class RemotePosition(
    val rev: Long,
    val percent: Double?,
    val chapterIndex: Int?,
    val charOffset: Int?,
    val fileId: String?,
    val sha256: String?,
)

/**
 * Which of two positions for the same book stands.
 *
 * The plan settles the authority: reading position belongs to the *client*, and devices merge by
 * "whichever is further along". The alternatives are worse in ways worth naming. Trusting the
 * device clock means a phone that is four minutes ahead silently wins every conflict, and one that
 * is behind loses every one — including its own newer position. Trusting arrival order means the
 * last device to come online wins, regardless of who actually read more.
 *
 * Percentage is the only comparison that survives both devices having read the same book in
 * different file versions: a character offset into a replaced file points at text that is not
 * there, while "62 % of the way through" means the same thing on both. So the anchor rides along
 * when the server's copy is adopted, but the *decision* is made on percentage alone.
 *
 * This is deliberately a pure function over two values rather than part of the repository: it is
 * the one piece of the sync that is a judgement rather than plumbing, and it is unit-tested.
 */
object ProgressMerge {

    /**
     * The row to store, or null when the local one stands.
     *
     * [local] is what this device last read; [remote] is what the server has, with [remote]'s own
     * rev. Our own writes come back through the delta, which is why an echo — a revision no newer
     * than the one this row was built from — is not a merge at all.
     */
    fun merge(
        local: ReadingPositionEntity?,
        remote: RemotePosition,
        bookId: String,
        now: Long = System.currentTimeMillis(),
    ): ReadingPositionEntity? {
        if (remote.rev <= (local?.baseRev ?: 0L)) return null
        if (local == null) return adopt(bookId, remote, now)
        // Nothing to compare with: a percentage is the only currency this decision takes, and a
        // sender that had none (a client that pushed a status without a position) cannot outrank
        // a place the reader is actually in.
        val percent = remote.percent ?: return null
        return if (percent > local.percent) adopt(bookId, remote, now) else null
    }

    private fun adopt(
        bookId: String,
        remote: RemotePosition,
        now: Long,
    ) = ReadingPositionEntity(
        bookId = bookId,
        chapterIndex = remote.chapterIndex ?: 0,
        charOffset = remote.charOffset ?: 0,
        updatedAt = now,
        percent = remote.percent ?: 0.0,
        fileId = remote.fileId,
        sha256 = remote.sha256,
        baseRev = remote.rev,
    )

    /**
     * What to store when [merge] keeps the local position: the same place, on the revision the
     * server has now been seen to have. Null when there is nothing to record.
     *
     * Without this the device is stuck, and the way it gets stuck is worth spelling out. The local
     * value wins a merge — it is further along — so nothing is adopted, and the row keeps the
     * revision it was written against. The server has since moved past that revision, so every
     * later push is refused as a conflict, and the pull that follows cannot repair it either: the
     * change it would have to merge is the one that made the local value win, and it is already
     * behind the cursor. The result is a book whose progress this device can never upload again.
     *
     * Rebasing is honest rather than a trick: by the time this is called, the content of [remote]'s
     * revision has been read, compared, and found to be the older position.
     */
    fun rebase(local: ReadingPositionEntity, remote: RemotePosition): ReadingPositionEntity? =
        if (remote.rev > local.baseRev) local.copy(baseRev = remote.rev) else null

    /**
     * Reads a delta row's anchor document, or null when it does not carry a position.
     *
     * The document is a JSON string the server never parses; anything unreadable inside it is
     * treated as "no anchor" rather than as an error, because the percentage beside it is still
     * worth merging and a client should not lose a position to a malformed string it cannot use.
     */
    fun remotePosition(item: SyncDeltaItemDto, json: Json): RemotePosition? {
        if (item.bookId == null) return null
        val position = item.positionJson?.let { text ->
            runCatching { json.decodeFromString<SyncPositionDto>(text) }.getOrNull()
        }
        return RemotePosition(
            rev = item.rev,
            percent = item.percent,
            chapterIndex = position?.chapterIndex,
            charOffset = position?.charOffset,
            fileId = position?.fileId ?: item.positionFileId,
            sha256 = position?.sha256,
        )
    }
}
