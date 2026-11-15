package live.geargrid.phone.call

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import live.geargrid.phone.db.ActiveCallEntity
import live.geargrid.phone.db.AppDatabase
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

data class CallSession(
    val clientCallId: String,
    @Volatile var rawNumber: String?,
    @Volatile var phoneE164: String?,
    val direction: String,
    val startedAtIso: String,
    val startedAtMs: Long,
    @Volatile var answered: Boolean = false,
    @Volatile var ringingPosted: Boolean = false
)

object ActiveCallSession {
    @Volatile
    private var current: CallSession? = null

    private val dbDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val dbScope = CoroutineScope(dbDispatcher)

    @Synchronized
    fun startOrReuse(
        rawNumber: String?,
        phoneE164: String?,
        direction: String,
        context: Context? = null
    ): CallSession {
        val nowMs = System.currentTimeMillis()
        val cleanRaw = rawNumber?.takeIf { it.isNotBlank() }
        val existing = current
        if (existing != null && !existing.answered && nowMs - existing.startedAtMs < 15_000L) {
            val compatiblePhone =
                existing.phoneE164 == null ||
                    phoneE164 == null ||
                    existing.phoneE164 == phoneE164
            if (compatiblePhone) {
                var changed = false
                if (existing.rawNumber.isNullOrBlank() && cleanRaw != null) {
                    existing.rawNumber = cleanRaw
                    changed = true
                }
                if (existing.phoneE164 == null && !phoneE164.isNullOrBlank()) {
                    existing.phoneE164 = phoneE164
                    changed = true
                }
                if (changed && context != null) {
                    persistAsync(context.applicationContext, existing)
                }
                return existing
            }
        }

        val created = CallSession(
            clientCallId = UUID.randomUUID().toString(),
            rawNumber = cleanRaw,
            phoneE164 = phoneE164,
            direction = direction,
            startedAtIso = Instant.ofEpochMilli(nowMs).toString(),
            startedAtMs = nowMs
        )
        current = created
        if (context != null) {
            persistAsync(context.applicationContext, created)
        }
        return created
    }

    fun peek(): CallSession? = current

    @Synchronized
    fun markOffhook(context: Context? = null) {
        val existing = current ?: return
        existing.answered = true
        if (context != null) {
            persistAsync(context.applicationContext, existing)
        }
    }

    @Synchronized
    fun markRingingPosted(clientCallId: String, context: Context? = null): Boolean {
        val existing = current
        if (existing == null || existing.clientCallId != clientCallId || existing.ringingPosted) {
            return false
        }
        existing.ringingPosted = true
        if (context != null) {
            persistAsync(context.applicationContext, existing)
        }
        return true
    }

    @Synchronized
    fun consume(context: Context? = null): CallSession? {
        val out = current
        current = null
        if (context != null) {
            val appContext = context.applicationContext
            dbScope.launch {
                runCatching {
                    AppDatabase.getInstance(appContext).activeCallDao().clearActiveCallSync()
                }
            }
        }
        return out
    }

    /**
     * Consumes the in-memory session or recovers one persisted in Room across process death.
     * Must be invoked off the main thread.
     */
    suspend fun consumeOrRestore(context: Context): CallSession? {
        val appContext = context.applicationContext
        val mem = synchronized(this) {
            val out = current
            current = null
            out
        }
        return withContext(dbDispatcher) {
            val dao = AppDatabase.getInstance(appContext).activeCallDao()
            val saved = runCatching { dao.getActiveCallSync() }.getOrNull()
            runCatching { dao.clearActiveCallSync() }
            mem ?: saved?.let {
                CallSession(
                    clientCallId = it.clientCallId,
                    rawNumber = it.rawNumber,
                    phoneE164 = it.phoneE164,
                    direction = it.direction,
                    startedAtIso = it.startedAtIso,
                    startedAtMs = it.startedAtMs,
                    answered = it.answered,
                    ringingPosted = it.ringingPosted
                )
            }
        }
    }

    private fun persistAsync(appContext: Context, session: CallSession) {
        val snapshot = ActiveCallEntity(
            id = 1,
            clientCallId = session.clientCallId,
            rawNumber = session.rawNumber,
            phoneE164 = session.phoneE164,
            direction = session.direction,
            startedAtIso = session.startedAtIso,
            startedAtMs = session.startedAtMs,
            answered = session.answered,
            ringingPosted = session.ringingPosted
        )
        dbScope.launch {
            runCatching {
                AppDatabase.getInstance(appContext).activeCallDao().upsertActiveCallSync(snapshot)
            }
        }
    }
}
