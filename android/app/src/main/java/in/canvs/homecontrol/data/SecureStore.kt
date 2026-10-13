package `in`.canvs.homecontrol.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted storage for MirAIe credentials and tokens; plain prefs for WiZ bulb names. */
class SecureStore(context: Context) {

    private val secure: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "miraie_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val plain: SharedPreferences =
        context.getSharedPreferences("home_control", Context.MODE_PRIVATE)

    var login: String?
        get() = secure.getString(KEY_LOGIN, null)
        set(v) = secure.edit { putString(KEY_LOGIN, v) }

    var password: String?
        get() = secure.getString(KEY_PASSWORD, null)
        set(v) = secure.edit { putString(KEY_PASSWORD, v) }

    var accessToken: String?
        get() = secure.getString(KEY_ACCESS, null)
        set(v) = secure.edit { putString(KEY_ACCESS, v) }

    var refreshToken: String?
        get() = secure.getString(KEY_REFRESH, null)
        set(v) = secure.edit { putString(KEY_REFRESH, v) }

    var userId: String?
        get() = secure.getString(KEY_USER, null)
        set(v) = secure.edit { putString(KEY_USER, v) }

    /** Epoch millis after which the access token should be treated as expired. */
    var expiresAt: Long
        get() = secure.getLong(KEY_EXPIRES, 0L)
        set(v) = secure.edit { putLong(KEY_EXPIRES, v) }

    /** Cached AC list (device, keys, LAN secrets) so local-only mode can start without the cloud. */
    var devicesCache: String?
        get() = secure.getString(KEY_DEVICES, null)
        set(v) = secure.edit { putString(KEY_DEVICES, v) }

    var localOnly: Boolean
        get() = plain.getBoolean(KEY_LOCAL_ONLY, false)
        set(v) = plain.edit { putBoolean(KEY_LOCAL_ONLY, v) }

    /** Experimental: try LAN control even when the AC model doesn't list `udpControl`. */
    var experimentalLocal: Boolean
        get() = plain.getBoolean(KEY_EXPERIMENTAL_LOCAL, false)
        set(v) = plain.edit { putBoolean(KEY_EXPERIMENTAL_LOCAL, v) }

    /** Local AC profiles for one device, as a JSON array string. */
    fun acProfiles(deviceId: String): String? = plain.getString("ac_profiles_$deviceId", null)

    fun setAcProfiles(deviceId: String, json: String) = plain.edit { putString("ac_profiles_$deviceId", json) }

    fun clearMiraie() = secure.edit { clear() }

    fun bulbName(mac: String): String? = plain.getString("bulb_name_$mac", null)

    fun setBulbName(mac: String, name: String) =
        plain.edit { putString("bulb_name_$mac", name) }

    private companion object {
        const val KEY_LOGIN = "login"
        const val KEY_PASSWORD = "password"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER = "user_id"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_DEVICES = "devices_cache"
        const val KEY_LOCAL_ONLY = "local_only"
        const val KEY_EXPERIMENTAL_LOCAL = "experimental_local"
    }
}
