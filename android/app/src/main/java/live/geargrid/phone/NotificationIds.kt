package live.geargrid.phone

import android.app.NotificationManager
import android.content.Context

/** Fixed id ranges, so the app can tell its activity notifications from calls and dial prompts. */
object NotificationIds {
    private const val EVENT_BASE = 1_000_000
    private const val INVOICE_BASE = 2_000_000
    private const val CALL_NOTE_BASE = 3_000_000
    private const val SUMMARY_BASE = 70_000
    private const val RANGE = 1_000_000

    fun forEvent(eventId: Int): Int = EVENT_BASE + (eventId % RANGE)
    fun forInvoice(invoiceId: Int): Int = INVOICE_BASE + (invoiceId % RANGE)
    fun forGroupSummary(group: String): Int = SUMMARY_BASE + (group.hashCode() and 0x0fff)
    fun forCallNote(clientCallId: String): Int = CALL_NOTE_BASE + (clientCallId.hashCode() and 0xfffff)

    fun isFeedItem(id: Int): Boolean = id in EVENT_BASE until CALL_NOTE_BASE
    fun isFeedSummary(id: Int): Boolean = id in SUMMARY_BASE..(SUMMARY_BASE + 0x0fff)

    /** Nothing in the feed is unread any more: remove the feed's notifications, and with them the badge. */
    fun clearFeed(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            manager.activeNotifications
                .map { it.id }
                .filter { isFeedItem(it) || isFeedSummary(it) }
                .forEach { manager.cancel(it) }
        }
    }

    /** Removes one handled notification, and its group's summary when it was the last one. */
    fun cancelAndTidy(context: Context, id: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            val active = manager.activeNotifications
            val group = active.firstOrNull { it.id == id }?.notification?.group
            manager.cancel(id)
            if (group == null) return@runCatching
            val sameGroup = active.filter { it.id != id && it.notification.group == group }
            if (sameGroup.all { isFeedSummary(it.id) }) sameGroup.forEach { manager.cancel(it.id) }
        }
    }
}
