/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.domain.repository

/** Who we are signed in as, as reported by the library server. */
data class ServerAccount(
    val username: String,
    val displayName: String,
    val role: String,
    val capabilities: List<String>,
    val mustChangePassword: Boolean
)

/** Why a server call failed, with the server's own error code when there was one. */
class AccountProblem(
    val code: String,
    override val message: String
) : Exception(message)

interface AccountRepository {
    /** Unauthenticated reachability check, so a typo address fails fast and in plain words. */
    suspend fun checkServer(baseUrl: String): Result<Unit>

    suspend fun login(baseUrl: String, username: String, password: String): Result<ServerAccount>

    /** Reads the signed-in account; rotates the token if the process restarted since the sign-in. */
    suspend fun currentAccount(): Result<ServerAccount>

    suspend fun logout(): Result<Unit>

    /**
     * Changes the password on the server. The endpoint demands the current one, so an access token
     * alone cannot lock the owner out; the other devices are signed out, this one is not.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit>

    fun signedIn(): Boolean
}
