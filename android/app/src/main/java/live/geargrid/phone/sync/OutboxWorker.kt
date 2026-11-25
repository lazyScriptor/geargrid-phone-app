package live.geargrid.phone.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.geargrid.phone.CredentialStore
import live.geargrid.phone.api.DeviceApiClient
import live.geargrid.phone.db.AppDatabase
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import kotlin.math.min

class OutboxWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = CredentialStore(applicationContext)
        if (!store.hasCredentials()) {
            return@withContext Result.success()
        }

        val dao = AppDatabase.getInstance(applicationContext).contactSyncDao()
        val api = DeviceApiClient(store)
        val now = System.currentTimeMillis()
        val due = dao.getDueOutbox(now)
        if (due.isEmpty()) {
            return@withContext Result.success()
        }

        var needsRetry = false

        for (item in due) {
            if (item.kind == KIND_CALL_RECORDING) {
                if (!store.isCallRecordingEnabled()) {
                    dao.deleteOutboxById(item.id)
                    continue
                }
                if (!store.isCallRecordingMobileDataAllowed() && isActiveNetworkMetered()) {
                    // Defer recording upload until an unmetered (Wi-Fi) network is active.
                    enqueueRecordingUpload(applicationContext, allowMobileData = false)
                    continue
                }
                val retryItem = processRecordingUpload(store, api, dao, item, now)
                if (retryItem) needsRetry = true
                continue
            }

            val endpoint = when (item.kind) {
                KIND_CONTACT_SYNC_CHUNK -> "/device/contacts/sync"
                KIND_CALL_EVENT -> "/device/calls"
                else -> {
                    dao.deleteOutboxById(item.id)
                    continue
                }
            }

            try {
                api.post(endpoint, JSONObject(item.payloadJson))
                dao.deleteOutboxById(item.id)
            } catch (e: DeviceApiClient.HttpException) {
                if (e.code == 400 || e.code == 401 || e.code == 403 || e.code == 409) {
                    dao.deleteOutboxById(item.id)
                } else {
                    rescheduleOrDrop(dao, item.id, item.attempts, now)
                    needsRetry = true
                }
            } catch (_: Exception) {
                rescheduleOrDrop(dao, item.id, item.attempts, now)
                needsRetry = true
            }
        }

        if (needsRetry) Result.retry() else Result.success()
    }

    private fun isActiveNetworkMetered(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return cm?.isActiveNetworkMetered == true
    }

    private suspend fun processRecordingUpload(
        store: CredentialStore,
        api: DeviceApiClient,
        dao: live.geargrid.phone.db.ContactSyncDao,
        item: live.geargrid.phone.db.OutboxEntity,
        nowMs: Long
    ): Boolean {
        val payload = runCatching { JSONObject(item.payloadJson) }.getOrNull()
        val clientCallId = payload?.optString("client_call_id")?.trim().orEmpty()
        val contentUriStr = payload?.optString("content_uri")?.trim().orEmpty()
        val filePath = payload?.optString("file_path")?.trim()?.takeIf { it.isNotEmpty() }
        val displayName = payload?.optString("display_name")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "call_recording.m4a"
        val mimeType = payload?.optString("mime_type")?.trim()
            ?.takeIf { it.startsWith("audio/") } ?: "audio/mp4"
        val durationSec = if (payload?.has("duration_seconds") == true && !payload.isNull("duration_seconds")) {
            payload.optInt("duration_seconds", -1).takeIf { it >= 0 }
        } else {
            null
        }

        if (clientCallId.isEmpty() || (contentUriStr.isEmpty() && filePath == null)) {
            dao.deleteOutboxById(item.id)
            return false
        }

        val bytes = readRecordingBytes(contentUriStr, filePath)
        if (bytes == null || bytes.isEmpty()) {
            dao.deleteOutboxById(item.id)
            return false
        }

        val encodedId = URLEncoder.encode(clientCallId, "UTF-8")
        val fields = if (durationSec != null) mapOf("duration_seconds" to durationSec.toString()) else emptyMap()

        return try {
            api.postMultipartFile(
                path = "/device/calls/$encodedId/recording",
                fieldName = "recording",
                fileName = displayName,
                mimeType = mimeType,
                bytes = bytes,
                fields = fields
            )
            dao.deleteOutboxById(item.id)
            if (store.shouldDeleteRecordingAfterUpload()) {
                deleteLocalRecordingSilently(contentUriStr, filePath)
            }
            false
        } catch (e: DeviceApiClient.HttpException) {
            if (e.code in listOf(400, 401, 403, 409, 413) || (e.code == 404 && item.attempts >= 3)) {
                dao.deleteOutboxById(item.id)
                false
            } else {
                rescheduleOrDrop(dao, item.id, item.attempts, nowMs)
                true
            }
        } catch (_: Exception) {
            rescheduleOrDrop(dao, item.id, item.attempts, nowMs)
            true
        }
    }

    private fun readRecordingBytes(contentUriStr: String, filePath: String?): ByteArray? {
        if (contentUriStr.isNotEmpty()) {
            val fromUri = runCatching {
                applicationContext.contentResolver.openInputStream(Uri.parse(contentUriStr))?.use { it.readBytes() }
            }.getOrNull()
            if (fromUri != null && fromUri.isNotEmpty()) return fromUri
        }
        if (filePath != null) {
            return runCatching {
                val f = File(filePath)
                if (f.exists() && f.isFile) f.readBytes() else null
            }.getOrNull()
        }
        return null
    }

    private fun deleteLocalRecordingSilently(contentUriStr: String, filePath: String?) {
        runCatching {
            if (contentUriStr.isNotEmpty()) {
                val deleted = applicationContext.contentResolver.delete(Uri.parse(contentUriStr), null, null)
                if (deleted > 0) return
            }
            if (filePath != null) {
                File(filePath).delete()
            }
        }
    }

    private suspend fun rescheduleOrDrop(
        dao: live.geargrid.phone.db.ContactSyncDao,
        id: Long,
        attempts: Int,
        nowMs: Long
    ) {
        val nextAttempts = attempts + 1
        if (nextAttempts >= MAX_ATTEMPTS) {
            dao.deleteOutboxById(id)
            return
        }
        val backoffMs = min(3_600_000L, 30_000L * (1L shl min(attempts, 6)))
        dao.updateOutboxRetry(id, nextAttempts, nowMs + backoffMs)
    }

    companion object {
        const val KIND_CONTACT_SYNC_CHUNK = "CONTACT_SYNC_CHUNK"
        const val KIND_CALL_EVENT = "CALL_EVENT"
        const val KIND_CALL_RECORDING = "CALL_RECORDING"
        private const val MAX_ATTEMPTS = 10
        private const val UNIQUE_OUTBOX = "geargrid_sync_outbox"
        private const val UNIQUE_RECORDING_OUTBOX = "geargrid_recording_outbox"

        fun enqueueOneShot(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_OUTBOX, ExistingWorkPolicy.KEEP, req)
        }

        fun enqueueRecordingUpload(context: Context, allowMobileData: Boolean) {
            val networkType = if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()
            val req = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_RECORDING_OUTBOX, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_OUTBOX)
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_RECORDING_OUTBOX)
        }
    }
}
