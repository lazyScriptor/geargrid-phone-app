package live.geargrid.phone

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResult
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import live.geargrid.phone.api.DeviceApiClient
import live.geargrid.phone.call.CallRecordingMedia
import live.geargrid.phone.db.AppDatabase
import live.geargrid.phone.sync.BriefSyncWorker
import live.geargrid.phone.sync.ContactSyncWorker
import live.geargrid.phone.sync.ContactsObserver
import live.geargrid.phone.sync.OutboxWorker
import org.json.JSONObject

@CapacitorPlugin(
    name = "PhoneAssistant",
    permissions = [
        Permission(strings = [Manifest.permission.READ_CONTACTS], alias = "contacts"),
        Permission(strings = [Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE], alias = "call_log"),
        Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = "notifications"),
        Permission(strings = [Manifest.permission.READ_MEDIA_AUDIO], alias = "audio"),
        Permission(strings = [Manifest.permission.READ_EXTERNAL_STORAGE], alias = "storage_audio")
    ]
)
class PhoneAssistantPlugin : Plugin() {

    private lateinit var store: CredentialStore

    override fun load() {
        store = CredentialStore(context)
        if (store.hasCredentials()) {
            BriefSyncWorker.schedulePeriodic(context)
            BriefSyncWorker.enqueueOneShot(context)
            OutboxWorker.enqueueOneShot(context)
            if (store.isContactSyncEnabled() && store.hasContactsConsent()) {
                ContactSyncWorker.schedulePeriodic(context)
                ContactsObserver.register(context)
            }
        }
    }

    @PluginMethod
    fun setCredentials(call: PluginCall) {
        val deviceId = call.getString("deviceId")?.trim().orEmpty()
        val deviceToken = call.getString("deviceToken")?.trim().orEmpty()
        val tenantDb = call.getString("tenantDb")?.trim().orEmpty()
        val apiBaseUrl = call.getString("apiBaseUrl")?.trim()

        if (deviceId.isEmpty() || deviceToken.isEmpty() || tenantDb.isEmpty()) {
            call.reject("deviceId, deviceToken, and tenantDb are required")
            return
        }

        store.setCredentials(deviceId, deviceToken, tenantDb, apiBaseUrl)
        BriefSyncWorker.schedulePeriodic(context)
        BriefSyncWorker.enqueueOneShot(context)
        OutboxWorker.enqueueOneShot(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                registerDeviceOnServer(store)
            } catch (_: Exception) {
                // Retry on next app resume / sync
            }
            call.resolve(JSObject().apply { put("saved", true) })
        }
    }

    @PluginMethod
    fun clearCredentials(call: PluginCall) {
        CoroutineScope(Dispatchers.IO).launch {
            RevocationHandler.wipeSuspend(context, keepCredentials = false)
            call.resolve(JSObject().apply { put("cleared", true) })
        }
    }

    /** The web pages keep their own copy of the device token, which goes stale when this app rotates it. */
    @PluginMethod
    fun getDeviceToken(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("deviceId", store.getDeviceId() ?: "")
            put("deviceToken", store.getDeviceToken() ?: "")
        })
    }

    /** The app icon badge follows the feed: with nothing unread, its notifications go away. */
    @PluginMethod
    fun syncBadge(call: PluginCall) {
        if (call.getInt("unread") == 0) NotificationIds.clearFeed(context)
        call.resolve()
    }

    @PluginMethod
    fun getSetupStatus(call: PluginCall) {
        call.resolve(buildSetupStatus())
    }

    @PluginMethod
    fun requestCallScreeningRole(call: PluginCall) {
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                call.resolve(buildSetupStatus())
                return
            }
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            startActivityForResult(call, intent, "onActivityResult")
            return
        }
        call.resolve(buildSetupStatus())
    }

    @PluginMethod
    fun requestOverlayPermission(call: PluginCall) {
        if (Settings.canDrawOverlays(context)) {
            call.resolve(buildSetupStatus())
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        startActivityForResult(call, intent, "onActivityResult")
    }

    @PluginMethod
    fun requestNotificationsPermission(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            call.resolve(buildSetupStatus())
            return
        }
        requestPermissionForAlias("notifications", call, "onPermissionResult")
    }

    @PluginMethod
    fun requestContactsPermission(call: PluginCall) {
        requestPermissionForAlias("contacts", call, "onPermissionResult")
    }

    @PluginMethod
    fun requestCallLogPermission(call: PluginCall) {
        requestPermissionForAlias("call_log", call, "onPermissionResult")
    }

    @PluginMethod
    fun requestAudioPermission(call: PluginCall) {
        if (CallRecordingMedia.hasPermission(context)) {
            call.resolve(buildSetupStatus())
            return
        }
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "audio" else "storage_audio"
        requestPermissionForAlias(alias, call, "onPermissionResult")
    }

    @PluginMethod
    fun requestBatteryExemption(call: PluginCall) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isIgnoringBatteryOptimizations(context.packageName) == true) {
            call.resolve(buildSetupStatus())
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        startActivityForResult(call, intent, "onActivityResult")
    }

    @PluginMethod
    fun openVendorAutostart(call: PluginCall) {
        val opened = tryVendorIntents()
        call.resolve(JSObject().apply { put("opened", opened) })
    }

    @PluginMethod
    fun syncNow(call: PluginCall) {
        if (store.hasCredentials()) {
            BriefSyncWorker.enqueueOneShot(context)
            OutboxWorker.enqueueOneShot(context)
            if (store.getContactSyncMode() != "off" && store.hasContactsConsent()) {
                ContactSyncWorker.enqueueOneShot(context)
            }
        }
        call.resolve(buildSetupStatus())
    }

    @PluginMethod
    fun wipeLocalContacts(call: PluginCall) {
        store.setContactsSyncedAt(null)
        store.setContactSyncMode("off")
        ContactSyncWorker.cancelPeriodic(context)
        ContactsObserver.unregister(context)
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getInstance(context).contactSyncDao()
            dao.clearAllState()
            dao.clearAllContactIndex()
            call.resolve(JSObject().apply { put("wiped", true) })
        }
    }

    @PluginMethod
    fun updatePrivacySelection(call: PluginCall) {
        call.getString("contactSyncMode")?.takeIf { it.isNotBlank() }?.let { mode ->
            store.setContactSyncMode(mode)
            if (mode == "off") {
                ContactSyncWorker.cancelPeriodic(context)
                ContactsObserver.unregister(context)
            } else if (store.hasContactsConsent() && hasPerm(Manifest.permission.READ_CONTACTS)) {
                ContactSyncWorker.schedulePeriodic(context)
                ContactsObserver.register(context)
                ContactSyncWorker.enqueueOneShot(context)
            }
        }
        call.getString("callLoggingScope")?.takeIf { it.isNotBlank() }?.let { scope ->
            store.setCallLoggingScope(scope)
        }
        if (call.data.has("simFilter")) {
            store.setSimFilter(call.getString("simFilter"))
        }
        call.getArray("selectedLabels")?.let { arr ->
            val set = linkedSetOf<String>()
            for (i in 0 until arr.length()) {
                arr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { set.add(it) }
            }
            store.setSelectedContactLabels(set)
        }
        call.getArray("selectedAccounts")?.let { arr ->
            val set = linkedSetOf<String>()
            for (i in 0 until arr.length()) {
                arr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { set.add(it) }
            }
            store.setSelectedContactAccounts(set)
        }
        call.getArray("excludedContactKeys")?.let { arr ->
            val set = linkedSetOf<String>()
            for (i in 0 until arr.length()) {
                arr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { set.add(it) }
            }
            store.setExcludedContactKeys(set)
        }
        call.resolve(buildSetupStatus())
    }

    @PluginMethod
    fun searchCustomers(call: PluginCall) {
        val query = call.getString("query")?.trim().orEmpty()
        val limit = (call.getInt("limit") ?: 15).coerceIn(1, 30)
        CoroutineScope(Dispatchers.IO).launch {
            val result = OfflineSearch.search(context, query, limit)
            val hits = JSArray()
            for (h in result.hits) {
                hits.put(JSObject().apply {
                    put("kind", h.kind)
                    put("id", h.id)
                    put("name", h.name)
                    h.company?.let { put("company", it) }
                    h.phone?.let { put("phone", it) }
                    h.rating?.let { put("rating", it) }
                    h.status?.let { put("status", it) }
                    h.summary?.let { put("summary", it) }
                    put("hasDues", h.hasDues)
                })
            }
            call.resolve(JSObject().apply {
                put("hits", hits)
                put("cachedBriefs", result.cachedBriefs)
            })
        }
    }

    @PluginMethod
    fun getSyncStatus(call: PluginCall) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(context)
            val cached = db.briefDao().countBriefs()
            val outbox = db.contactSyncDao().countOutbox()
            call.resolve(JSObject().apply {
                put("lastBriefSyncAt", store.getBriefsSyncedAt())
                put("lastContactSyncAt", store.getContactsSyncedAt())
                put("cachedBriefs", cached)
                put("outboxPending", outbox)
                put("staleAfterMinutes", store.getSyncStaleAfterMinutes())
                put("showSyncFooter", store.getSyncFooterMode())
                put("online", isOnline())
                store.getLastSyncResultJson()?.let { raw ->
                    runCatching { put("lastResult", JSObject(raw)) }
                }
            })
        }
    }

    @PluginMethod
    fun showSampleCall(call: PluginCall) {
        val canDraw = Settings.canDrawOverlays(context)
        if (canDraw) live.geargrid.phone.call.CallerOverlayManager.showSample(context)
        call.resolve(JSObject().apply { put("shown", canDraw) })
    }

    @PluginMethod
    fun setCallTextScale(call: PluginCall) {
        val scale: Float? = if (call.data.isNull("scale")) null else call.getFloat("scale")
        store.setCallTextScale(scale)
        call.resolve(JSObject().apply { put("saved", true) })
    }

    @PluginMethod
    fun getAppPrefs(call: PluginCall) {
        val language = store.getAppLanguage().let { if (it == "en" || it == "si" || it == "ta") it else "follow" }
        call.resolve(JSObject().apply {
            put("appLanguage", language)
            put("appTextSize", store.getAppTextSize())
            store.getPhoneCallTextScale()?.let { put("callTextScale", it.toDouble()) }
            put("appLockEnabled", store.isAppLockEnabled())
            put("appVersion", getAppVersionName())
        })
    }

    @PluginMethod
    fun setAppLanguage(call: PluginCall) {
        store.setAppLanguage(call.getString("language") ?: "follow")
        call.resolve(JSObject().apply { put("saved", true) })
    }

    @PluginMethod
    fun setAppTextSize(call: PluginCall) {
        val size = call.getString("size") ?: "default"
        store.setAppTextSize(if (size in listOf("small", "default", "large", "extra_large")) size else "default")
        call.resolve(JSObject().apply { put("saved", true) })
    }

    @PluginMethod
    fun setAppLock(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: false
        // A shop that requires the lock cannot be overridden from the phone.
        store.setAppLockEnabled(enabled || store.requireAppLockByShop())
        call.resolve(JSObject().apply { put("saved", true) })
    }

    @PluginMethod
    fun authenticateAppLock(call: PluginCall) {
        val act = activity as? androidx.fragment.app.FragmentActivity
        if (act == null) {
            call.resolve(JSObject().apply { put("ok", false); put("error", "no_activity") })
            return
        }
        AppLock.authenticate(act, "GearGrid") { ok, error ->
            call.resolve(JSObject().apply {
                put("ok", ok)
                if (error != null) put("error", error)
            })
        }
    }

    @PluginMethod
    fun setSignedIn(call: PluginCall) {
        store.setFullAppSignedIn(call.getBoolean("signedIn") ?: false)
        call.resolve(JSObject().apply { put("saved", true) })
    }

    @PluginMethod
    fun setStatusBarTheme(call: PluginCall) {
        val dark = call.getBoolean("dark") ?: false
        val color = call.getString("color")
        (activity as? MainActivity)?.applyStatusBarTheme(dark, color)
        call.resolve(JSObject().apply { put("applied", true) })
    }

    @PluginMethod
    fun sharePdf(call: PluginCall) {
        val html = call.getString("html").orEmpty()
        val filename = call.getString("filename") ?: "receipt.pdf"
        val widthMm = call.getInt("widthMm") ?: 72
        val act = activity
        if (html.isBlank() || act == null) {
            call.resolve(JSObject().apply { put("shared", false); put("error", "empty") })
            return
        }
        PdfShare.share(act, html, filename, widthMm) { shared, error ->
            call.resolve(JSObject().apply {
                put("shared", shared)
                if (error != null) put("error", error)
            })
        }
    }

    @PluginMethod
    fun listSims(call: PluginCall) {
        val sims = JSArray()
        runCatching {
            val sm = context.getSystemService(android.telephony.SubscriptionManager::class.java)
            val list = if (hasPerm(Manifest.permission.READ_PHONE_STATE)) sm?.activeSubscriptionInfoList else null
            list?.forEach { info ->
                val label = info.displayName?.toString()?.trim().orEmpty().ifBlank { "SIM ${info.simSlotIndex + 1}" }
                sims.put(JSObject().apply {
                    put("id", label)
                    put("label", "$label (SIM ${info.simSlotIndex + 1})")
                })
            }
        }
        call.resolve(JSObject().apply { put("sims", sims) })
    }

    @PluginMethod
    fun getAlerts(call: PluginCall) {
        val arr = JSArray()
        val stored = AlertStore.readAll(store)
        for (i in 0 until stored.length()) {
            val o = stored.optJSONObject(i) ?: continue
            arr.put(JSObject(o.toString()))
        }
        call.resolve(JSObject().apply { put("alerts", arr) })
    }

    @PluginMethod
    fun clearAlerts(call: PluginCall) {
        AlertStore.clear(store)
        call.resolve(JSObject().apply { put("cleared", true) })
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @PluginMethod
    fun setContactSyncEnabled(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: false
        // Turning sync on needs READ_CONTACTS first, so ask before saving anything.
        if (enabled && !hasPerm(Manifest.permission.READ_CONTACTS)) {
            requestPermissionForAlias("contacts", call, "onContactsPermissionForSync")
            return
        }
        applyContactSync(call, enabled)
    }

    @PermissionCallback
    private fun onContactsPermissionForSync(call: PluginCall) {
        if (hasPerm(Manifest.permission.READ_CONTACTS)) {
            applyContactSync(call, true)
        } else {
            // Denied: leave sync off and report the unchanged status.
            call.resolve(buildSetupStatus())
        }
    }

    private fun applyContactSync(call: PluginCall, enabled: Boolean) {
        val consented = call.getBoolean("consented") ?: enabled
        store.setContactSyncMode(if (enabled) "all" else "off")
        if (enabled && consented) {
            store.setContactsConsented(true)
            ContactSyncWorker.schedulePeriodic(context)
            ContactsObserver.register(context)
            ContactSyncWorker.enqueueOneShot(context)
        } else if (!enabled) {
            ContactSyncWorker.cancelPeriodic(context)
            ContactsObserver.unregister(context)
        }

        CoroutineScope(Dispatchers.IO).launch {
            if (!enabled) {
                val dao = AppDatabase.getInstance(context).contactSyncDao()
                dao.clearAllState()
                dao.clearAllContactIndex()
            }
            if (store.hasCredentials()) {
                try {
                    registerDeviceOnServer(store)
                } catch (_: Exception) {
                    // Best-effort sync with server; retried when next online
                }
            }
            call.resolve(buildSetupStatus())
        }
    }

    @ActivityCallback
    private fun onActivityResult(call: PluginCall, @Suppress("UNUSED_PARAMETER") result: ActivityResult) {
        call.resolve(buildSetupStatus())
    }

    @PermissionCallback
    private fun onPermissionResult(call: PluginCall) {
        // Sync may already be on from before the permission was granted; start watching now.
        if (store.isContactSyncEnabled() && store.hasContactsConsent() &&
            hasPerm(Manifest.permission.READ_CONTACTS)
        ) {
            ContactsObserver.register(context)
            ContactSyncWorker.enqueueOneShot(context)
        }
        call.resolve(buildSetupStatus())
    }

    private fun buildSetupStatus(): JSObject {
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        val callScreeningHeld =
            roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        val overlayGranted = Settings.canDrawOverlays(context)
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPerm(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        val contactsGranted = hasPerm(Manifest.permission.READ_CONTACTS)
        val callLogGranted =
            hasPerm(Manifest.permission.READ_CALL_LOG) && hasPerm(Manifest.permission.READ_PHONE_STATE)
        val audioGranted = CallRecordingMedia.hasPermission(context)
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryExempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true

        if (store.hasCredentials()) {
            DeviceApiClient(store).checkOfflineWipe()
        }
        val deviceModel = "${Build.MANUFACTURER ?: ""} ${Build.MODEL ?: ""}".trim()
        val osVersion = ("Android " + (Build.VERSION.RELEASE ?: "")).trim()
        return JSObject().apply {
            put("isNative", true)
            put("hasCredentials", store.hasCredentials())
            put("callScreeningRole", callScreeningHeld)
            put("overlayPermission", overlayGranted)
            put("notificationsPermission", notificationsGranted)
            put("contactsPermission", contactsGranted)
            put("callLogPermission", callLogGranted)
            put("audioPermission", audioGranted)
            put("callRecordingEnabled", store.isCallRecordingEnabled())
            put("callRecordingSetupAllowed", store.canSetUpCallRecording())
            put("batteryOptimizationExempt", batteryExempt)
            put("contactSyncEnabled", store.isContactSyncEnabled())
            put("contactSyncMode", store.getContactSyncMode())
            put("callLoggingScope", store.getCallLoggingScope())
            put("simFilter", store.getSimFilter())
            put("contactsConsented", store.hasContactsConsent())
            put("contactsSyncedAt", store.getContactsSyncedAt())
            put("contactsSyncError", store.getContactsSyncError())
            put("briefsSyncedAt", store.getBriefsSyncedAt())
            put("manufacturer", Build.MANUFACTURER ?: "Android")
            put("deviceModel", deviceModel)
            put("osVersion", osVersion)
            put("appVersion", getAppVersionName())
            put("minAppVersion", store.getMinAppVersion())
            put("latestAppVersion", store.getLatestAppVersion())
            put("deviceStatus", store.getDeviceStatus())
            put("offlineWipeDays", store.getOfflineWipeDays())
            put("effectiveCapabilitiesJson", store.getEffectiveCapabilitiesJson())
            put("capabilityReasonsJson", store.getCapabilityReasonsJson())
            put("reportedState", JSObject().apply {
                put("call_screening_role", callScreeningHeld)
                put("overlay_permission", overlayGranted)
                put("notifications_permission", notificationsGranted)
                put("contacts_permission", contactsGranted)
                put("call_log_permission", callLogGranted)
                put("audio_permission", audioGranted)
                put("battery_optimization_exempt", batteryExempt)
            })
        }
    }

    private fun registerDeviceOnServer(store: CredentialStore) {
        val status = buildSetupStatus()
        val deviceModel = "${Build.MANUFACTURER ?: ""} ${Build.MODEL ?: ""}".trim().take(100)
        val osVersion = ("Android " + (Build.VERSION.RELEASE ?: "")).trim().take(20)
        val reportedState = JSONObject().apply {
            put("call_screening_role", status.optBoolean("callScreeningRole", false))
            put("overlay_permission", status.optBoolean("overlayPermission", false))
            put("notifications_permission", status.optBoolean("notificationsPermission", false))
            put("contacts_permission", status.optBoolean("contactsPermission", false))
            put("call_log_permission", status.optBoolean("callLogPermission", false))
            put("audio_permission", status.optBoolean("audioPermission", false))
            put("battery_optimization_exempt", status.optBoolean("batteryOptimizationExempt", false))
        }
        val res = DeviceApiClient(store).post("/device/register", JSONObject().apply {
            put("app_version", getAppVersionName())
            put("contact_sync_enabled", store.isContactSyncEnabled())
            if (deviceModel.isNotEmpty()) put("device_model", deviceModel)
            if (osVersion.isNotEmpty()) put("os_version", osVersion)
            put("reported_state", reportedState)
            store.getPushToken()?.let { put("push_token", it) }
        }).optJSONObject("data") ?: return
        res.optString("device_status", "").trim().takeIf { it.isNotEmpty() }?.let {
            store.setDeviceStatus(it)
        }
        if (res.has("offline_wipe_days")) {
            val days = res.optInt("offline_wipe_days", store.getOfflineWipeDays())
            if (days in 1..30) store.setOfflineWipeDays(days)
        }
        res.optJSONObject("effective_capabilities")?.let {
            store.setEffectiveCapabilitiesJson(it.toString())
        }
        res.optJSONObject("capability_reasons")?.let {
            store.setCapabilityReasonsJson(it.toString())
        }
    }

    private fun hasPerm(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun getAppVersionName(): String = try {
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        pkgInfo.versionName ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }

    private fun tryVendorIntents(): Boolean {
        val candidates = listOf(
            // Samsung One UI battery/sleeping apps settings
            Intent().setComponent(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            ),
            // Vivo Funtouch / OriginOS autostart & background power management
            Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                )
            ),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )

        for (intent in candidates) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
                // Try next OEM component
            }
        }
        return false
    }
}
