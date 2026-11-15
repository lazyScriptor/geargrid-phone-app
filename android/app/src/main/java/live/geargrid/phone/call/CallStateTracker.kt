package live.geargrid.phone.call

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import live.geargrid.phone.CredentialStore
import live.geargrid.phone.PhoneNormalizer
import live.geargrid.phone.api.DeviceApiClient
import live.geargrid.phone.db.AppDatabase
import live.geargrid.phone.db.OutboxEntity
import live.geargrid.phone.sync.BriefSyncWorker
import live.geargrid.phone.sync.OutboxWorker
import org.json.JSONObject
import java.time.Instant
import kotlin.math.max

class CallStateTracker : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val appContext = context.applicationContext
        val store = CredentialStore(appContext)
        if (!store.hasCredentials()) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (!store.isWithinBusinessHours() && stateStr != TelephonyManager.EXTRA_STATE_IDLE) {
            return
        }

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                @Suppress("DEPRECATION")
                val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val phoneE164 = rawNumber?.let { PhoneNormalizer.normalizeToDigits(it) }

                val session = ActiveCallSession.startOrReuse(rawNumber, phoneE164, "INCOMING", appContext)
                if (phoneE164 != null) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val db = AppDatabase.getInstance(appContext)
                            val briefDao = db.briefDao()
                            val brief = briefDao.findBriefByPhone(phoneE164)
                            val contact = if (brief == null) briefDao.findContactByPhone(phoneE164) else null
                            CallerOverlayManager.showBrief(
                                appContext,
                                rawNumber ?: "+$phoneE164",
                                brief,
                                contact
                            )

                            val shouldLog = CallPolicyHelper.shouldLogForScope(
                                store.getCallLoggingScope(),
                                brief != null,
                                contact != null
                            )
                            if (shouldLog && ActiveCallSession.markRingingPosted(session.clientCallId, appContext)) {
                                val ringingBody = JSONObject().apply {
                                    put("client_call_id", session.clientCallId)
                                    if (rawNumber != null) put("raw_number", rawNumber) else put("raw_number", JSONObject.NULL)
                                    put("direction", "INCOMING")
                                    put("state", "RINGING")
                                    put("started_at", session.startedAtIso)
                                }
                                postOrEnqueue(appContext, store, ringingBody)
                            }
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (ActiveCallSession.peek() == null) {
                    ActiveCallSession.startOrReuse(null, null, "OUTGOING", appContext)
                }
                ActiveCallSession.markOffhook(appContext)
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                CallerOverlayManager.dismiss(appContext)
                if (!store.isWithinBusinessHours()) {
                    ActiveCallSession.consumeOrRestore(appContext)
                    return
                }
                val endedAtMs = System.currentTimeMillis()
                val endedAtIso = Instant.ofEpochMilli(endedAtMs).toString()

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val session = ActiveCallSession.consumeOrRestore(appContext)
                        val loggingScope = store.getCallLoggingScope()
                        if (session != null) {
                            val callLogInfo = readRecentCallLogWithRetry(appContext, session.startedAtMs)
                            val simLabel = callLogInfo?.accountId?.trim()?.takeIf { it.isNotEmpty() }
                            if (!CallPolicyHelper.matchesSimFilter(store.getSimFilter(), simLabel, simLabel)) {
                                return@launch
                            }
                            val resolvedRawNumber = session.rawNumber ?: callLogInfo?.number
                            val resolvedPhoneE164 = session.phoneE164
                                ?: resolvedRawNumber?.let { PhoneNormalizer.normalizeToDigits(it) }

                            val db = AppDatabase.getInstance(appContext)
                            val briefDao = db.briefDao()
                            val hasBrief = resolvedPhoneE164 != null && briefDao.findBriefByPhone(resolvedPhoneE164) != null
                            val hasContact = resolvedPhoneE164 != null && briefDao.findContactByPhone(resolvedPhoneE164) != null
                            val isAllowed = session.ringingPosted ||
                                CallPolicyHelper.shouldLogForScope(loggingScope, hasBrief, hasContact)
                            if (!isAllowed) {
                                return@launch
                            }

                            val resolvedDirection = mapCallDirection(callLogInfo?.type, session.answered, session.direction)
                            val resolvedDuration = callLogInfo?.durationSeconds
                                ?: max(0, ((endedAtMs - session.startedAtMs) / 1000L).toInt())

                            val endedBody = JSONObject().apply {
                                put("client_call_id", session.clientCallId)
                                if (!resolvedRawNumber.isNullOrBlank()) {
                                    put("raw_number", resolvedRawNumber)
                                } else {
                                    put("raw_number", JSONObject.NULL)
                                }
                                put("direction", resolvedDirection)
                                put("state", "ENDED")
                                put("started_at", session.startedAtIso)
                                put("ended_at", endedAtIso)
                                put("duration_seconds", resolvedDuration)
                                if (!simLabel.isNullOrBlank()) put("sim_label", simLabel.take(40))
                            }

                            postOrEnqueue(appContext, store, endedBody)
                            showAfterCallCardIfReady(
                                appContext,
                                store,
                                session.clientCallId,
                                resolvedRawNumber,
                                resolvedPhoneE164
                            )
                            BriefSyncWorker.enqueueOneShot(appContext)
                            OutboxWorker.enqueueOneShot(appContext)
                        } else {
                            // A-09 call-log fallback when session was lost (e.g. OEM killed process).
                            val lookbackStartMs = endedAtMs - FALLBACK_LOOKBACK_MS
                            val fallbackEntry = readRecentCallLogWithRetry(appContext, lookbackStartMs)
                                ?: return@launch
                            if (fallbackEntry.dateMs < lookbackStartMs) return@launch
                            val simLabel = fallbackEntry.accountId?.trim()?.takeIf { it.isNotEmpty() }
                            if (!CallPolicyHelper.matchesSimFilter(store.getSimFilter(), simLabel, simLabel)) {
                                return@launch
                            }

                            val phoneE164 = fallbackEntry.number?.let { PhoneNormalizer.normalizeToDigits(it) }
                            val db = AppDatabase.getInstance(appContext)
                            val briefDao = db.briefDao()
                            val hasBrief = phoneE164 != null && briefDao.findBriefByPhone(phoneE164) != null
                            val hasContact = phoneE164 != null && briefDao.findContactByPhone(phoneE164) != null
                            if (!CallPolicyHelper.shouldLogForScope(loggingScope, hasBrief, hasContact)) {
                                return@launch
                            }

                            val dedupKey = "calllog:${phoneE164 ?: "anon"}:${fallbackEntry.dateMs}"
                            val prefs = appContext.getSharedPreferences("geargrid_call_fallback", Context.MODE_PRIVATE)
                            if (prefs.getString("last_key", null) == dedupKey) {
                                return@launch
                            }
                            prefs.edit().putString("last_key", dedupKey).apply()

                            val startedIso = Instant.ofEpochMilli(fallbackEntry.dateMs).toString()
                            val fallbackEndedIso = Instant.ofEpochMilli(
                                max(fallbackEntry.dateMs, fallbackEntry.dateMs + fallbackEntry.durationSeconds * 1000L)
                            ).toString()

                            val endedBody = JSONObject().apply {
                                put("client_call_id", dedupKey)
                                if (!fallbackEntry.number.isNullOrBlank()) {
                                    put("raw_number", fallbackEntry.number)
                                } else {
                                    put("raw_number", JSONObject.NULL)
                                }
                                put("direction", mapCallDirection(fallbackEntry.type, fallbackEntry.durationSeconds > 0, "INCOMING"))
                                put("state", "ENDED")
                                put("started_at", startedIso)
                                put("ended_at", fallbackEndedIso)
                                put("duration_seconds", fallbackEntry.durationSeconds)
                                if (!simLabel.isNullOrBlank()) put("sim_label", simLabel.take(40))
                            }

                            postOrEnqueue(appContext, store, endedBody)
                            showAfterCallCardIfReady(
                                appContext,
                                store,
                                dedupKey,
                                fallbackEntry.number,
                                phoneE164
                            )
                            BriefSyncWorker.enqueueOneShot(appContext)
                            OutboxWorker.enqueueOneShot(appContext)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun mapCallDirection(callLogType: Int?, answered: Boolean, defaultDirection: String): String =
        when (callLogType) {
            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
            CallLog.Calls.MISSED_TYPE,
            CallLog.Calls.REJECTED_TYPE -> "MISSED"
            CallLog.Calls.INCOMING_TYPE -> "INCOMING"
            else -> if (!answered && defaultDirection == "INCOMING") "MISSED" else defaultDirection
        }

    private suspend fun postOrEnqueue(
        context: Context,
        store: CredentialStore,
        payload: JSONObject
    ) {
        val api = DeviceApiClient(store)
        try {
            api.post("/device/calls", payload)
        } catch (e: DeviceApiClient.HttpException) {
            if (e.code !in listOf(400, 401, 403, 409)) {
                enqueueCallOutbox(context, payload)
            }
        } catch (_: Exception) {
            enqueueCallOutbox(context, payload)
        }
    }

    private suspend fun showAfterCallCardIfReady(
        context: Context,
        store: CredentialStore,
        clientCallId: String,
        rawNumber: String?,
        phoneE164: String?
    ) {
        if (!store.isAfterCallCardEnabled()) return
        val briefDao = AppDatabase.getInstance(context).briefDao()
        val brief = phoneE164?.let { briefDao.findBriefByPhone(it) }
        val contact = if (brief == null && phoneE164 != null) briefDao.findContactByPhone(phoneE164) else null
        val label = brief?.displayName ?: contact?.displayName ?: rawNumber ?: "Caller"

        val isUnknownCaller = brief == null && contact == null && !rawNumber.isNullOrBlank()
        CallerOverlayManager.showAfterCallCard(
            context = context,
            clientCallId = clientCallId,
            callerLabel = label,
            customerId = brief?.customerId,
            isUnknownCaller = isUnknownCaller,
            onOpenOnDesktop = brief?.customerId?.let { cid ->
                {
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            val body = JSONObject().apply {
                                put("customer_id", cid)
                                put("call_id", clientCallId)
                            }
                            DeviceApiClient(store).post("/device/pos/open-customer", body)
                        }
                    }
                }
            },
            onSaveLead = if (isUnknownCaller && !rawNumber.isNullOrBlank()) {
                { leadName, leadNote ->
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            val body = JSONObject().apply {
                                put("display_name", leadName)
                                put("phone", rawNumber)
                                put("note", leadNote ?: JSONObject.NULL)
                                put("call_id", clientCallId)
                            }
                            DeviceApiClient(store).post("/device/leads", body)
                        }
                    }
                }
            } else {
                null
            }
        ) { tags, note, followUpOn ->
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val body = JSONObject().apply {
                        put("tags", org.json.JSONArray(tags))
                        put("note", note ?: JSONObject.NULL)
                        put("follow_up_on", followUpOn ?: JSONObject.NULL)
                    }
                    val encodedId = java.net.URLEncoder.encode(clientCallId, "UTF-8")
                    DeviceApiClient(store).post("/device/calls/$encodedId/note", body)
                    BriefSyncWorker.enqueueOneShot(context)
                }
            }
        }
    }

    private suspend fun enqueueCallOutbox(context: Context, payload: JSONObject) {
        AppDatabase.getInstance(context).contactSyncDao().enqueueOutbox(
            OutboxEntity(
                kind = OutboxWorker.KIND_CALL_EVENT,
                payloadJson = payload.toString(),
                createdAt = System.currentTimeMillis()
            )
        )
        OutboxWorker.enqueueOneShot(context)
    }

    private data class CallLogEntry(
        val number: String?,
        val durationSeconds: Int,
        val type: Int,
        val dateMs: Long,
        val accountId: String?
    )

    /**
     * Queries the CallLog with retries (immediate, +1.5s, +2.5s = 4s total) because Android
     * frequently commits the new call-log row after ACTION_PHONE_STATE_CHANGED(IDLE) fires.
     */
    private suspend fun readRecentCallLogWithRetry(context: Context, minStartedAtMs: Long): CallLogEntry? {
        readRecentCallLogOnce(context, minStartedAtMs)?.let { return it }
        delay(1_500L)
        readRecentCallLogOnce(context, minStartedAtMs)?.let { return it }
        delay(2_500L)
        return readRecentCallLogOnce(context, minStartedAtMs)
    }

    private fun readRecentCallLogOnce(context: Context, minStartedAtMs: Long): CallLogEntry? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.PHONE_ACCOUNT_ID
        )
        val selection = "${CallLog.Calls.DATE} >= ?"
        val selectionArgs = arrayOf((minStartedAtMs - 15_000L).toString())

        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val durIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val accIdx = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                CallLogEntry(
                    number = if (numIdx >= 0) cursor.getString(numIdx)?.trim()?.takeIf { it.isNotEmpty() } else null,
                    durationSeconds = if (durIdx >= 0) max(0, cursor.getInt(durIdx)) else 0,
                    type = if (typeIdx >= 0) cursor.getInt(typeIdx) else -1,
                    dateMs = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L,
                    accountId = if (accIdx >= 0) cursor.getString(accIdx)?.trim()?.takeIf { it.isNotEmpty() } else null
                )
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val FALLBACK_LOOKBACK_MS = 10 * 60 * 1000L
    }
}
