package live.geargrid.phone

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import live.geargrid.phone.api.DeviceApiClient
import org.json.JSONObject

/**
 * Tells the server about this phone's Android permissions whenever they change.
 *
 * They used to be reported only at pairing, so permissions granted afterwards in Android Settings
 * never reached the server and the device card kept showing them as off.
 */
object DeviceStateReporter {

    private const val PREFS = "geargrid_state_report"
    private const val KEY_LAST_SENT = "last_sent_state"

    fun currentState(context: Context): JSONObject {
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val has = { perm: String ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
        return JSONObject().apply {
            put("call_screening_role", roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true)
            put("overlay_permission", Settings.canDrawOverlays(context))
            put(
                "notifications_permission",
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(Manifest.permission.POST_NOTIFICATIONS),
            )
            put("contacts_permission", has(Manifest.permission.READ_CONTACTS))
            put(
                "call_log_permission",
                has(Manifest.permission.READ_CALL_LOG) && has(Manifest.permission.READ_PHONE_STATE),
            )
            put("battery_optimization_exempt", pm?.isIgnoringBatteryOptimizations(context.packageName) == true)
        }
    }

    /** Sends the state in the background if it differs from what was last sent. */
    fun reportIfChanged(context: Context, appVersion: String) {
        val appContext = context.applicationContext
        val store = CredentialStore(appContext)
        if (!store.hasCredentials()) return
        val state = currentState(appContext)
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val serialized = state.toString()
        if (prefs.getString(KEY_LAST_SENT, null) == serialized) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                DeviceApiClient(store).post("/device/register", JSONObject().apply {
                    put("app_version", appVersion)
                    put("contact_sync_enabled", store.isContactSyncEnabled())
                    put("reported_state", state)
                })
                prefs.edit().putString(KEY_LAST_SENT, serialized).apply()
            } catch (_: Exception) {
                // Not saved as sent, so the next resume tries again.
            }
        }
    }
}
