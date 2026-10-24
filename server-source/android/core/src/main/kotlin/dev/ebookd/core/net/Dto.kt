package dev.ebookd.core.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire types for `/api/v1`, mirroring `shared/api-spec/openapi.yaml`.
 *
 * Every optional field has a default and the parser ignores unknown keys, because the contract
 * allows the server to *add* fields and enum values inside v1 — a client that crashed on a new
 * field would make that rule useless. The one field that is deliberately strict is
 * [MetaDto.protocol]: a mismatch is a break the client has to handle, not a detail to shrug off.
 */

@Serializable
data class MetaDto(
    val apiVersion: String,
    val serverVersion: String,
    val revision: String? = null,
    val protocol: Int = 0,
    val minAppVersionCode: Int = 0,
    val upgradeRequired: Boolean = false,
    /** Server clock, used to measure the offset before stamping sync records. */
    val serverTime: String,
    val features: Map<String, Boolean> = emptyMap(),
    val formats: FormatsDto = FormatsDto(),
    /** Left as a raw object: limits are advisory, and a client that needs one reads it by name. */
    val limits: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        /** The protocol the app speaks. A server that disagrees is not this app's server. */
        const val PROTOCOL = 1
    }
}

@Serializable
data class FormatsDto(
    val accepted: List<String> = emptyList(),
    val renderable: List<String> = emptyList(),
)

@Serializable
data class PublicUserDto(
    val id: String,
    val username: String,
    val displayName: String? = null,
    val email: String? = null,
    val role: String = "user",
    val status: String = "active",
    val quotaBytes: Long? = null,
    val mustChangePassword: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class DeviceDto(
    val id: String? = null,
    val label: String? = null,
    val platform: String = "android",
    val clientVersion: String? = null,
)

@Serializable
data class LoginRequestDto(
    val username: String,
    val password: String,
    val device: DeviceDto = DeviceDto(),
)

@Serializable
data class RefreshRequestDto(val refreshToken: String)

@Serializable
data class SessionGrantDto(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Int = 0,
    /** Opaque and single-use: every refresh returns a new one and kills the previous. */
    val refreshToken: String,
    val deviceId: String,
    val user: PublicUserDto,
)

@Serializable
data class MeResponseDto(
    val user: PublicUserDto,
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class ErrorEnvelopeDto(val error: ErrorBodyDto)

@Serializable
data class ErrorBodyDto(
    val code: String,
    val message: String,
    val details: JsonObject = JsonObject(emptyMap()),
    val requestId: String? = null,
)
