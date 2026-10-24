/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import ua.acclorite.book_story.data.remote.AuthInterceptor
import ua.acclorite.book_story.data.remote.KeystoreTokenStore
import ua.acclorite.book_story.data.remote.RateLimitRetryInterceptor
import ua.acclorite.book_story.data.remote.TokenRefresher
import ua.acclorite.book_story.data.remote.TokenStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UnauthenticatedClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // `encodeDefaults` alone would send "id": null for an optional field, and the server reads
        // a present-but-null id as a type error rather than as "not provided".
        explicitNulls = false
    }

    /**
     * The refresh token is the one credential worth protecting, so it is wrapped by a platform key
     * instead of sitting in a preferences file.
     */
    @Provides
    @Singleton
    fun provideTokenStore(application: Application): TokenStore = KeystoreTokenStore(application)

    /**
     * Rotation must not travel through the interceptor that triggers it, or the graph would be a
     * cycle — and a 401 from `/auth/refresh` would otherwise start another rotation.
     */
    @Provides
    @UnauthenticatedClient
    @Singleton
    fun provideUnauthenticatedClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(30L, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideTokenRefresher(
        @UnauthenticatedClient client: OkHttpClient,
        json: Json,
        tokens: TokenStore
    ): TokenRefresher = TokenRefresher(client, json, tokens)

    @Provides
    @Singleton
    fun provideOkHttp(interceptor: AuthInterceptor): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(interceptor)
        .addInterceptor(RateLimitRetryInterceptor())
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(60L, TimeUnit.SECONDS)
        .callTimeout(10L, TimeUnit.MINUTES)
        .build()
}
