package live.geargrid.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
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
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
        val isHighRisk = data["risk"] == "high"
        val invoiceId = data["invoice_id"].orEmpty()
        val eventId = data["event_id"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: data["activity_event_id"]?.trim().orEmpty()
        val amount = data["amount"]?.toDoubleOrNull()

        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (type.isNotEmpty()) putExtra(EXTRA_PUSH_TYPE, type)
            if (invoiceId.isNotEmpty()) putExtra(EXTRA_INVOICE_ID, invoiceId)
            if (eventId.isNotEmpty()) putExtra(EXTRA_EVENT_ID, eventId)
        }

        val notificationId = eventId.toIntOrNull()?.let { NotificationIds.forEvent(it) }
            ?: invoiceId.toIntOrNull()?.let { NotificationIds.forInvoice(it) }
            ?: (System.currentTimeMillis() % 60_000L).toInt()
        val store = CredentialStore(applicationContext)
        val language = PhoneStrings.resolveLanguage(store.getAppLanguage())
        launchIntent.putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        // The action keeps this PendingIntent apart from every other notification's.
        launchIntent.action = "live.geargrid.phone.OPEN.$notificationId"
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smallIcon = applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.ic_dialog_info

        // One channel per type, so each can be muted in Android settings; High events use the louder one.
        val typeGroup = resolveTypeGroup(type, data["channel"])
        val channelId = when {
            silent -> CHANNEL_BILLS_SILENT
            isHighRisk -> CHANNEL_HIGH_PRIORITY
            else -> typeGroup
        }

        val groupKey = "geargrid_group_$typeGroup"
        val recent = if (silent) emptyList() else recordRecentPush(typeGroup, title, amount)
        // A second push of the same type within a minute joins the stack without another sound.
        val quietlyStacked = recent.size >= 2 && !isHighRisk
        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(groupKey)
            .setSilent(silent || quietlyStacked)
            .setPriority(
                when {
                    silent -> NotificationCompat.PRIORITY_LOW
                    isHighRisk -> NotificationCompat.PRIORITY_MAX
                    else -> NotificationCompat.PRIORITY_HIGH
                }
            )

        // "Mark checked" works from the shade, after the phone is unlocked.
        if (eventId.toLongOrNull() != null) {
            val checkIntent = Intent(applicationContext, NotificationActionReceiver::class.java).apply {
                action = "${NotificationActionReceiver.ACTION_MARK_CHECKED}.$notificationId"
                putExtra(EXTRA_EVENT_ID, eventId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            val checkPending = PendingIntent.getBroadcast(
                applicationContext,
                notificationId,
                checkIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.checkbox_on_background,
                    PhoneStrings.get(language, "action_mark_checked"),
                    checkPending
                ).setAuthenticationRequired(true).build()
            )
        }

        // "Open bill" opens /device/invoice/$id
        if (invoiceId.isNotEmpty()) {
            builder.addAction(
                android.R.drawable.ic_menu_view,
                PhoneStrings.get(language, "action_open_bill"),
                pendingIntent
            )
        }

        runCatching {
            AlertStore.add(
                store,
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
        manager.notify(notificationId, builder.build())

        // Pushes of one type arriving together show as one stack ("5 payments · Rs 42,000")
        if (recent.size >= 2) {
            val summaryText = formatGroupSummaryText(language, typeGroup, recent)
            val inbox = NotificationCompat.InboxStyle().setSummaryText(summaryText)
            recent.takeLast(5).forEach { inbox.addLine(it.title) }
            val summaryId = NotificationIds.forGroupSummary(typeGroup)
            val summaryIntent = Intent(applicationContext, MainActivity::class.java).apply {
                action = "live.geargrid.phone.ALERTS.$summaryId"
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_ALERTS, true)
            }
            val summaryPending = PendingIntent.getActivity(
                applicationContext,
                summaryId,
                summaryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val summary = NotificationCompat.Builder(applicationContext, typeGroup)
                .setSmallIcon(smallIcon)
                .setContentTitle(summaryText)
                .setContentText(PhoneStrings.format(language, "group_new", recent.size))
                .setStyle(inbox)
                .setGroup(groupKey)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .setContentIntent(summaryPending)
                .build()
            manager.notify(summaryId, summary)
        }

        if (eventId.isNotEmpty()) setBadgeNumber(store, manager, builder, notificationId)
    }

    /**
     * Launchers that show a number add up the numbers of the active notifications, so the newest one
     * carries whatever makes that sum equal the feed's unread count.
     */
    private fun setBadgeNumber(
        store: CredentialStore,
        manager: NotificationManager,
        builder: NotificationCompat.Builder,
        notificationId: Int
    ) {
        if (!store.hasCredentials()) return
        runCatching {
            val unread = DeviceApiClient(store)
                .get("/device/activity?unread=true&limit=1")
                .optJSONObject("data")
                ?.optInt("total", -1) ?: -1
            if (unread < 0) return
            val active = manager.activeNotifications
            // Gone already (swiped away or handled): don't bring it back.
            if (active.none { it.id == notificationId }) return
            val others = active
                .filter { it.id != notificationId && NotificationIds.isFeedItem(it.id) }
                .sumOf { maxOf(1, it.notification.number) }
            builder.setNumber(maxOf(1, unread - others)).setOnlyAlertOnce(true).setSilent(true)
            manager.notify(notificationId, builder.build())
        }
    }

    private data class RecentPush(val timestampMs: Long, val title: String, val amount: Double?)

    companion object {
        /** Messages that change this phone's pairing state; only obeyed when aimed at this device id. */
        private val DEVICE_STATE_TYPES = setOf("device_revoked", "device_suspended", "wipe_cache")
        private const val GROUP_WINDOW_MS = 60_000L

        private val recentByGroup = ConcurrentHashMap<String, MutableList<RecentPush>>()

        const val CHANNEL_BILLS = "bills"
        const val CHANNEL_PAYMENTS = "payments"
        const val CHANNEL_DISCOUNTS = "discounts"
        const val CHANNEL_BACKDATED = "backdated"
        const val CHANNEL_RETURNS = "returns"
        const val CHANNEL_SYSTEM = "system"
        const val CHANNEL_HIGH_PRIORITY = "high_priority"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_APPROVALS = "approvals"
        const val CHANNEL_DIGEST = "digest"
        const val CHANNEL_BILLS_SILENT = "bills_silent"
        const val CHANNEL_CALLS = "calls"
        const val EXTRA_PUSH_TYPE = "type"
        const val EXTRA_INVOICE_ID = "invoice_id"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_OPEN_ALERTS = "open_alerts"

        private val KNOWN_ALERT_CHANNELS = setOf(
            CHANNEL_BILLS,
            CHANNEL_PAYMENTS,
            CHANNEL_DISCOUNTS,
            CHANNEL_BACKDATED,
            CHANNEL_RETURNS,
            CHANNEL_SYSTEM,
            CHANNEL_HIGH_PRIORITY,
            CHANNEL_ALERTS,
            CHANNEL_APPROVALS,
            CHANNEL_DIGEST,
            CHANNEL_BILLS_SILENT
        )

        private fun resolveTypeGroup(type: String, serverChannel: String?): String {
            return when (type) {
                "payment", "customer_payment", "refund" -> CHANNEL_PAYMENTS
                "discount" -> CHANNEL_DISCOUNTS
                "backdated_payment", "backdated_bill", "backdated_return" -> CHANNEL_BACKDATED
                "return" -> CHANNEL_RETURNS
                "invoice_created", "invoice_reopened" -> CHANNEL_BILLS
                "payment_voided", "invoice_deleted", "invoice_restored", "id_card_released_unpaid", "review",
                "call_promise_missed" -> CHANNEL_ALERTS
                "daily_summary", "follow_up_due", "system" -> CHANNEL_SYSTEM
                else -> serverChannel?.takeIf { it in KNOWN_ALERT_CHANNELS && it != CHANNEL_BILLS_SILENT }
                    ?: CHANNEL_BILLS
            }
        }

        private fun recordRecentPush(group: String, title: String, amount: Double?): List<RecentPush> {
            val now = System.currentTimeMillis()
            val list = recentByGroup.getOrPut(group) { mutableListOf() }
            synchronized(list) {
                list.removeAll { now - it.timestampMs > GROUP_WINDOW_MS }
                list.add(RecentPush(now, title, amount))
                return list.toList()
            }
        }

        private fun formatGroupSummaryText(language: String, group: String, items: List<RecentPush>): String {
            val known = listOf(
                CHANNEL_PAYMENTS, CHANNEL_DISCOUNTS, CHANNEL_BACKDATED, CHANNEL_RETURNS, CHANNEL_BILLS, CHANNEL_SYSTEM
            )
            val key = if (group in known) "group_$group" else "group_alerts"
            val counted = PhoneStrings.format(language, key, items.size)
            // Only when every push carried its amount: a part total would mislead.
            if (items.any { it.amount == null }) return counted
            val total = items.sumOf { it.amount ?: 0.0 }
            if (total <= 0.0) return counted
            return "$counted · Rs ${NumberFormat.getNumberInstance(Locale.US).format(total)}"
        }

        fun ensureNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val highChannels = listOf(
                Triple(CHANNEL_BILLS, "Bills", "Alerts when bills are created, reopened, or deleted."),
                Triple(CHANNEL_PAYMENTS, "Payments", "Alerts when payments or settlements are recorded."),
                Triple(CHANNEL_DISCOUNTS, "Discounts", "Alerts when discounts are applied to bills."),
                Triple(CHANNEL_BACKDATED, "Back-dated Entries", "Alerts for back-dated payments, bills, or returns."),
                Triple(CHANNEL_RETURNS, "Returns", "Alerts when equipment returns are processed."),
                Triple(CHANNEL_ALERTS, "Alerts", "Voids, deletions, contact reuse, and blacklisting alerts."),
                Triple(CHANNEL_APPROVALS, "Approvals", "Approval requests requiring administrator decision."),
                Triple(CHANNEL_DIGEST, "Summaries & Digests", "Batched event digests and daily shop summaries.")
            )
            for ((id, name, desc) in highChannels) {
                val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = desc
                    enableVibration(true)
                    setShowBadge(true)
                }
                manager.createNotificationChannel(ch)
            }

            val systemChannel = NotificationChannel(
                CHANNEL_SYSTEM,
                "System & Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily digests, follow-ups, and system notifications."
                setShowBadge(true)
            }
            manager.createNotificationChannel(systemChannel)

            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val highPriorityChannel = NotificationChannel(
                CHANNEL_HIGH_PRIORITY,
                "High Priority Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Louder alerts for high-risk events such as large discounts, voids, or after-hours activity."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 120, 250)
                enableLights(true)
                setShowBadge(true)
                setSound(
                    alarmSound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            manager.createNotificationChannel(highPriorityChannel)

            val silentChannel = NotificationChannel(
                CHANNEL_BILLS_SILENT,
                "Quiet Hours (Silent)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silent push alerts delivered during shop or personal quiet hours."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
            }

            val callsChannel = NotificationChannel(
                CHANNEL_CALLS,
                "Incoming Caller Briefs",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Displays customer balance and rental briefs during phone calls."
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }

            manager.createNotificationChannel(silentChannel)
            manager.createNotificationChannel(callsChannel)
        }
    }
}
