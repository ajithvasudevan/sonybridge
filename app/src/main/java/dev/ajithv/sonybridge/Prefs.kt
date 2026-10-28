package dev.ajithv.sonybridge

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** immich endpoint + options, stored encrypted. Falls back to BuildConfig defaults. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = try {
        val key = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "sb_secure", key, ctx,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // keystore unavailable (rare) -> plain prefs so the app still runs
        ctx.getSharedPreferences("sb_plain", Context.MODE_PRIVATE)
    }

    var url: String
        get() = sp.getString("url", "")?.ifBlank { BuildConfig.IMMICH_URL } ?: BuildConfig.IMMICH_URL
        set(v) { sp.edit().putString("url", v.trim()).apply() }

    var key: String
        get() = sp.getString("key", "")?.ifBlank { BuildConfig.IMMICH_KEY } ?: BuildConfig.IMMICH_KEY
        set(v) { sp.edit().putString("key", v.trim()).apply() }

    var uploadVideo: Boolean
        get() = sp.getBoolean("uploadVideo", true)
        set(v) { sp.edit().putBoolean("uploadVideo", v).apply() }

    var uploadRaw: Boolean
        get() = sp.getBoolean("uploadRaw", false)
        set(v) { sp.edit().putBoolean("uploadRaw", v).apply() }

    /** Whether the bridge should be running (persisted → survives reboot; drives BootReceiver + auto-start). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    /** Last hotspot/Wi-Fi IP the camera uploaded to — to detect changes. */
    var lastApIp: String?
        get() = sp.getString("lastApIp", null)
        set(v) { sp.edit().putString("lastApIp", v).apply() }
}
