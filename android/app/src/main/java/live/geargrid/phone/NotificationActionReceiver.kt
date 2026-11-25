package live.geargrid.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import live.geargrid.phone.api.DeviceApiClient
import org.json.JSONObject

/**
 * Handles notification shade action buttons ("Mark checked") without opening the app.
 * The button always answers with a short message, so a tap never looks like it did nothing.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!intent.action.orEmpty().startsWith(ACTION_MARK_CHECKED)) return
        val eventId = intent.getStringExtra(GearGridMessagingService.EXTRA_EVENT_ID)?.trim()?.toLongOrNull() ?: return
        val notificationId = intent.getIntExtra(GearGridMessagingService.EXTRA_NOTIFICATION_ID, -1)

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = CredentialStore(appContext)
                val language = PhoneStrings.resolveLanguage(store.getAppLanguage())
                val messageKey = if (!store.hasCredentials()) {
                    "check_failed"
                } else {
                    try {
                        DeviceApiClient(store).post("/device/activity/$eventId/check", JSONObject())
                        if (notificationId != -1) NotificationIds.cancelAndTidy(appContext, notificationId)
                        "check_done"
                    } catch (e: DeviceApiClient.HttpException) {
                        if (e.code == 403) "check_not_allowed" else "check_failed"
                    } catch (_: Exception) {
                        // Offline: the notification stays, so it can be tried again
                        "check_failed"
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, PhoneStrings.get(language, messageKey), Toast.LENGTH_SHORT).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_CHECKED = "live.geargrid.phone.ACTION_MARK_CHECKED"
    }
}
