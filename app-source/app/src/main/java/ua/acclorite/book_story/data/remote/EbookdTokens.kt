/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where the credentials live, and why they live in two places.
 *
 * The access token is kept in memory only: it expires in minutes, and a second copy on disk would
 * be a second thing to leak. The refresh token is the long-lived one, so it is wrapped by an
 * AES-256-GCM key held in the Android Keystore and written to `noBackupFilesDir`, which keeps it
 * out of cloud backups and device-to-device transfers.
 *
 * The server base URL travels with the refresh token because rotation happens from an OkHttp
 * interceptor, where nothing knows about the settings screen that collected it.
 */
interface TokenStore {
    fun accessToken(): String?

    fun baseUrl(): String?

    fun deviceId(): String?

    fun save(baseUrl: String, grant: SessionGrantDto)

    fun refreshToken(): String?

    /**
     * What the signed-in account may do, read without a network round trip. Screens use it to hide
     * actions the server would refuse with 403 — the alternative is teaching users to discover
     * permissions by hitting errors.
     */
    fun capabilities(): List<String>

    fun saveCapabilities(capabilities: List<String>)

    fun clear()
}

@Serializable
internal data class StoredCredentials(
    val baseUrl: String,
    val refreshToken: String,
    val deviceId: String,
    val capabilities: List<String> = emptyList()
)

class KeystoreTokenStore(
    private val context: Context,
    private val keyAlias: String = KEY_ALIAS
) : TokenStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val file: File get() = File(context.noBackupFilesDir, "ebookd_session.bin")

    @Volatile
    private var access: String? = null

    @Volatile
    private var base: String? = null

    @Volatile
    private var device: String? = null

    override fun accessToken(): String? = access

    override fun baseUrl(): String? = base ?: read()?.baseUrl

    override fun deviceId(): String? = device ?: read()?.deviceId

    override fun save(baseUrl: String, grant: SessionGrantDto) {
        access = grant.accessToken
        device = grant.deviceId
        base = baseUrl.trimEnd('/')
        write(StoredCredentials(base!!, grant.refreshToken, grant.deviceId))
    }

    override fun refreshToken(): String? = read()?.refreshToken

    @Volatile
    private var caps: List<String>? = null

    override fun capabilities(): List<String> = caps ?: read()?.capabilities.orEmpty()

    override fun saveCapabilities(capabilities: List<String>) {
        val stored = read() ?: return
        caps = capabilities
        write(stored.copy(capabilities = capabilities))
    }

    override fun clear() {
        access = null
        device = null
        base = null
        caps = null
        file.delete()
    }

    private fun write(credentials: StoredCredentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(
            json.encodeToString(StoredCredentials.serializer(), credentials).toByteArray()
        )
        file.parentFile?.mkdirs()
        file.writeBytes(blob)
    }

    private fun read(): StoredCredentials? {
        if (!file.exists()) return null
        return try {
            val blob = file.readBytes()
            if (blob.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            val plaintext = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
            json.decodeFromString(StoredCredentials.serializer(), plaintext.decodeToString())
        } catch (_: Exception) {
            // A keystore key can be invalidated by the platform (lock screen reset, biometric
            // change). Forgetting the credential and asking for a sign-in beats crashing on the
            // first read after boot.
            clear()
            null
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_ALIAS = "book_story_ebookd_refresh_v1"
    }
}
