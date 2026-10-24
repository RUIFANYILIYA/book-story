package dev.ebookd.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Small, synchronous-friendly settings: the server address, the last username typed, and how the
 * reader lays text out.
 *
 * The address lives here rather than in a build config because it is a *user* decision — the same
 * APK points at a laptop during development and at a NAS afterwards — and because the reader's
 * shelf must survive an app update that changes nothing else.
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val baseUrl: Flow<String?> = dataStore.data.map { it[KEY_BASE_URL] }
    val lastUsername: Flow<String?> = dataStore.data.map { it[KEY_LAST_USERNAME] }

    /**
     * Reader settings are global rather than per book. A book that opened in a different typeface
     * because the last one read happened to be a different size reads as a bug — and the layout is
     * recomputed from these values every time anyway, since the saved position is a character
     * offset precisely so that a global font change cannot move anyone.
     */
    val reader: Flow<ReaderSettings> = dataStore.data.map { it.asReaderSettings() }

    suspend fun baseUrlNow(): String? = baseUrl.first()

    suspend fun setBaseUrl(value: String) {
        dataStore.edit { it[KEY_BASE_URL] = value }
    }

    suspend fun setLastUsername(value: String?) {
        dataStore.edit { preferences ->
            if (value.isNullOrBlank()) preferences.remove(KEY_LAST_USERNAME) else preferences[KEY_LAST_USERNAME] = value
        }
    }

    suspend fun updateReader(change: (ReaderSettings) -> ReaderSettings) {
        dataStore.edit { preferences ->
            val next = change(preferences.asReaderSettings())
            preferences[ReaderKeys.FONT_SP] = next.fontSizeSp
            preferences[ReaderKeys.LINE_MULT] = next.lineHeightMultiplier
            preferences[ReaderKeys.MARGIN_DP] = next.marginDp
            preferences[ReaderKeys.PALETTE] = next.palette
            preferences[ReaderKeys.MOTION] = next.motion
            preferences[ReaderKeys.BRIGHTNESS] = next.brightness
            preferences[ReaderKeys.WARMTH] = next.warmth
            preferences[ReaderKeys.VOLUME_KEYS] = next.volumeKeys
            // Removed rather than left as an empty string when the reader goes back to the built-in
            // typeface: "no font" is the absence of the key, so there is one representation of it.
            next.fontPath?.let { preferences[ReaderKeys.FONT_PATH] = it }
                ?: preferences.remove(ReaderKeys.FONT_PATH)
        }
    }

    /** Forgets which server this was, without touching the credentials (the sign-out path does that). */
    suspend fun clearServer() {
        dataStore.edit { it.remove(KEY_BASE_URL) }
    }

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_LAST_USERNAME = stringPreferencesKey("last_username")
    }
}

/** Everything the layout pass consumes, in the units the platform speaks. */
data class ReaderSettings(
    val fontSizeSp: Float,
    val lineHeightMultiplier: Float,
    val marginDp: Float,
    val palette: String,
    val motion: String,
    /**
     * The reader window's own brightness, 0.05..1, or [BRIGHTNESS_SYSTEM] to leave it to the phone.
     *
     * A window attribute rather than a global setting: `Settings.System` needs `WRITE_SETTINGS`, which
     * OEM builds frequently ignore, and a reader has no business changing how bright the phone is for
     * everyone else. Restored when the reader screen goes away, so leaving the book gives the phone
     * back its own brightness.
     */
    val brightness: Float = BRIGHTNESS_SYSTEM,
    /** An extra warm overlay on top of the palette's own tint, 0..1. */
    val warmth: Float = 0f,
    /** Absolute path of a font the reader imported; null means the platform typeface. */
    val fontPath: String? = null,
    /** Whether the volume keys turn pages — only while the chrome is hidden; see `ReaderViewModel`. */
    val volumeKeys: Boolean = false,
) {
    companion object {
        const val BRIGHTNESS_SYSTEM = -1f
    }
}

internal object ReaderKeys {
    val FONT_SP = floatPreferencesKey("reader_font_sp")
    val LINE_MULT = floatPreferencesKey("reader_line_mult")
    val MARGIN_DP = floatPreferencesKey("reader_margin_dp")
    val PALETTE = stringPreferencesKey("reader_palette")
    val MOTION = stringPreferencesKey("reader_motion")
    val BRIGHTNESS = floatPreferencesKey("reader_brightness")
    val WARMTH = floatPreferencesKey("reader_warmth")
    val FONT_PATH = stringPreferencesKey("reader_font_path")
    val VOLUME_KEYS = booleanPreferencesKey("reader_volume_keys")

    const val DEFAULT_FONT_SP = 19f
    const val DEFAULT_LINE_MULT = 1.6f
    const val DEFAULT_MARGIN_DP = 18f
    const val DEFAULT_PALETTE = "day"
    const val DEFAULT_MOTION = "slide"
}

private fun Preferences.asReaderSettings() = ReaderSettings(
    fontSizeSp = this[ReaderKeys.FONT_SP] ?: ReaderKeys.DEFAULT_FONT_SP,
    lineHeightMultiplier = this[ReaderKeys.LINE_MULT] ?: ReaderKeys.DEFAULT_LINE_MULT,
    marginDp = this[ReaderKeys.MARGIN_DP] ?: ReaderKeys.DEFAULT_MARGIN_DP,
    palette = this[ReaderKeys.PALETTE] ?: ReaderKeys.DEFAULT_PALETTE,
    motion = this[ReaderKeys.MOTION] ?: ReaderKeys.DEFAULT_MOTION,
    brightness = this[ReaderKeys.BRIGHTNESS] ?: ReaderSettings.BRIGHTNESS_SYSTEM,
    warmth = this[ReaderKeys.WARMTH] ?: 0f,
    fontPath = this[ReaderKeys.FONT_PATH],
    volumeKeys = this[ReaderKeys.VOLUME_KEYS] ?: false,
)
