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
        Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = "notifications")
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
    fun searchOfflineBriefs(call: PluginCall) {
        val rawQuery = call.getString("query")?.trim().orEmpty()
        val limit = (call.getInt("limit") ?: 25).coerceIn(1, 50)
        if (rawQuery.length < 2) {
            call.resolve(JSObject().apply { put("briefs", JSArray()) })
            return
        }
        val digits = rawQuery.filter { it.isDigit() }
        val profile = store.getBriefProfileJson()
        val showDebt = profile?.optBoolean("show_debt", true) ?: true
        val showRating = profile?.optBoolean("show_rating", true) ?: true
        val showCompany = profile?.optBoolean("show_company", true) ?: true
        val showIdStatus = profile?.optBoolean("show_id_status", true) ?: true

        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getInstance(context).briefDao()
            val matches = dao.searchBriefs(rawQuery, digits, limit)
            val arr = JSArray()
            for (b in matches) {
                val phones = dao.getPhonesForCustomer(b.customerId)
                arr.put(JSObject().apply {
                    put("customer_id", b.customerId)
                    put("display_name", b.displayName)
                    if (showCompany) put("company_name", b.companyName)
                    if (showRating && b.rating != null) put("rating", b.rating)
                    put("status", b.status)
                    if (showDebt) put("balance_due", b.balanceDue)
                    if (showIdStatus) put("id_retained", b.idRetained)
                    put("summary_line", b.summaryLine)
                    put("phones", JSArray().apply { phones.forEach { put(it) } })
                })
            }
            call.resolve(JSObject().apply { put("briefs", arr) })
        }
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
            put("battery_optimization_exempt", status.optBoolean("batteryOptimizationExempt", false))
        }
        val res = DeviceApiClient(store).post("/device/register", JSONObject().apply {
            put("app_version", getAppVersionName())
            put("contact_sync_enabled", store.isContactSyncEnabled())
            if (deviceModel.isNotEmpty()) put("device_model", deviceModel)
            if (osVersion.isNotEmpty()) put("os_version", osVersion)
            put("reported_state", reportedState)
            store.getPushToken()?.let { put("push_token", it) }
        })
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
