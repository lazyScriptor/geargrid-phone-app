package live.geargrid.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import live.geargrid.phone.api.DeviceApiClient
import org.json.JSONObject

class GearGridMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val store = CredentialStore(applicationContext)
        store.setPushToken(token)

        if (!store.hasCredentials()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val versionName = try {
                    applicationContext.packageManager
                        .getPackageInfo(applicationContext.packageName, 0)
                        .versionName ?: "1.0.0"
                } catch (_: Exception) {
                    "1.0.0"
                }
                DeviceApiClient(store).post(
                    "/device/register",
                    JSONObject().apply {
                        put("app_version", versionName)
                        put("push_token", token)
                        put("contact_sync_enabled", store.isContactSyncEnabled())
                    }
                )
            } catch (_: Exception) {
                // Next foreground resume or registerDeviceWithServer call will retry
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        ensureNotificationChannels(applicationContext)

        val data = message.data
        val title = message.notification?.title
            ?: data["title"]
            ?: getString(R.string.app_name)
        val body = message.notification?.body
            ?: data["body"]
            ?: return
        val silent = data["silent"] == "true"
        val type = data["type"].orEmpty()
        val invoiceId = data["invoice_id"].orEmpty()

        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (type.isNotEmpty()) putExtra(EXTRA_PUSH_TYPE, type)
            if (invoiceId.isNotEmpty()) putExtra(EXTRA_INVOICE_ID, invoiceId)
        }

        val requestCode = invoiceId.toIntOrNull() ?: (System.currentTimeMillis() and 0x7fffffff).toInt()
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smallIcon = applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.ic_dialog_info

        val channelId = if (silent) CHANNEL_BILLS_SILENT else CHANNEL_BILLS
        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setSilent(silent)
            .setPriority(
                if (silent) NotificationCompat.PRIORITY_LOW
                else NotificationCompat.PRIORITY_HIGH
            )

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val notificationId = invoiceId.toIntOrNull() ?: (System.currentTimeMillis() % 100_000L).toInt()
        manager.notify(notificationId, builder.build())
    }

    companion object {
        const val CHANNEL_BILLS = "bills"
        const val CHANNEL_BILLS_SILENT = "bills_silent"
        const val CHANNEL_CALLS = "calls"
        const val EXTRA_PUSH_TYPE = "type"
        const val EXTRA_INVOICE_ID = "invoice_id"

        fun ensureNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val billsChannel = NotificationChannel(
                CHANNEL_BILLS,
                "Rental Bills & Payments",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time alerts for new invoices, payments, returns, and contact events."
                enableVibration(true)
            }

            val silentChannel = NotificationChannel(
                CHANNEL_BILLS_SILENT,
                "Rental Bills (Quiet Hours)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silent push alerts delivered during shop quiet hours."
                setSound(null, null)
                enableVibration(false)
            }

            val callsChannel = NotificationChannel(
                CHANNEL_CALLS,
                "Incoming Caller Briefs",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Displays customer balance and rental briefs during phone calls."
                enableVibration(false)
                setSound(null, null)
            }

            manager.createNotificationChannel(billsChannel)
            manager.createNotificationChannel(silentChannel)
            manager.createNotificationChannel(callsChannel)
        }
    }
}
