package dev.ebookd.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sync wire types: what a position looks like on the way up and on the way down.
 *
 * The anchor travels as a *document* the client defines — `position_json` is opaque to the server,
 * which stores the string and merges on `percent`. That is not laziness: the anchor's meaning
 * belongs to the reader (chapter and character offset into normalized text), while the percentage
 * is the part two clients and two file versions can agree about. Keeping the server out of the
 * anchor's shape is what lets the shape change without a migration.
 *
 * [SyncPositionDto] is what this app writes inside it, and reads back from `position_json`.
 */
@Serializable
data class SyncPositionDto(
    val chapterIndex: Int? = null,
    val charOffset: Int? = null,
    val fileId: String? = null,
    val sha256: String? = null,
)

@Serializable
data class SyncPushPayloadDto(
    val status: String? = null,
    val percent: Double? = null,
    val position: SyncPositionDto? = null,
)

@Serializable
data class SyncChangeDto(
    val kind: String = KIND_STATE,
    val bookId: String? = null,
    /** The rev this change was based on; the server refuses to apply it over a newer one. */
    val baseRev: Long? = null,
    val payload: SyncPushPayloadDto,
) {
    companion object {
        const val KIND_STATE = "state"
    }
}

@Serializable
data class SyncPushRequestDto(val changes: List<SyncChangeDto>)

@Serializable
data class SyncAppliedDto(
    val index: Int = -1,
    val kind: String = "",
    val id: String = "",
    val rev: Long = 0,
)

@Serializable
data class SyncConflictDto(
    val index: Int = -1,
    val kind: String = "",
    val id: String = "",
    val serverRev: Long = 0,
)

@Serializable
data class SyncPushResultDto(
    val applied: List<SyncAppliedDto> = emptyList(),
    val conflicts: List<SyncConflictDto> = emptyList(),
    val serverRev: Long = 0,
)

@Serializable
data class SyncDeltaDto(
    val items: List<SyncDeltaItemDto> = emptyList(),
    val nextCursor: Long = 0,
    val serverRev: Long = 0,
    val hasMore: Boolean = false,
)

/**
 * One changed row. State and bookmark rows share the envelope and differ in which fields they fill,
 * so the unused ones are null rather than absent — the server selects them as `kind` plus whatever
 * belongs to that table.
 */
@Serializable
data class SyncDeltaItemDto(
    val kind: String = "",
    @SerialName("book_id") val bookId: String? = null,
    val status: String? = null,
    @SerialName("progress_percent") val percent: Double? = null,
    /** The anchor document, as a JSON *string*: the server never parses it. */
    @SerialName("position_json") val positionJson: String? = null,
    @SerialName("position_file_id") val positionFileId: String? = null,
    @SerialName("last_read_at") val lastReadAt: String? = null,
    val rev: Long = 0,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    val isState: Boolean get() = kind == SyncChangeDto.KIND_STATE
}
