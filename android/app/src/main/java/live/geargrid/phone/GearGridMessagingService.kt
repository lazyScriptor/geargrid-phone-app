package live.geargrid.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
        val type = data["type"].orEmpty()
        // The push token survives re-pairing, so a revoke or wipe sent for an old pairing can arrive
        // after the phone paired again. Ignore device-state messages naming a different device id.
        val targetDeviceId = data["device_id"].orEmpty()
        val currentDeviceId = CredentialStore(applicationContext).getDeviceId().orEmpty()
        if (type in DEVICE_STATE_TYPES && targetDeviceId.isNotEmpty() && targetDeviceId != currentDeviceId) {
            return
        }
        if (type == "device_revoked") {
            RevocationHandler.wipe(applicationContext, keepCredentials = false)
            return
        }
        if (type == "device_suspended") {
            CredentialStore(applicationContext).setDeviceStatus("SUSPENDED")
            RevocationHandler.signOutFullApp(applicationContext)
            RevocationHandler.wipe(applicationContext, keepCredentials = true)
            return
        }
        if (type == "wipe_cache") {
            RevocationHandler.wipe(applicationContext, keepCredentials = true)
            return
        }
        if (type == "device_resumed" || type == "device_approved") {
            CredentialStore(applicationContext).setDeviceStatus("ACTIVE")
            live.geargrid.phone.sync.BriefSyncWorker.enqueueOneShot(applicationContext)
            return
        }
        if (type == "sample_call") {
            live.geargrid.phone.call.CallerOverlayManager.showSample(applicationContext)
            return
        }
        if (type == "policy_changed") {
            live.geargrid.phone.sync.BriefSyncWorker.enqueueOneShot(applicationContext)
            return
        }
        if (type == "dial") {
            val phone = data["phone"]?.trim().orEmpty()
                .ifEmpty { data["phone_e164"]?.trim()?.let { "+$it" }.orEmpty() }
            if (phone.isNotEmpty()) {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { applicationContext.startActivity(dialIntent) }

                val smallIcon = applicationInfo.icon.takeIf { it != 0 }
                    ?: android.R.drawable.sym_call_outgoing
                val dialPending = PendingIntent.getActivity(
                    applicationContext,
                    (System.currentTimeMillis() and 0x7fffffff).toInt(),
                    dialIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val dialNotif = NotificationCompat.Builder(applicationContext, CHANNEL_CALLS)
                    .setSmallIcon(smallIcon)
                    .setContentTitle("Click to call")
                    .setContentText("Tap to dial $phone")
                    .setAutoCancel(true)
                    .setContentIntent(dialPending)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.notify(9102, dialNotif)
            }
            return
        }

        val title = message.notification?.title
            ?: data["title"]?.takeIf { it.isNotBlank() }
            ?: getString(R.string.app_name)
        val body = message.notification?.body
            ?: data["body"]?.takeIf { it.isNotBlank() }
            ?: return
        val silent = data["silent"] == "true"
        val invoiceId = data["invoice_id"].orEmpty()
        val eventId = data["event_id"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: data["activity_event_id"]?.trim().orEmpty()

        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (type.isNotEmpty()) putExtra(EXTRA_PUSH_TYPE, type)
            if (invoiceId.isNotEmpty()) putExtra(EXTRA_INVOICE_ID, invoiceId)
            if (eventId.isNotEmpty()) putExtra(EXTRA_EVENT_ID, eventId)
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

        val channelId = if (silent) {
            CHANNEL_BILLS_SILENT
        } else {
            data["channel"]?.takeIf { it in KNOWN_ALERT_CHANNELS } ?: CHANNEL_BILLS
        }
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

        runCatching {
            AlertStore.add(
                CredentialStore(applicationContext),
                title,
                body,
                channelId,
                type.ifBlank { null },
                invoiceId.ifBlank { null },
                eventId.ifBlank { null }
            )
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val notificationId = invoiceId.toIntOrNull() ?: (System.currentTimeMillis() % 100_000L).toInt()
        manager.notify(notificationId, builder.build())
    }

    companion object {
        /** Messages that change this phone's pairing state; only obeyed when aimed at this device id. */
        private val DEVICE_STATE_TYPES = setOf("device_revoked", "device_suspended", "wipe_cache")

        const val CHANNEL_BILLS = "bills"
        const val CHANNEL_PAYMENTS = "payments"
        const val CHANNEL_RETURNS = "returns"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_APPROVALS = "approvals"
        const val CHANNEL_DIGEST = "digest"
        const val CHANNEL_BILLS_SILENT = "bills_silent"
        const val CHANNEL_CALLS = "calls"
        const val EXTRA_PUSH_TYPE = "type"
        const val EXTRA_INVOICE_ID = "invoice_id"
        const val EXTRA_EVENT_ID = "event_id"

        private val KNOWN_ALERT_CHANNELS = setOf(
            CHANNEL_BILLS,
            CHANNEL_PAYMENTS,
            CHANNEL_RETURNS,
            CHANNEL_ALERTS,
            CHANNEL_APPROVALS,
            CHANNEL_DIGEST,
            CHANNEL_BILLS_SILENT
        )

        fun ensureNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val highChannels = listOf(
                Triple(CHANNEL_BILLS, "Bills", "Alerts when bills are created or updated."),
                Triple(CHANNEL_PAYMENTS, "Payments", "Alerts when payments are recorded."),
                Triple(CHANNEL_RETURNS, "Returns", "Alerts when equipment returns are processed."),
                Triple(CHANNEL_ALERTS, "Alerts", "Voids, deletions, contact reuse, and blacklisting alerts."),
                Triple(CHANNEL_APPROVALS, "Approvals", "Approval requests requiring administrator decision."),
                Triple(CHANNEL_DIGEST, "Summaries & Digests", "Batched event digests and daily shop summaries.")
            )
            for ((id, name, desc) in highChannels) {
                val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = desc
                    enableVibration(true)
                }
                manager.createNotificationChannel(ch)
            }

            val silentChannel = NotificationChannel(
                CHANNEL_BILLS_SILENT,
                "Quiet Hours (Silent)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silent push alerts delivered during shop or personal quiet hours."
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

            manager.createNotificationChannel(silentChannel)
            manager.createNotificationChannel(callsChannel)
        }
    }
}
