package live.geargrid.phone

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun setCredentials(deviceId: String, deviceToken: String, tenantDb: String, apiBaseUrl: String?) {
        prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_TENANT_DB, tenantDb)
            .putString(KEY_API_BASE_URL, (apiBaseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_API_BASE_URL).trimEnd('/'))
            .apply()
    }

    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_TENANT_DB)
            .remove(KEY_CONTACT_SYNC_ENABLED)
            .remove(KEY_CONTACTS_CONSENTED)
            .remove(KEY_CONTACTS_SYNCED_AT)
            .remove(KEY_CONTACTS_SYNC_ERROR)
            .remove(KEY_BRIEFS_SYNCED_AT)
            .remove(KEY_BRIEFS_SINCE_CURSOR)
            .remove(KEY_PUSH_TOKEN)
            .apply()
    }

    fun hasCredentials(): Boolean =
        !getDeviceId().isNullOrBlank() &&
            !getDeviceToken().isNullOrBlank() &&
            !getTenantDb().isNullOrBlank()

    fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)
    fun getDeviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)
    fun getTenantDb(): String? = prefs.getString(KEY_TENANT_DB, null)
    fun getApiBaseUrl(): String =
        prefs.getString(KEY_API_BASE_URL, DEFAULT_API_BASE_URL) ?: DEFAULT_API_BASE_URL

    fun isContactSyncEnabled(): Boolean = prefs.getBoolean(KEY_CONTACT_SYNC_ENABLED, false)
    fun setContactSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTACT_SYNC_ENABLED, enabled).apply()
    }

    fun hasContactsConsent(): Boolean = prefs.getBoolean(KEY_CONTACTS_CONSENTED, false)
    fun setContactsConsented(consented: Boolean) {
        prefs.edit().putBoolean(KEY_CONTACTS_CONSENTED, consented).apply()
    }

    fun getContactsSyncedAt(): String? = prefs.getString(KEY_CONTACTS_SYNCED_AT, null)
    fun setContactsSyncedAt(iso: String) {
        prefs.edit().putString(KEY_CONTACTS_SYNCED_AT, iso).apply()
    }

    /** Why the last contact sync failed (a short code the web app translates), or null after a success. */
    fun getContactsSyncError(): String? = prefs.getString(KEY_CONTACTS_SYNC_ERROR, null)
    fun setContactsSyncError(code: String?) {
        prefs.edit().apply {
            if (code == null) remove(KEY_CONTACTS_SYNC_ERROR) else putString(KEY_CONTACTS_SYNC_ERROR, code)
        }.apply()
    }

    fun getBriefsSyncedAt(): String? = prefs.getString(KEY_BRIEFS_SYNCED_AT, null)
    fun setBriefsSyncedAt(iso: String) {
        prefs.edit().putString(KEY_BRIEFS_SYNCED_AT, iso).apply()
    }

    fun getBriefsSinceCursor(): String? = prefs.getString(KEY_BRIEFS_SINCE_CURSOR, null)
    fun setBriefsSinceCursor(iso: String?) {
        val editor = prefs.edit()
        if (iso.isNullOrBlank()) {
            editor.remove(KEY_BRIEFS_SINCE_CURSOR)
        } else {
            editor.putString(KEY_BRIEFS_SINCE_CURSOR, iso)
        }
        editor.apply()
    }

    fun getPushToken(): String? = prefs.getString(KEY_PUSH_TOKEN, null)
    fun setPushToken(token: String?) {
        val editor = prefs.edit()
        if (token.isNullOrBlank()) {
            editor.remove(KEY_PUSH_TOKEN)
        } else {
            editor.putString(KEY_PUSH_TOKEN, token)
        }
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "geargrid_phone_credentials"
        private const val DEFAULT_API_BASE_URL = "https://app.geargrid.live/api"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_TENANT_DB = "tenant_db"
        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_CONTACT_SYNC_ENABLED = "contact_sync_enabled"
        private const val KEY_CONTACTS_CONSENTED = "contacts_consented"
        private const val KEY_CONTACTS_SYNCED_AT = "contacts_synced_at"
        private const val KEY_CONTACTS_SYNC_ERROR = "contacts_sync_error"
        private const val KEY_BRIEFS_SYNCED_AT = "briefs_synced_at"
        private const val KEY_BRIEFS_SINCE_CURSOR = "briefs_since_cursor"
        private const val KEY_PUSH_TOKEN = "push_token"
    }
}
