package live.geargrid.phone.sync

import android.content.Context
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
        CallRecordingWorker.resumePending(applicationContext, store)
        val now = System.currentTimeMillis()
        val due = dao.getDueOutbox(now)
        if (due.isEmpty()) {
            return@withContext Result.success()
        }

        var needsRetry = false

        for (item in due) {
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

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_OUTBOX)
            CallRecordingWorker.cancelAll(context)
        }
    }
}
