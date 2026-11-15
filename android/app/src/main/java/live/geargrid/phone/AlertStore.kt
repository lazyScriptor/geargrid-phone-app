package live.geargrid.phone

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** The last notifications this phone received, kept (encrypted) so the Alerts tab works without a connection. */
object AlertStore {
    private const val MAX = 100

    @Synchronized
    fun add(
        store: CredentialStore,
        title: String,
        body: String,
        group: String,
        eventKey: String?,
        invoiceId: String?
    ) {
        val current = readAll(store)
        val entry = JSONObject().apply {
            put("id", "${System.currentTimeMillis()}-${current.length()}")
            put("receivedAt", Instant.now().toString())
            put("title", title)
            put("body", body)
            put("group", groupFor(group))
            if (!eventKey.isNullOrBlank()) put("eventKey", eventKey)
            if (!invoiceId.isNullOrBlank()) put("invoiceId", invoiceId)
        }
        val next = JSONArray()
        next.put(entry)
        for (i in 0 until minOf(current.length(), MAX - 1)) next.put(current.get(i))
        store.setAlertsJson(next.toString())
    }

    @Synchronized
    fun readAll(store: CredentialStore): JSONArray =
        runCatching { JSONArray(store.getAlertsJson() ?: "[]") }.getOrDefault(JSONArray())

    @Synchronized
    fun clear(store: CredentialStore) = store.setAlertsJson(null)

    /** Channels map onto the five filter groups of the Alerts tab. */
    private fun groupFor(channel: String): String = when (channel) {
        "bills", "bills_silent" -> "bills"
        "payments" -> "payments"
        "returns" -> "returns"
        "digest" -> "digest"
        else -> "alerts"
    }
}
