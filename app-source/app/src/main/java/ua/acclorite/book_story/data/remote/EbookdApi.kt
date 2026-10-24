/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.remote

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * Rotates the refresh token, exactly once per expiry no matter how many requests noticed it.
 *
 * ebookd refresh tokens are single use and revoke their whole lineage on replay: if three parallel
 * requests each see a 401 and each refresh, two of them present an already-spent token and the
 * server correctly concludes the lineage was copied, logging the reader out at the busiest moment.
 * A blocking lock is enough here — and is simpler than the coroutine version — because whichever
 * caller loses the race finds [TokenStore.accessToken] already moved on and reuses it instead of
 * starting a second rotation.
 *
 * This call runs on a client without [AuthInterceptor]: rotation authenticating itself would be a
 * cycle, both in the dependency graph and at runtime.
 */
class TokenRefresher(
    private val http: OkHttpClient,
    private val json: Json,
    private val tokens: TokenStore
) {
    private val lock = Any()

    /** Returns the token to use now, or null when there is nothing to refresh or rotation failed. */
    fun refresh(staleAccess: String?): String? = synchronized(lock) {
        val current = tokens.accessToken()
        if (current != staleAccess) return current

        val refreshToken = tokens.refreshToken() ?: return null
        val baseUrl = tokens.baseUrl() ?: return null
        val body = json.encodeToString(
            RefreshRequestDto.serializer(), RefreshRequestDto(refreshToken)
        ).toRequestBody(JSON_MEDIA)

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/auth/refresh")
            .post(body)
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.code == 200 -> {
                        val grant = json.decodeFromString(SessionGrantDto.serializer(), text)
                        tokens.save(baseUrl, grant)
                        grant.accessToken
                    }
                    // A rejected refresh is a statement, not weather: keeping the credential would
                    // repeat the same failure forever.
                    response.code == 401 -> {
                        tokens.clear()
                        null
                    }

                    else -> null
                }
            }
        } catch (_: IOException) {
            // Offline, DNS failure or a restarting server. Clearing here would sign the reader out
            // because their train entered a tunnel.
            null
        }
    }
}

/** Attaches the bearer token and transparently rotates it once per response. */
class AuthInterceptor @Inject constructor(
    private val tokens: TokenStore,
    private val refresher: TokenRefresher
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath
        if (path.endsWith("/auth/login") || path.endsWith("/auth/refresh")) {
            return chain.proceed(original)
        }

        // The access token lives in memory only, so the first request after a process restart
        // always arrives without one and has to earn it back from the refresh token.
        var access = tokens.accessToken() ?: refresher.refresh(null)

        var response = chain.proceed(original.withAccess(access))
        if (response.code == 401 && access != null) {
            val fresh = refresher.refresh(access)
            if (fresh != null && fresh != access) {
                response.close()
                response = chain.proceed(original.withAccess(fresh))
            }
        }
        return response
    }

    private fun Request.withAccess(access: String?): Request {
        if (access == null) return this
        return newBuilder().header("Authorization", "Bearer $access").build()
    }
}

/**
 * Waits out a rate limit instead of failing the request.
 *
 * The server allows a fixed number of requests per minute, counted per source address. A NAS that
 * publishes its port makes the whole LAN arrive as one address, so a batch download competes with
 * every other client and with the shelf's own refreshes: a forty-book batch overran the budget and
 * lost most of its books to 429s. Here a 429 means "not yet" rather than "no" — the server sends
 * `Retry-After`, and honoring it turns a failed batch into a slower one.
 */
class RateLimitRetryInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        while (true) {
            val response = chain.proceed(chain.request())
            if (response.code != 429 || attempt >= MAX_RETRIES) return response
            val waitSeconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 30)
                ?: (1L shl attempt)
            response.close()
            attempt += 1
            Thread.sleep(waitSeconds * 1_000)
        }
    }

    private companion object {
        /** Three retries is a few seconds of patience, not an unbounded queue. */
        const val MAX_RETRIES = 3
    }
}

/** One endpoint per call, everything under `/api/v1`. */@Singleton
class EbookdApi @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val tokens: TokenStore
) {
    class Failure(
        val status: Int,
        val code: String,
        override val message: String
    ) : IOException(message)

    suspend fun meta(baseUrl: String): MetaDto =
        io { get(url(baseUrl, "/api/v1/meta"), MetaDto.serializer()) }

    suspend fun login(baseUrl: String, username: String, password: String): SessionGrantDto = io {
        val body = json.encodeToString(
            LoginRequestDto.serializer(),
            LoginRequestDto(
                username = username,
                password = password,
                device = DeviceDto(label = "Book's Story", platform = "android")
            )
        ).toRequestBody(JSON_MEDIA)

        val request = Request.Builder()
            .url(url(baseUrl, "/api/v1/auth/login"))
            .post(body)
            .build()
        val grant = send(request, SessionGrantDto.serializer())
        tokens.save(baseUrl, grant)
        grant
    }

    suspend fun me(baseUrl: String): MeDto =
        io { get(url(baseUrl, "/api/v1/auth/me"), MeDto.serializer()) }

    /**
     * The shelf cursor is opaque and signed, and it is bound to the sort and order that issued it:
     * pass it back unchanged with every other parameter identical, and start over at page one
     * whenever a filter changes.
     */
    suspend fun books(baseUrl: String, cursor: String? = null, limit: Int = 50): BookPageDto = io {
        val builder = url(baseUrl, "/api/v1/books").toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
        if (!cursor.isNullOrBlank()) builder.addQueryParameter("cursor", cursor)
        get(builder.build().toString(), BookPageDto.serializer())
    }

    suspend fun book(baseUrl: String, bookId: String): BookDetailDto =
        io { get(url(baseUrl, "/api/v1/books/$bookId"), BookDetailDto.serializer()) }

    /** The server's shelf tree. One level of nesting at most — the server enforces it. */
    suspend fun categories(baseUrl: String): List<CategoryDto> =
        io { get(url(baseUrl, "/api/v1/categories"), CategoryListDto.serializer()) }.items

    /**
     * There is no single-book delete route; the server only offers the batch one, which soft-deletes
     * and needs the `batch:delete` capability.
     */
    suspend fun batchDelete(bookIds: List<String>): BatchOutcomeDto = io {
        val baseUrl = tokens.baseUrl() ?: throw IOException("No server configured")
        val body = json.encodeToString(
            BatchRequestDto.serializer(), BatchRequestDto(bookIds)
        ).toRequestBody(JSON_MEDIA)
        send(
            Request.Builder()
                .url(url(baseUrl, "/api/v1/books/batch/delete"))
                .post(body)
                .build(),
            BatchOutcomeDto.serializer()
        )
    }

    /**
     * Cover bytes, or null when there is nothing usable. The server answers with an SVG placeholder
     * for books that have no real cover, and Android cannot decode SVG into a bitmap — callers must
     * treat a null as normal rather than as a failure.
     */
    /**
     * A cover's bytes, whatever format the server drew them in.
     *
     * The server answers with a generated SVG for every coverless book, and an earlier version of
     * this refused to hand those back — nothing here can decode one, so they looked like nothing.
     * But "nothing" is also the answer for "the shelf should ask again", and the cover was therefore
     * fetched again on every single sync: forty-three books made about a thousand requests an hour,
     * competing with their own downloads for the link and the server's request budget. The bytes are
     * returned so that they can be written down and the asking can stop; the tile does not use them
     * — it draws its own colour block when the file it is pointed at cannot be decoded.
     */
    suspend fun cover(bookId: String): ByteArray? = io {
        val baseUrl = tokens.baseUrl() ?: return@io null
        http.newCall(
            Request.Builder().url(url(baseUrl, "/api/v1/covers/$bookId")).get().build()
        ).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val type = response.header("Content-Type").orEmpty()
            if (!type.contains("image/", ignoreCase = true)) return@use null
            response.body?.bytes()
        }
    }

    /** Tells the server to drop this device's session, then forgets the credential either way. */
    suspend fun logout(baseUrl: String) = io {
        val request = Request.Builder()
            .url(url(baseUrl, "/api/v1/auth/logout"))
            .post("{}".toRequestBody(JSON_MEDIA))
            .build()
        runCatching { http.newCall(request).execute().use { } }
        tokens.clear()
        Unit
    }

    /**
     * Changes the signed-in account's password. The endpoint demands the current one, and it keeps
     * this device signed in while dropping the other sessions.
     */
    suspend fun changePassword(
        baseUrl: String,
        currentPassword: String,
        newPassword: String
    ): PasswordChangeResultDto = io {
        val body = json.encodeToString(
            PasswordChangeRequestDto.serializer(),
            PasswordChangeRequestDto(currentPassword = currentPassword, newPassword = newPassword)
        ).toRequestBody(JSON_MEDIA)

        val request = Request.Builder()
            .url(url(baseUrl, "/api/v1/auth/me/password"))
            .post(body)
            .build()
        send(request, PasswordChangeResultDto.serializer())
    }

    val cachedBaseUrl: String? get() = tokens.baseUrl()

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun url(baseUrl: String, path: String) = baseUrl.trimEnd('/') + path

    private fun <T> get(fullUrl: String, serializer: DeserializationStrategy<T>): T =
        send(Request.Builder().url(fullUrl).get().build(), serializer)

    private fun <T> send(request: Request, serializer: DeserializationStrategy<T>): T {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw parseFailure(response.code, body)
            return json.decodeFromString(serializer, body)
        }
    }

    private fun parseFailure(status: Int, body: String): Failure {
        val envelope = runCatching {
            json.decodeFromString(ErrorEnvelopeDto.serializer(), body)
        }.getOrNull()
        return Failure(
            status = status,
            code = envelope?.error?.code ?: "http_$status",
            message = envelope?.error?.message.orEmpty().ifBlank { "HTTP $status" }
        )
    }
}
