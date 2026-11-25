package live.geargrid.phone.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.geargrid.phone.CredentialStore
import live.geargrid.phone.api.DeviceApiClient
import live.geargrid.phone.call.CallRecordingMedia
import live.geargrid.phone.call.RecordedCall
import live.geargrid.phone.call.RecordingRules
import live.geargrid.phone.db.AppDatabase
import live.geargrid.phone.db.OutboxEntity
import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * One queued outbox row per answered call: find the recording the phone made, then upload it.
 *
 * Silent by design: no notification, toast, foreground service or expedited work, and these rows are
 * left out of the "waiting to upload" count the phone shows. Nothing is searched or sent unless the
 * server says, at that moment, that the shop has recording switched on.
 */
class CallRecordingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = CredentialStore(applicationContext)
        if (!store.hasCredentials()) return@withContext Result.success()

        val dao = AppDatabase.getInstance(applicationContext).contactSyncDao()
        val row = dao.getOutboxById(inputData.getLong(KEY_ROW_ID, -1L))
            ?: return@withContext Result.success()
        val job = runCatching { JSONObject(row.payloadJson) }.getOrNull()
        val clientCallId = job?.optString("client_call_id").orEmpty()
        val ageMs = System.currentTimeMillis() - (job?.optLong("ended_at_ms") ?: 0L)
        if (job == null || clientCallId.isEmpty() || ageMs > RecordingRules.MAX_JOB_AGE_MS) {
            dao.deleteOutboxById(row.id)
            return@withContext Result.success()
        }

        val api = DeviceApiClient(store)
        // Off means off: ask the server before looking at any file.
        val policy = runCatching { api.get("/device/policy").optJSONObject("data") }.getOrNull()
            ?: return@withContext Result.retry()
        store.setDevicePolicyJson(policy.toString())
        if (!store.isCallRecordingEnabled()) {
            dao.deleteOutboxByKind(OutboxWorker.KIND_CALL_RECORDING)
            return@withContext Result.success()
        }
        if (!store.isCallRecordingMobileDataAllowed() && isNetworkMetered()) {
            return@withContext Result.retry()
        }
        if (!CallRecordingMedia.hasPermission(applicationContext)) return@withContext Result.retry()

        if (!job.has("content_uri")) {
            val number = job.optString("number").takeIf { it.isNotEmpty() }
            val call = RecordedCall(
                startedAtMs = job.optLong("started_at_ms"),
                endedAtMs = job.optLong("ended_at_ms"),
                talkSeconds = if (job.has("talk_seconds")) job.optInt("talk_seconds") else null,
                number = number,
                contactNames = CallRecordingMedia.contactNames(applicationContext, number)
            )
            val match = runCatching {
                RecordingRules.pick(call, CallRecordingMedia.candidates(applicationContext, call))
            }.getOrNull()
            if (match == null) {
                val tries = row.attempts + 1
                if (RecordingRules.stopSearching(tries, ageMs)) {
                    dao.deleteOutboxById(row.id)
                    return@withContext Result.success()
                }
                dao.updateOutboxRetry(row.id, tries, 0L)
                return@withContext Result.retry()
            }
            job.put("content_uri", CallRecordingMedia.uriFor(match.id).toString())
            job.put("mime_type", match.mimeType)
            job.put("extension", match.displayName.substringAfterLast('.', "m4a"))
            if (match.durationMs > 0L) job.put("duration_seconds", match.durationMs / 1000L)
            dao.updateOutboxPayload(row.id, job.toString())
        }

        val uri = Uri.parse(job.optString("content_uri"))
        val resolver = applicationContext.contentResolver
        try {
            api.postFile(
                path = "/device/calls/${URLEncoder.encode(clientCallId, "UTF-8")}/recording",
                fieldName = "recording",
                // A fixed name: the phone's own file name often holds the contact's name.
                fileName = "call.${job.optString("extension", "m4a")}",
                mimeType = job.optString("mime_type", "audio/mp4"),
                fields = if (job.has("duration_seconds")) {
                    mapOf("duration_seconds" to job.optLong("duration_seconds").toString())
                } else {
                    emptyMap()
                },
                open = { resolver.openInputStream(uri) ?: throw FileNotFoundException() }
            )
        } catch (e: DeviceApiClient.HttpException) {
            if (!RecordingRules.dropAfterHttpError(e.code, e.message, ageMs)) {
                return@withContext Result.retry()
            }
            dao.deleteOutboxById(row.id)
            return@withContext Result.success()
        } catch (_: FileNotFoundException) {
            // Deleted on the phone before it could be sent.
            dao.deleteOutboxById(row.id)
            return@withContext Result.success()
        } catch (_: Exception) {
            return@withContext Result.retry()
        }

        dao.deleteOutboxById(row.id)
        if (store.shouldDeleteRecordingAfterUpload()) {
            CallRecordingMedia.deleteQuietly(applicationContext, uri)
        }
        Result.success()
    }

    private fun isNetworkMetered(): Boolean {
        val manager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return manager?.isActiveNetworkMetered != false
    }

    companion object {
        private const val KEY_ROW_ID = "outbox_row_id"
        private const val TAG = "geargrid_call_recording"

        /** Gives the phone's recorder time to finish and list its file. */
        private const val FIRST_SEARCH_DELAY_MS = 45_000L

        /** Two "call ended" broadcasts for one call arrive this close together. */
        private const val SAME_CALL_MS = 10_000L

        /** Called after every logged call. Does nothing unless the shop has recording switched on. */
        suspend fun onCallEnded(
            context: Context,
            store: CredentialStore,
            clientCallId: String,
            startedAtMs: Long,
            endedAtMs: Long,
            number: String?,
            talkSeconds: Int?
        ) {
            if (!store.isCallRecordingEnabled() || talkSeconds == 0) return
            if (!CallRecordingMedia.hasPermission(context)) return
            runCatching {
                val dao = AppDatabase.getInstance(context).contactSyncDao()
                val alreadyQueued = dao.getOutboxByKind(OutboxWorker.KIND_CALL_RECORDING).any { queued ->
                    val other = JSONObject(queued.payloadJson)
                    kotlin.math.abs(other.optLong("ended_at_ms") - endedAtMs) < SAME_CALL_MS &&
                        other.optString("number") == number.orEmpty()
                }
                if (alreadyQueued) return
                val job = JSONObject().apply {
                    put("client_call_id", clientCallId)
                    put("started_at_ms", startedAtMs)
                    put("ended_at_ms", endedAtMs)
                    put("number", number.orEmpty())
                    if (talkSeconds != null) put("talk_seconds", talkSeconds)
                }
                val rowId = dao.enqueueOutbox(
                    OutboxEntity(
                        kind = OutboxWorker.KIND_CALL_RECORDING,
                        payloadJson = job.toString(),
                        createdAt = System.currentTimeMillis()
                    )
                )
                enqueue(context, rowId, store.isCallRecordingMobileDataAllowed(), FIRST_SEARCH_DELAY_MS)
            }
        }

        /** Restarts any queued row that lost its work request, and clears the queue when switched off. */
        suspend fun resumePending(context: Context, store: CredentialStore) {
            val dao = AppDatabase.getInstance(context).contactSyncDao()
            if (!store.isCallRecordingEnabled()) {
                dao.deleteOutboxByKind(OutboxWorker.KIND_CALL_RECORDING)
                return
            }
            dao.getOutboxByKind(OutboxWorker.KIND_CALL_RECORDING).forEach {
                val endedAtMs = runCatching { JSONObject(it.payloadJson).optLong("ended_at_ms") }.getOrDefault(0L)
                val wait = (endedAtMs + FIRST_SEARCH_DELAY_MS - System.currentTimeMillis())
                    .coerceIn(0L, FIRST_SEARCH_DELAY_MS)
                enqueue(context, it.id, store.isCallRecordingMobileDataAllowed(), wait)
            }
        }

        private fun enqueue(context: Context, rowId: Long, allowMobileData: Boolean, delayMs: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                .build()
            val request = OneTimeWorkRequestBuilder<CallRecordingWorker>()
                .setInputData(workDataOf(KEY_ROW_ID to rowId))
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60L, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            // The name carries the network rule, so allowing mobile data adds a request that need not
            // wait for Wi-Fi. Whichever runs second finds the row gone.
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "$TAG-$rowId-${if (allowMobileData) "any" else "wifi"}",
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG)
        }
    }
}
