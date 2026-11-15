package live.geargrid.phone

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class CredentialStore(context: Context) {

    val appContext: Context = context.applicationContext ?: context

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
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_TENANT_DB, tenantDb)
            .putString(KEY_API_BASE_URL, (apiBaseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_API_BASE_URL).trimEnd('/'))
            .putLong(KEY_TOKEN_ISSUED_AT_MS, now)
            .putLong(KEY_LAST_SYNC_SUCCESS_MS, now)
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
            .remove(KEY_TOKEN_ISSUED_AT_MS)
            .remove(KEY_LAST_SYNC_SUCCESS_MS)
            .remove(KEY_OFFLINE_WIPE_DAYS)
            .remove(KEY_DEVICE_STATUS)
            .remove(KEY_EFFECTIVE_CAPABILITIES)
            .remove(KEY_CAPABILITY_REASONS)
            .remove(KEY_DEVICE_POLICY_JSON)
            .remove(KEY_CONTACT_SYNC_MODE)
            .remove(KEY_SELECTED_CONTACT_LABELS)
            .remove(KEY_SELECTED_CONTACT_ACCOUNTS)
            .remove(KEY_EXCLUDED_CONTACT_KEYS)
            .remove(KEY_CALL_LOGGING_SCOPE)
            .remove(KEY_SIM_FILTER)
            .remove(KEY_AFTER_CALL_ENABLED)
            .remove(KEY_AFTER_CALL_TAGS)
            .remove(KEY_AFTER_CALL_DISMISS_SEC)
            .remove(KEY_MIN_APP_VERSION)
            .remove(KEY_LATEST_APP_VERSION)
            .remove(KEY_CALL_TEXT_SCALE)
            .remove(KEY_APP_LANGUAGE)
            .remove(KEY_APP_TEXT_SIZE)
            .apply()
    }

    fun getTokenIssuedAtMs(): Long = prefs.getLong(KEY_TOKEN_ISSUED_AT_MS, 0L)

    fun setTokenIssuedAtMs(ms: Long) {
        prefs.edit().putLong(KEY_TOKEN_ISSUED_AT_MS, ms).apply()
    }

    fun updateToken(newToken: String, issuedAtMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_DEVICE_TOKEN, newToken)
            .putLong(KEY_TOKEN_ISSUED_AT_MS, issuedAtMs)
            .apply()
    }

    fun getLastSyncSuccessMs(): Long = prefs.getLong(KEY_LAST_SYNC_SUCCESS_MS, 0L)

    fun setLastSyncSuccessMs(ms: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_SUCCESS_MS, ms).apply()
    }

    fun getOfflineWipeDays(): Int = prefs.getInt(KEY_OFFLINE_WIPE_DAYS, 7).coerceAtLeast(1)

    fun setOfflineWipeDays(days: Int) {
        prefs.edit().putInt(KEY_OFFLINE_WIPE_DAYS, days.coerceAtLeast(1)).apply()
    }

    fun getDeviceStatus(): String = prefs.getString(KEY_DEVICE_STATUS, "ACTIVE") ?: "ACTIVE"

    fun setDeviceStatus(status: String) {
        prefs.edit().putString(KEY_DEVICE_STATUS, status).apply()
    }

    fun getEffectiveCapabilitiesJson(): String? = prefs.getString(KEY_EFFECTIVE_CAPABILITIES, null)

    fun setEffectiveCapabilitiesJson(json: String?) {
        val editor = prefs.edit()
        if (json.isNullOrBlank()) {
            editor.remove(KEY_EFFECTIVE_CAPABILITIES)
        } else {
            editor.putString(KEY_EFFECTIVE_CAPABILITIES, json)
        }
        editor.apply()
    }

    fun getCapabilityReasonsJson(): String? = prefs.getString(KEY_CAPABILITY_REASONS, null)

    fun setCapabilityReasonsJson(json: String?) {
        val editor = prefs.edit()
        if (json.isNullOrBlank()) {
            editor.remove(KEY_CAPABILITY_REASONS)
        } else {
            editor.putString(KEY_CAPABILITY_REASONS, json)
        }
        editor.apply()
    }

    fun getDevicePolicyJson(): String? = prefs.getString(KEY_DEVICE_POLICY_JSON, null)

    fun setDevicePolicyJson(json: String?) {
        val editor = prefs.edit()
        if (json.isNullOrBlank()) {
            editor.remove(KEY_DEVICE_POLICY_JSON)
        } else {
            editor.putString(KEY_DEVICE_POLICY_JSON, json)
        }
        editor.apply()
    }

    private fun parseDevicePolicy(): JSONObject? =
        getDevicePolicyJson()?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun getLockScreenMode(): String =
        parseDevicePolicy()?.optJSONObject("brief_profile")?.optString("lock_screen")
            ?.takeIf { it in listOf("full", "name_rating", "name_only", "hidden") }
            ?: "name_only"

    fun getOverlayPosition(): String =
        parseDevicePolicy()?.optJSONObject("overlay")?.optString("position")
            ?.takeIf { it == "top" || it == "bottom" }
            ?: "top"

    fun getOverlayAutoDismissSeconds(): Int =
        (parseDevicePolicy()?.optJSONObject("overlay")?.optInt("auto_dismiss_seconds", 0) ?: 0)
            .coerceIn(0, 60)

    fun getOverlayShowFor(): String =
        parseDevicePolicy()?.optJSONObject("overlay")?.optString("show_for")
            ?.takeIf { it == "incoming" || it == "incoming_and_outgoing" }
            ?: "incoming"

    fun getSyncStaleAfterMinutes(): Int =
        (parseDevicePolicy()?.optJSONObject("sync")?.optInt("stale_after_minutes", 60) ?: 60).coerceIn(1, 10080)

    fun getSyncFooterMode(): String =
        parseDevicePolicy()?.optJSONObject("sync")?.optString("show_sync_footer")
            ?.takeIf { it in listOf("always", "only_when_stale", "never") } ?: "always"

    fun getCallTextScale(): Float {
        val phoneScale = prefs.getFloat(KEY_CALL_TEXT_SCALE, -1f)
        val design = parseDevicePolicy()?.optJSONObject("call_screen")?.optDouble("text_scale", 1.0)?.toFloat() ?: 1f
        val policy = parseDevicePolicy()?.optJSONObject("call_screen_policy")
        val min = policy?.optDouble("min", 0.8)?.toFloat() ?: 0.8f
        val max = policy?.optDouble("max", 2.0)?.toFloat() ?: 2f
        return (if (phoneScale > 0f) phoneScale else design).coerceIn(min, max)
    }

    fun setCallTextScale(scale: Float?) { prefs.edit().apply { if (scale == null) remove(KEY_CALL_TEXT_SCALE) else putFloat(KEY_CALL_TEXT_SCALE, scale.coerceIn(0.8f, 2f)) }.apply() }
    fun getAppLanguage(): String = prefs.getString(KEY_APP_LANGUAGE, "follow_phone") ?: "follow_phone"
    fun setAppLanguage(language: String) { prefs.edit().putString(KEY_APP_LANGUAGE, language).apply() }
    fun getAppTextSize(): String = prefs.getString(KEY_APP_TEXT_SIZE, "default") ?: "default"
    fun setAppTextSize(size: String) { prefs.edit().putString(KEY_APP_TEXT_SIZE, size).apply() }

    fun getUnknownCallerMode(): String =
        parseDevicePolicy()?.optString("unknown_caller")
            ?.takeIf { it == "show_not_in_geargrid" || it == "show_nothing" }
            ?: "show_not_in_geargrid"

    fun isCallerBriefEnabled(): Boolean {
        val raw = getEffectiveCapabilitiesJson() ?: return true
        return runCatching { JSONObject(raw).optBoolean("caller_brief", true) }.getOrDefault(true)
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

    fun getContactSyncMode(): String {
        val explicit = prefs.getString(KEY_CONTACT_SYNC_MODE, null)
        if (!explicit.isNullOrBlank()) return explicit
        return if (isContactSyncEnabled()) "all" else "off"
    }

    fun setContactSyncMode(mode: String) {
        prefs.edit()
            .putString(KEY_CONTACT_SYNC_MODE, mode)
            .putBoolean(KEY_CONTACT_SYNC_ENABLED, mode != "off")
            .apply()
    }

    fun getSelectedContactLabels(): Set<String> = readStringSetPref(KEY_SELECTED_CONTACT_LABELS)
    fun setSelectedContactLabels(labels: Set<String>) {
        writeStringSetPref(KEY_SELECTED_CONTACT_LABELS, labels)
    }

    fun getSelectedContactAccounts(): Set<String> = readStringSetPref(KEY_SELECTED_CONTACT_ACCOUNTS)
    fun setSelectedContactAccounts(accounts: Set<String>) {
        writeStringSetPref(KEY_SELECTED_CONTACT_ACCOUNTS, accounts)
    }

    fun getExcludedContactKeys(): Set<String> = readStringSetPref(KEY_EXCLUDED_CONTACT_KEYS)
    fun setExcludedContactKeys(keys: Set<String>) {
        writeStringSetPref(KEY_EXCLUDED_CONTACT_KEYS, keys)
    }

    fun getCallLoggingScope(): String =
        prefs.getString(KEY_CALL_LOGGING_SCOPE, "customers_and_contacts")
            ?: "customers_and_contacts"

    fun setCallLoggingScope(scope: String) {
        prefs.edit().putString(KEY_CALL_LOGGING_SCOPE, scope).apply()
    }

    fun getSimFilter(): String? =
        prefs.getString(KEY_SIM_FILTER, null)?.trim()?.takeIf { it.isNotEmpty() && it != "all" }

    fun setSimFilter(sim: String?) {
        val editor = prefs.edit()
        val trimmed = sim?.trim()?.takeIf { it.isNotEmpty() && it != "all" }
        if (trimmed == null) {
            editor.remove(KEY_SIM_FILTER)
        } else {
            editor.putString(KEY_SIM_FILTER, trimmed)
        }
        editor.apply()
    }

    fun isStoreUnknownNumbersEnabled(): Boolean {
        val policy = parseDevicePolicy() ?: return true
        val logging = policy.optJSONObject("call_logging_policy")
            ?: policy.optJSONObject("call_logging")
            ?: return true
        return logging.optBoolean("store_unknown_numbers", true)
    }

    fun isWithinBusinessHours(): Boolean {
        val policy = parseDevicePolicy() ?: return true
        val logging = policy.optJSONObject("call_logging_policy")
            ?: policy.optJSONObject("call_logging")
            ?: return true
        if (!logging.optBoolean("business_hours_only", false)) return true
        val hours = logging.optJSONObject("business_hours") ?: return true
        val from = hours.optString("from", "08:00").takeIf { it.length == 5 } ?: "08:00"
        val to = hours.optString("to", "18:00").takeIf { it.length == 5 } ?: "18:00"
        val current = ZonedDateTime.now(ZoneId.of("Asia/Colombo"))
            .format(DateTimeFormatter.ofPattern("HH:mm"))
        return if (from <= to) current in from..to else (current >= from || current <= to)
    }

    fun isAfterCallCardEnabled(): Boolean = prefs.getBoolean(KEY_AFTER_CALL_ENABLED, true)

    fun setAfterCallCardEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AFTER_CALL_ENABLED, enabled).apply()
    }

    fun getAfterCallTags(): List<String> {
        val raw = prefs.getString(KEY_AFTER_CALL_TAGS, null)
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it, "").trim().takeIf { s -> s.isNotEmpty() } }
            }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        return DEFAULT_AFTER_CALL_TAGS
    }

    fun setAfterCallTags(tags: List<String>) {
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }
        val arr = JSONArray()
        (if (clean.isEmpty()) DEFAULT_AFTER_CALL_TAGS else clean).forEach { arr.put(it) }
        prefs.edit().putString(KEY_AFTER_CALL_TAGS, arr.toString()).apply()
    }

    fun getAfterCallDismissSeconds(): Int =
        prefs.getInt(KEY_AFTER_CALL_DISMISS_SEC, 25).coerceIn(0, 120)

    fun setAfterCallDismissSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_AFTER_CALL_DISMISS_SEC, seconds.coerceIn(0, 120)).apply()
    }

    fun getMinAppVersion(): String =
        prefs.getString(KEY_MIN_APP_VERSION, "1.0.0")?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0"

    fun setMinAppVersion(version: String) {
        prefs.edit().putString(KEY_MIN_APP_VERSION, version.trim().ifEmpty { "1.0.0" }).apply()
    }

    fun getLatestAppVersion(): String =
        prefs.getString(KEY_LATEST_APP_VERSION, "1.0.0")?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0"

    fun setLatestAppVersion(version: String) {
        prefs.edit().putString(KEY_LATEST_APP_VERSION, version.trim().ifEmpty { "1.0.0" }).apply()
    }

    fun getBriefProfileJson(): JSONObject? = parseDevicePolicy()?.optJSONObject("brief_profile")

    private fun readStringSetPref(key: String): Set<String> {
        val raw = prefs.getString(key, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            val set = linkedSetOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "").trim()
                if (item.isNotEmpty()) set.add(item)
            }
            set
        }.getOrDefault(emptySet())
    }

    private fun writeStringSetPref(key: String, values: Set<String>) {
        val arr = JSONArray()
        values.map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    fun hasContactsConsent(): Boolean = prefs.getBoolean(KEY_CONTACTS_CONSENTED, false)
    fun setContactsConsented(consented: Boolean) {
        prefs.edit().putBoolean(KEY_CONTACTS_CONSENTED, consented).apply()
    }

    fun getContactsSyncedAt(): String? = prefs.getString(KEY_CONTACTS_SYNCED_AT, null)
    fun setContactsSyncedAt(iso: String?) {
        prefs.edit().apply {
            if (iso.isNullOrBlank()) remove(KEY_CONTACTS_SYNCED_AT) else putString(KEY_CONTACTS_SYNCED_AT, iso)
        }.apply()
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
        private const val KEY_TOKEN_ISSUED_AT_MS = "token_issued_at_ms"
        private const val KEY_LAST_SYNC_SUCCESS_MS = "last_sync_success_ms"
        private const val KEY_OFFLINE_WIPE_DAYS = "offline_wipe_days"
        private const val KEY_DEVICE_STATUS = "device_status"
        private const val KEY_EFFECTIVE_CAPABILITIES = "effective_capabilities"
        private const val KEY_CAPABILITY_REASONS = "capability_reasons"
        private const val KEY_DEVICE_POLICY_JSON = "device_policy_json"
        private const val KEY_CONTACT_SYNC_MODE = "contact_sync_mode"
        private const val KEY_SELECTED_CONTACT_LABELS = "selected_contact_labels"
        private const val KEY_SELECTED_CONTACT_ACCOUNTS = "selected_contact_accounts"
        private const val KEY_EXCLUDED_CONTACT_KEYS = "excluded_contact_keys"
        private const val KEY_CALL_LOGGING_SCOPE = "call_logging_scope"
        private const val KEY_SIM_FILTER = "sim_filter"
        private const val KEY_AFTER_CALL_ENABLED = "after_call_enabled"
        private const val KEY_AFTER_CALL_TAGS = "after_call_tags"
        private const val KEY_AFTER_CALL_DISMISS_SEC = "after_call_dismiss_sec"
        private const val KEY_MIN_APP_VERSION = "min_app_version"
        private const val KEY_LATEST_APP_VERSION = "latest_app_version"
        private const val KEY_CALL_TEXT_SCALE = "call_text_scale"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_APP_TEXT_SIZE = "app_text_size"
        private val DEFAULT_AFTER_CALL_TAGS = listOf(
            "Booking",
            "Payment promise",
            "Extension",
            "Complaint",
            "Price enquiry",
            "Wrong number"
        )
    }
}
