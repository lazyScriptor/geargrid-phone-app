package live.geargrid.phone.call

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import live.geargrid.phone.CredentialStore
import live.geargrid.phone.GearGridMessagingService
import live.geargrid.phone.MainActivity
import live.geargrid.phone.NotificationIds
import live.geargrid.phone.PhoneStrings
import live.geargrid.phone.db.AppDatabase
import org.json.JSONObject

/**
 * Opens the call card (/device/call/{id}) after a call with a customer who still owes money or has
 * machines out. When the server can't be reached the caller falls back to the native note card.
 */
object CallCardLauncher {
    const val EXTRA_CALL_ID = "call_id"

    /** True when the call card was offered, so the native note card should stay away. */
    suspend fun openIfNeeded(
        context: Context,
        store: CredentialStore,
        response: JSONObject?,
        direction: String,
        rawNumber: String?,
        phoneE164: String?
    ): Boolean {
        if (!store.isAfterCallCardEnabled() || direction == "MISSED") return false
        val data = response?.optJSONObject("data") ?: return false
        val clientCallId = data.optString("client_call_id").trim()
        if (clientCallId.isEmpty() || data.optLong("customer_id", 0L) <= 0L) return false
        if ((data.optJSONArray("invoice_ids")?.length() ?: 0) == 0) return false

        val brief = phoneE164?.let { AppDatabase.getInstance(context).briefDao().findBriefByPhone(it) }
        val label = brief?.displayName ?: rawNumber.orEmpty()
        val language = PhoneStrings.resolveLanguage(store.getAppLanguage())
        val id = NotificationIds.forCallNote(clientCallId)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "live.geargrid.phone.CALL.$id"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALL_ID, clientCallId)
            putExtra(GearGridMessagingService.EXTRA_NOTIFICATION_ID, id)
        }

        // The notification stays until the card is really on screen: some phones block the pop-up.
        GearGridMessagingService.ensureNotificationChannels(context)
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val smallIcon = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_call_outgoing
        val notification = NotificationCompat.Builder(context, GearGridMessagingService.CHANNEL_CALLS)
            .setSmallIcon(smallIcon)
            .setContentTitle(PhoneStrings.get(language, "call_note_title"))
            .setContentText(label)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        runCatching { manager?.notify(id, notification) }

        // Never replace a bill somebody is in the middle of: then only the notification is shown.
        if (Settings.canDrawOverlays(context) && !MainActivity.leftOnFullAppPage) {
            runCatching { context.startActivity(intent) }
        }
        return true
    }
}
