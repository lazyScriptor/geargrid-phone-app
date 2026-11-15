package live.geargrid.phone.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.geargrid.phone.CredentialStore
import live.geargrid.phone.api.DeviceApiClient
import live.geargrid.phone.db.AppDatabase
import live.geargrid.phone.db.BriefEntity
import live.geargrid.phone.db.BriefPhoneEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BriefSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = CredentialStore(applicationContext)
        if (!store.hasCredentials()) {
            return@withContext Result.success()
        }

        val api = DeviceApiClient(store)
        val briefDao = AppDatabase.getInstance(applicationContext).briefDao()

        var cursor: Long? = null
        var lastServerTime: String? = null

        try {
            api.checkOfflineWipe()
            api.rotateTokenIfNeeded()
            runCatching {
                val policyData = api.get("/device/policy").optJSONObject("data")
                if (policyData != null) {
                    store.setDevicePolicyJson(policyData.toString())
                    policyData.optString("device_status", "").takeIf { it.isNotBlank() }?.let {
                        store.setDeviceStatus(it)
                    }
                    val wipeDays = policyData.optInt("offline_wipe_days", 0)
                    if (wipeDays > 0) store.setOfflineWipeDays(wipeDays)
                    policyData.optJSONObject("effective_capabilities")?.let {
                        store.setEffectiveCapabilitiesJson(it.toString())
                    }
                    policyData.optJSONObject("capability_reasons")?.let {
                        store.setCapabilityReasonsJson(it.toString())
                    }
                    policyData.optString("contact_sync_mode", "").takeIf { it.isNotBlank() }?.let {
                        store.setContactSyncMode(it)
                    }
                    policyData.optString("call_logging_scope", "").takeIf { it.isNotBlank() }?.let {
                        store.setCallLoggingScope(it)
                    }
                    store.setSimFilter(policyData.optNullableString("sim_filter"))
                    policyData.optJSONObject("after_call_card")?.let { afterCall ->
                        store.setAfterCallCardEnabled(afterCall.optBoolean("enabled", true))
                        store.setAfterCallDismissSeconds(afterCall.optInt("auto_dismiss_seconds", 25))
                        afterCall.optJSONArray("tags")?.let { tagsArr ->
                            val list = (0 until tagsArr.length())
                                .mapNotNull { i -> tagsArr.optString(i, "").trim().takeIf { it.isNotEmpty() } }
                            if (list.isNotEmpty()) {
                                store.setAfterCallTags(list)
                            }
                        }
                    }
                    policyData.optString("min_app_version", "").trim().takeIf { it.isNotEmpty() }?.let {
                        store.setMinAppVersion(it)
                    }
                    policyData.optString("latest_app_version", "").trim().takeIf { it.isNotEmpty() }?.let {
                        store.setLatestAppVersion(it)
                    }
                }
            }
            val since = store.getBriefsSinceCursor()

            do {
                val currentCursor = cursor
                val query = buildString {
                    append("/device/briefs?limit=").append(PAGE_LIMIT)
                    if (!since.isNullOrBlank()) {
                        append("&since=").append(URLEncoder.encode(since, "UTF-8"))
                    }
                    if (currentCursor != null && currentCursor > 0L) {
                        append("&cursor=").append(currentCursor)
                    }
                }

                val response = api.get(query)
                val data = response.optJSONObject("data") ?: break

                val briefsJson = data.optJSONArray("briefs") ?: JSONArray()
                val briefEntities = ArrayList<BriefEntity>(briefsJson.length())
                val phoneEntities = ArrayList<BriefPhoneEntity>()
                val touchedCustomerIds = ArrayList<Long>(briefsJson.length())

                for (i in 0 until briefsJson.length()) {
                    val obj = briefsJson.optJSONObject(i) ?: continue
                    val customerId = obj.optLong("customer_id", 0L)
                    if (customerId <= 0L) continue

                    touchedCustomerIds.add(customerId)

                    val activeItemsJson = (obj.optJSONArray("active_items") ?: JSONArray()).toString()
                    val lastPaymentObj = obj.optJSONObject("last_payment")
                    val lastPaymentJson = lastPaymentObj?.toString()

                    briefEntities.add(
                        BriefEntity(
                            customerId = customerId,
                            displayName = obj.optString("display_name", "Customer #$customerId"),
                            companyName = obj.optNullableString("company_name"),
                            parentCompany = obj.optNullableString("parent_company"),
                            rating = if (obj.isNull("rating")) null else obj.optDouble("rating"),
                            status = obj.optString("status", "Active"),
                            balanceDue = obj.optDouble("balance_due", 0.0),
                            depositBalance = obj.optDouble("deposit_balance", 0.0),
                            idRetained = obj.optBoolean("id_retained", false),
                            activeItemsJson = activeItemsJson,
                            invoiceCount = obj.optInt("invoice_count", 0),
                            firstRental = obj.optNullableString("first_rental"),
                            lastRental = obj.optNullableString("last_rental"),
                            lastPaymentJson = lastPaymentJson,
                            lastReviewComment = obj.optNullableString("last_review_comment"),
                            summaryLine = obj.optString("summary_line", ""),
                            updatedAt = obj.optString("updated_at", "")
                        )
                    )

                    val phonesJson = obj.optJSONArray("phones") ?: JSONArray()
                    for (pIdx in 0 until phonesJson.length()) {
                        val phoneE164 = phonesJson.optString(pIdx).trim()
                        if (phoneE164.isNotEmpty()) {
                            phoneEntities.add(
                                BriefPhoneEntity(
                                    phoneE164 = phoneE164,
                                    customerId = customerId
                                )
                            )
                        }
                    }
                }

                if (touchedCustomerIds.isNotEmpty()) {
                    touchedCustomerIds.chunked(SQLITE_BIND_CHUNK).forEach { chunk ->
                        briefDao.deleteBriefPhonesByCustomerIds(chunk)
                    }
                }
                if (briefEntities.isNotEmpty()) {
                    briefDao.upsertBriefs(briefEntities)
                }
                if (phoneEntities.isNotEmpty()) {
                    briefDao.upsertBriefPhones(phoneEntities)
                }

                val deletedJson = data.optJSONArray("deleted_customer_ids") ?: JSONArray()
                if (deletedJson.length() > 0) {
                    val deletedIds = ArrayList<Long>(deletedJson.length())
                    for (dIdx in 0 until deletedJson.length()) {
                        val id = deletedJson.optLong(dIdx, 0L)
                        if (id > 0L) deletedIds.add(id)
                    }
                    deletedIds.chunked(SQLITE_BIND_CHUNK).forEach { chunk ->
                        briefDao.deleteBriefPhonesByCustomerIds(chunk)
                        briefDao.deleteBriefsByCustomerIds(chunk)
                    }
                }

                val serverTime = data.optNullableString("server_time")
                if (!serverTime.isNullOrBlank()) {
                    lastServerTime = serverTime
                }

                val nextCursor = if (data.isNull("next_cursor")) null else data.optLong("next_cursor", 0L)
                cursor = if (nextCursor != null && nextCursor > 0L) nextCursor else null
            } while (cursor != null)

            if (!lastServerTime.isNullOrBlank()) {
                store.setBriefsSinceCursor(lastServerTime)
                store.setBriefsSyncedAt(lastServerTime)
            }
            store.setLastSyncSuccessMs(System.currentTimeMillis())
            return@withContext Result.success()
        } catch (e: DeviceApiClient.HttpException) {
            return@withContext if (e.code == 401 || e.code == 403 || e.code == 409) {
                Result.failure()
            } else {
                Result.retry()
            }
        } catch (_: IOException) {
            return@withContext Result.retry()
        } catch (_: Exception) {
            return@withContext Result.retry()
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        val value = optString(key, "").trim()
        return if (value.isEmpty() || value == "null") null else value
    }

    companion object {
        private const val PAGE_LIMIT = 500
        private const val SQLITE_BIND_CHUNK = 200
        private const val UNIQUE_PERIODIC = "geargrid_brief_sync_periodic"
        private const val UNIQUE_ONESHOT = "geargrid_brief_sync_oneshot"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<BriefSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
            OutboxWorker.enqueueOneShot(context)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_PERIODIC)
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_ONESHOT)
            OutboxWorker.cancel(context)
        }

        fun enqueueOneShot(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<BriefSyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_ONESHOT, ExistingWorkPolicy.REPLACE, req)
            OutboxWorker.enqueueOneShot(context)
        }
    }
}
