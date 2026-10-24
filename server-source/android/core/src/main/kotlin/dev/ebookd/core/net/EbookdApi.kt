package dev.ebookd.core.net

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The subset of `/api/v1` the app shell speaks today. Kept as one interface rather than several so
 * the base URL has exactly one owner — the whole point of the server-config screen is that this
 * URL is user input, and a second Retrofit instance would quietly keep pointing at the old host.
 */
interface EbookdApi {

    /**
     * Capability discovery. Unauthenticated on purpose: the address screen calls it before any
     * credentials exist, both to prove the URL is an ebookd server and to measure the clock offset.
     */
    @GET("api/v1/meta")
    suspend fun meta(@Header("X-App-Version-Code") appVersionCode: Int? = null): MetaDto

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): SessionGrantDto

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): SessionGrantDto

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: RefreshRequestDto)

    @GET("api/v1/auth/me")
    suspend fun me(): MeResponseDto

    /**
     * One page of the catalogue, with the caller's reading state joined in.
     *
     * The cursor is opaque and signed, and it is rejected if it arrives with a different sort or
     * order than the one it was issued for — so a caller that changes filters must start over
     * rather than pass the old cursor along.
     */
    @GET("api/v1/books")
    suspend fun books(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = DEFAULT_PAGE_SIZE,
        @Query("q") query: String? = null,
        @Query("format") format: String? = null,
        @Query("sort") sort: String? = null,
        @Query("order") order: String? = null,
    ): BookPageDto

    @GET("api/v1/books/{id}")
    suspend fun book(@Path("id") id: String): BookDetailDto

    /**
     * Sends reading positions that were written while offline.
     *
     * Each change names the revision it was based on, so the server can refuse to apply one over a
     * newer row instead of silently discarding it; what arrives back as a `conflict` is the client's
     * to resolve, because only the client knows which position the reader was actually looking at.
     * See [dev.ebookd.core.data.ProgressMerge] for the rule this app applies.
     */
    @POST("api/v1/sync/push")
    suspend fun syncPush(@Body body: SyncPushRequestDto): SyncPushResultDto

    /**
     * Everything the server has changed since [cursor], the caller's last seen revision.
     *
     * The cursor is the server's own counter, so a device that was offline for a week converges by
     * walking it forward rather than by comparing clocks it cannot trust.
     */
    @GET("api/v1/sync/delta")
    suspend fun syncDelta(
        @Query("cursor") cursor: Long,
        @Query("limit") limit: Int = SYNC_PAGE_SIZE,
    ): SyncDeltaDto

    /**
     * Filing the reader's own shelf: marks several books read at once.
     *
     * The one batch call that needs no special permission — it writes only the caller's own state
     * row — which is why it is the action the app offers to everybody.
     */
    @POST("api/v1/books/batch/status")
    suspend fun batchStatus(@Body body: BatchStatusRequestDto): BatchResultDto

    /** Tagging is shared metadata, so the server refuses it without `library:edit`; the app hides it. */
    @POST("api/v1/books/batch/tag")
    suspend fun batchTag(@Body body: BatchTagRequestDto): BatchResultDto

    /** Soft delete: the book leaves the catalogue, its files stay for the retention window. */
    @POST("api/v1/books/batch/delete")
    suspend fun batchDelete(@Body body: BatchDeleteRequestDto): BatchResultDto

    /** Existing tags for the picker, most used first; [query] is a substring match. */
    @GET("api/v1/tags")
    suspend fun tags(@Query("q") query: String? = null): TagPageDto

    /**
     * This reader's own cover for one book.
     *
     * The server checks the picture's magic bytes and nothing else, so the app is the one that has
     * to make the bytes a cover: cropped to the shelf's 2:3, downscaled, and re-encoded. A phone
     * camera's 4000×3000 JPEG would otherwise be stored at full size and refetched by every launch.
     */
    @Multipart
    @POST("api/v1/books/{id}/cover")
    suspend fun uploadCover(
        @Path("id") id: String,
        @Part part: MultipartBody.Part,
    ): CoverOverrideDto

    /**
     * Back to the library's cover.
     *
     * Idempotent by contract (204 whether or not one was set), which is what lets the menu item mean
     * "make sure I have no override" rather than "undo the thing you may or may not have done here".
     */
    @DELETE("api/v1/books/{id}/cover")
    suspend fun clearCover(@Path("id") id: String)

    companion object {
        const val DEFAULT_PAGE_SIZE = 50

        /** The server's own maximum; asking for more is clamped rather than rejected. */
        const val SYNC_PAGE_SIZE = 1000

        /**
         * Reserved by RFC 2606, and rewritten per request by `BaseUrlInterceptor`. Shared because a
         * raw file download is not a Retrofit call, and a second copy of this string would let the
         * two paths disagree about the host while both "worked".
         */
        const val PLACEHOLDER_BASE_URL = "http://ebookd.invalid/"

        fun fileDownloadUrl(fileId: String): String =
            PLACEHOLDER_BASE_URL + "api/v1/files/$fileId/download"
    }
}
