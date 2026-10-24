package dev.ebookd.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.ebookd.core.net.SessionGrantDto
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
 * The access token is kept **in memory only**: it expires in minutes, and writing it to disk would
 * create a second copy to leak for no benefit. The refresh token is the long-lived one, so it is
 * encrypted with an AES-256-GCM key that lives in the Android Keystore — a key that never leaves
 * the TEE/StrongBox and cannot be read back even by this process — and the ciphertext is written to
 * `noBackupFilesDir`, which excludes it from cloud backups and device-to-device transfer.
 *
 * The interface exists so the retry-and-refresh logic can be tested on a plain JVM: the Keystore is
 * one of the things that genuinely cannot be exercised without a device, and pretending otherwise
 * would hide that the interceptor is the part with the concurrency bug.
 */
interface TokenStore {
    /** In-memory access token, valid until the next refresh or process death. */
    fun accessToken(): String?

    fun deviceId(): String?

    /** Persists the grant: access token in memory, refresh token wrapped by the platform key. */
    fun save(grant: SessionGrantDto)

    /** The stored refresh token, or null when absent or no longer decryptable. */
    fun refreshToken(): String?

    fun clear()
}

@Serializable
internal data class StoredCredentials(val refreshToken: String, val deviceId: String)

class AndroidKeystoreTokenStore(
    private val context: Context,
    private val keyAlias: String = "ebookd-refresh-v1",
) : TokenStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val file: File get() = File(context.noBackupFilesDir, "secrets.bin")

    @Volatile
    private var access: String? = null

    @Volatile
    private var device: String? = null

    override fun accessToken(): String? = access

    override fun deviceId(): String? = device ?: read()?.deviceId

    override fun save(grant: SessionGrantDto) {
        access = grant.accessToken
        device = grant.deviceId
        write(StoredCredentials(grant.refreshToken, grant.deviceId))
    }

    override fun refreshToken(): String? = read()?.refreshToken

    override fun clear() {
        access = null
        device = null
        // Deleting the ciphertext is enough — the key stays, so a later sign-in still has one.
        file.delete()
    }

    private fun write(credentials: StoredCredentials) {
        val plaintext = json.encodeToString(StoredCredentials.serializer(), credentials).toByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(plaintext)
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
            // A key can be invalidated by the platform (lockscreen reset, biometric change). The
            // honest response is to forget the credential and ask for a sign-in, not to crash while
            // reading a file on app start.
            clear()
            null
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
