package dev.ebookd.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Where the reader stopped, in the only coordinate that survives a re-layout.
 *
 * `charOffset` is an offset into one chapter's *laid-out* text, not a page number and not an offset
 * into the file. A page number is a property of the current font size and window shape, and a file
 * offset belongs to a document the paginator never sees; either would put the reader on the wrong
 * page the first time they turn a knob. A chapter plus a character offset resolves to a page
 * against whatever layout is actually in front of them, which is what makes "change the font size
 * three times and still be where you were" a consequence of the schema rather than a feature.
 *
 * Reader *settings* are absent on purpose: they are global, and a per-book font size would make a
 * book open differently after a restart for a reason nobody chose.
 *
 * The three fields after `updatedAt` exist for syncing, and each answers a question the anchor
 * cannot. [percent] is the whole-book percentage — the only number that means the same thing on
 * another device or against another version of the file, which is why the server's merge rule is
 * built on it. [fileId] and [sha256] say *which* file the anchor was measured against: a position
 * saved against a replaced file still has a percentage worth honouring, but its character offset
 * points into text that no longer exists. [baseRev] is the server revision this row came from, so a
 * push can tell "nobody has written since" from "someone else got there first".
 */
@Entity(tableName = "reading_position")
data class ReadingPositionEntity(
    @PrimaryKey val bookId: String,
    val chapterIndex: Int,
    val charOffset: Int,
    val updatedAt: Long,
    val percent: Double = 0.0,
    val fileId: String? = null,
    val sha256: String? = null,
    val baseRev: Long = 0,
)

@Dao
interface ReadingPositionDao {

    @Query("SELECT * FROM reading_position WHERE bookId = :bookId")
    suspend fun find(bookId: String): ReadingPositionEntity?

    /** Every position this device holds, for the shelf's overlay — see [LocalPosition]. */
    @Query("SELECT bookId, percent FROM reading_position")
    fun observePercents(): Flow<List<LocalPosition>>

    /** One book's percentage, for the surfaces that show a single book's progress. */
    @Query("SELECT percent FROM reading_position WHERE bookId = :bookId")
    fun observePercent(bookId: String): Flow<Double?>

    @Upsert
    suspend fun save(position: ReadingPositionEntity)
}
