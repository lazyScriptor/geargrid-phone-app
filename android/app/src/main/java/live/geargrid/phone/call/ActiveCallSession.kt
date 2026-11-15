package live.geargrid.phone.call

import java.time.Instant
import java.util.UUID

data class CallSession(
    val clientCallId: String,
    val rawNumber: String?,
    val phoneE164: String?,
    val direction: String,
    val startedAtIso: String,
    val startedAtMs: Long,
    @Volatile var answered: Boolean = false
)

object ActiveCallSession {
    @Volatile
    private var current: CallSession? = null

    @Synchronized
    fun startOrReuse(rawNumber: String?, phoneE164: String?, direction: String): CallSession {
        val nowMs = System.currentTimeMillis()
        val existing = current
        if (existing != null && nowMs - existing.startedAtMs < 5_000L) {
            val samePhone =
                (existing.phoneE164 != null && existing.phoneE164 == phoneE164) ||
                    (existing.phoneE164 == null && phoneE164 == null)
            if (samePhone) {
                return existing
            }
        }
        val created = CallSession(
            clientCallId = UUID.randomUUID().toString(),
            rawNumber = rawNumber?.takeIf { it.isNotBlank() },
            phoneE164 = phoneE164,
            direction = direction,
            startedAtIso = Instant.ofEpochMilli(nowMs).toString(),
            startedAtMs = nowMs
        )
        current = created
        return created
    }

    fun peek(): CallSession? = current

    @Synchronized
    fun markOffhook() {
        current?.answered = true
    }

    @Synchronized
    fun consume(): CallSession? {
        val out = current
        current = null
        return out
    }
}
