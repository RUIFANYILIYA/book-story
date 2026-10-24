/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.repository

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import ua.acclorite.book_story.data.remote.EbookdApi
import ua.acclorite.book_story.data.remote.PublicUserDto
import ua.acclorite.book_story.data.remote.TokenStore
import ua.acclorite.book_story.domain.repository.AccountProblem
import ua.acclorite.book_story.domain.repository.AccountRepository
import ua.acclorite.book_story.domain.repository.ServerAccount

@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val api: EbookdApi,
    private val tokens: TokenStore
) : AccountRepository {

    override suspend fun checkServer(baseUrl: String): Result<Unit> =
        runCatching {
            api.meta(addressOrThrow(baseUrl))
            Unit
        }.recoverCatching { throw translate(it) }

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String
    ): Result<ServerAccount> =
        runCatching {
            val grant = api.login(addressOrThrow(baseUrl), username.trim(), password)
            // The grant carries the user but not the capability set; /auth/me is the only source of
            // that, and it is one cheap call on a screen the user is already looking at.
            val me = api.me(addressOrThrow(baseUrl))
            tokens.saveCapabilities(me.capabilities)
            toAccount(me.user, me.capabilities)
        }.recoverCatching { throw translate(it) }

    override suspend fun currentAccount(): Result<ServerAccount> =
        runCatching {
            val baseUrl = tokens.baseUrl() ?: throw AccountProblem(NO_SERVER, "No server configured")
            val me = api.me(baseUrl)
            tokens.saveCapabilities(me.capabilities)
            toAccount(me.user, me.capabilities)
        }.recoverCatching { throw translate(it) }

    override suspend fun logout(): Result<Unit> =
        runCatching {
            val baseUrl = tokens.baseUrl()
            if (baseUrl != null) api.logout(baseUrl) else tokens.clear()
            Unit
        }

    override fun signedIn(): Boolean = tokens.refreshToken() != null

    override suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit> =
        runCatching {
            val baseUrl = tokens.baseUrl() ?: throw AccountProblem(NO_SERVER, "No server configured")
            api.changePassword(baseUrl, currentPassword, newPassword)
            Unit
        }.recoverCatching { throw translate(it) }

    private fun toAccount(user: PublicUserDto, capabilities: List<String>) = ServerAccount(
        username = user.username,
        displayName = user.displayName,
        role = user.role,
        capabilities = capabilities,
        mustChangePassword = user.mustChangePassword
    )

    /**
     * A missing scheme is the user's typo rather than a network fault, and the two need different
     * wording — "cannot reach the server" sent people to check the NAS over a forgotten "http://".
     */
    private fun addressOrThrow(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw AccountProblem(BAD_ADDRESS, "Address must start with http:// or https://")
        }
        return trimmed
    }

    private fun translate(error: Throwable): Throwable = when (error) {
        is AccountProblem -> error
        is EbookdApi.Failure -> AccountProblem(error.code, error.message)
        is IllegalArgumentException -> AccountProblem(BAD_ADDRESS, error.message.orEmpty())
        is IOException -> AccountProblem(UNREACHABLE, error.message.orEmpty())
        else -> error
    }

    private companion object {
        const val BAD_ADDRESS = "bad_address"
        const val UNREACHABLE = "unreachable"
        const val NO_SERVER = "no_server"
    }
}
