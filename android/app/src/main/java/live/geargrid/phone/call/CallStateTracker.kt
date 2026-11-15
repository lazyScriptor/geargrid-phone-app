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

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                @Suppress("DEPRECATION")
                val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val phoneE164 = rawNumber?.let { PhoneNormalizer.normalizeToDigits(it) }

                val hadSession = ActiveCallSession.peek() != null
                val session = ActiveCallSession.startOrReuse(rawNumber, phoneE164, "INCOMING")
                if (!hadSession && phoneE164 != null) {
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

                            val ringingBody = JSONObject().apply {
                                put("client_call_id", session.clientCallId)
                                if (rawNumber != null) put("raw_number", rawNumber) else put("raw_number", JSONObject.NULL)
                                put("direction", "INCOMING")
                                put("state", "RINGING")
                                put("started_at", session.startedAtIso)
                            }
                            postOrEnqueue(appContext, store, ringingBody)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (ActiveCallSession.peek() == null) {
                    ActiveCallSession.startOrReuse(null, null, "OUTGOING")
                }
                ActiveCallSession.markOffhook()
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                CallerOverlayManager.dismiss(appContext)
                val session = ActiveCallSession.consume() ?: return

                val endedAtMs = System.currentTimeMillis()
                val endedAtIso = Instant.ofEpochMilli(endedAtMs).toString()

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val callLogInfo = readRecentCallLog(appContext, session.startedAtMs)
                        val resolvedRawNumber = session.rawNumber ?: callLogInfo?.number
                        val resolvedDirection = when (callLogInfo?.type) {
                            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                            CallLog.Calls.MISSED_TYPE,
                            CallLog.Calls.REJECTED_TYPE -> "MISSED"
                            CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                            else -> if (!session.answered && session.direction == "INCOMING") {
                                "MISSED"
                            } else {
                                session.direction
                            }
                        }
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
                        }

                        postOrEnqueue(appContext, store, endedBody)
                        BriefSyncWorker.enqueueOneShot(appContext)
                        OutboxWorker.enqueueOneShot(appContext)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
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
        val type: Int
    )

    private fun readRecentCallLog(context: Context, minStartedAtMs: Long): CallLogEntry? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE
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
                CallLogEntry(
                    number = if (numIdx >= 0) cursor.getString(numIdx)?.trim()?.takeIf { it.isNotEmpty() } else null,
                    durationSeconds = if (durIdx >= 0) max(0, cursor.getInt(durIdx)) else 0,
                    type = if (typeIdx >= 0) cursor.getInt(typeIdx) else -1
                )
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
