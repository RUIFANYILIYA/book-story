package dev.ebookd.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Batch shelf maintenance — "select thirty books and file them".
 *
 * The server's six batch endpoints share one answer shape, and the shape is the interesting part:
 * it reports **per id**, not a count. "42 changed" cannot tell the client which of its selection
 * did not happen, and a book that another device deleted between selection and submission is an
 * ordinary occurrence rather than an error — the caller has to drop exactly those ids and keep the
 * rest. Everything the app does with a batch result starts from [BatchResultDto.split].
 *
 * The whole batch is one transaction server-side, so a partial application cannot happen through a
 * crash; the partial results here are per-id *refusals* (a stale id, a missing collection), not
 * half-written rows.
 */

@Serializable
data class BatchResultDto(
    val requested: Int = 0,
    val changed: List<BatchChangeDto> = emptyList(),
    val applied: Int = 0,
    val skipped: Int = 0,
    val dryRun: Boolean = false,
) {
    /**
     * Which ids took the change and which did not.
     *
     * An id the response simply does not mention counts as failed. The server always answers for
     * every id it was given, so silence means the answer was truncated or the peer is not the
     * server this was written against — and reverting an optimistic change is the safe reading of
     * both, while "not mentioned" read as "applied" would leave the shelf showing something that
     * never happened.
     */
    fun split(selected: List<String>): BatchSplit {
        val ok = changed.filter { it.ok }.map { it.id }.toSet()
        return BatchSplit(
            applied = selected.filter { it in ok },
            failed = selected.filterNot { it in ok },
        )
    }
}

@Serializable
data class BatchChangeDto(
    val id: String,
    val ok: Boolean,
    val error: String? = null,
)

/** What a batch did, in the app's own terms: the ids that carry the change, and the ones that do not. */
data class BatchSplit(
    val applied: List<String>,
    val failed: List<String>,
) {
    val allApplied: Boolean get() = failed.isEmpty()
}

@Serializable
data class BatchStatusRequestDto(
    val bookIds: List<String>,
    /** One of the server's enum: none, reading, finished, abandoned, on_hold. */
    val status: String? = null,
    /** `library` or `wishlist`; null leaves the shelf alone. */
    val shelf: String? = null,
)

@Serializable
data class BatchTagRequestDto(
    val bookIds: List<String>,
    /** Tag *names*: the server creates the tag when it does not exist, which is what a text field wants. */
    val add: List<String> = emptyList(),
    /** Tag removals go by id, because a name can be renamed while an id cannot. */
    val remove: List<String> = emptyList(),
)

@Serializable
data class BatchDeleteRequestDto(
    val bookIds: List<String>,
)

/** `GET /api/v1/tags`, which answers with raw rows: the field names are snake_case here. */
@Serializable
data class TagPageDto(val items: List<TagDto> = emptyList())

@Serializable
data class TagDto(
    val id: String = "",
    val name: String = "",
    @SerialName("usage_count") val usageCount: Int = 0,
)
