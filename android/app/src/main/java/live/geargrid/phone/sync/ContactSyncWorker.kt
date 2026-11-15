package live.geargrid.phone.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
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
import live.geargrid.phone.PhoneNormalizer
import live.geargrid.phone.api.DeviceApiClient
import live.geargrid.phone.db.AppDatabase
import live.geargrid.phone.db.ContactIndexEntity
import live.geargrid.phone.db.ContactStateEntity
import live.geargrid.phone.db.OutboxEntity
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit

class ContactSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    data class ScannedContact(
        val key: String,
        val name: String,
        val phones: List<String>,
        val hash: String
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = CredentialStore(applicationContext)
        if (!store.isContactSyncEnabled() || !store.hasContactsConsent()) {
            return@withContext Result.success()
        }
        if (!store.hasCredentials()) {
            store.setContactsSyncError(ERROR_NOT_PAIRED)
            return@withContext Result.success()
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            store.setContactsSyncError(ERROR_NO_PERMISSION)
            return@withContext Result.success()
        }

        val db = AppDatabase.getInstance(applicationContext)
        val dao = db.contactSyncDao()
        val api = DeviceApiClient(store)

        val scanned = scanDeviceContacts()
        val previousStates = dao.getAllState()
        val previousByKey = previousStates.associateBy { it.contactKey }
        val isInitialFullSync = previousStates.isEmpty()

        val upserts = if (isInitialFullSync) {
            scanned.values.toList()
        } else {
            scanned.values.filter { c -> previousByKey[c.key]?.payloadHash != c.hash }
        }
        val deletedKeys = previousStates
            .map { it.contactKey }
            .filter { !scanned.containsKey(it) }

        if (!isInitialFullSync && upserts.isEmpty() && deletedKeys.isEmpty()) {
            // Nothing changed, but the check succeeded: record it so "Sync now" shows a result.
            store.setContactsSyncError(null)
            store.setContactsSyncedAt(Instant.now().toString())
            return@withContext Result.success()
        }

        val chunks = if (upserts.isEmpty()) listOf(emptyList()) else upserts.chunked(CHUNK_SIZE)
        val seenKeys = scanned.keys.toList()
        var lastServerTime: String? = null

        try {
            chunks.forEachIndexed { idx, chunk ->
                val isLast = idx == chunks.lastIndex
                val body = JSONObject().apply {
                    put("full", isInitialFullSync)
                    put("final", isLast)
                    put("upserts", JSONArray().apply {
                        chunk.forEach { contact ->
                            put(JSONObject().apply {
                                put("key", contact.key)
                                put("name", contact.name)
                                put("phones", JSONArray(contact.phones))
                            })
                        }
                    })
                    put("deletes", if (idx == 0) JSONArray(deletedKeys) else JSONArray())
                    if (isInitialFullSync && isLast) {
                        put("seen_keys", JSONArray(seenKeys))
                    }
                }

                try {
                    val response = api.post("/device/contacts/sync", body)
                    val dataObj = response.optJSONObject("data")
                    val serverTime = dataObj?.optString("server_time").orEmpty()
                    if (serverTime.isNotBlank()) {
                        lastServerTime = serverTime
                    }
                } catch (e: DeviceApiClient.HttpException) {
                    store.setContactsSyncError(errorCodeFor(e.code))
                    if (e.code == 401 || e.code == 403 || e.code == 409) {
                        return@withContext Result.failure()
                    }
                    dao.enqueueOutbox(
                        OutboxEntity(
                            kind = OutboxWorker.KIND_CONTACT_SYNC_CHUNK,
                            payloadJson = body.toString(),
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    OutboxWorker.enqueueOneShot(applicationContext)
                    return@withContext Result.retry()
                }
            }
        } catch (e: Exception) {
            store.setContactsSyncError(ERROR_NETWORK)
            return@withContext Result.retry()
        }

        val now = System.currentTimeMillis()
        if (upserts.isNotEmpty()) {
            dao.upsertStates(upserts.map { ContactStateEntity(it.key, it.hash, now) })
            val indexRows = upserts.flatMap { c ->
                c.phones.mapNotNull { raw ->
                    val normalized = PhoneNormalizer.normalizeToDigits(raw) ?: return@mapNotNull null
                    ContactIndexEntity(normalized, c.name, c.key, now)
                }
            }
            if (indexRows.isNotEmpty()) {
                dao.upsertContactIndex(indexRows)
            }
        }
        if (deletedKeys.isNotEmpty()) {
            deletedKeys.chunked(500).forEach { batch ->
                dao.deleteStatesByKeys(batch)
                dao.deleteContactIndexByKeys(batch)
            }
        }

        store.setContactsSyncError(null)
        store.setContactsSyncedAt(lastServerTime ?: Instant.now().toString())
        OutboxWorker.enqueueOneShot(applicationContext)
        Result.success()
    }

    /**
     * Strict privacy boundary: queries ONLY contact key, display name, and phone number
     * from ContactsContract.CommonDataKinds.Phone. Photos, emails, addresses, and notes
     * are never read or transmitted.
     */
    private fun scanDeviceContacts(): Map<String, ScannedContact> {
        val grouped = linkedMapOf<String, Pair<String, LinkedHashSet<String>>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        applicationContext.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val lookupIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val contactId = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                val lookup = if (lookupIdx >= 0) cursor.getString(lookupIdx) else null
                val key = contactKeyFor(lookup, contactId)
                val name = (if (nameIdx >= 0) cursor.getString(nameIdx) else null)?.trim().orEmpty()
                    .take(MAX_NAME_LENGTH)
                val rawNum = (if (numIdx >= 0) cursor.getString(numIdx) else null)?.trim().orEmpty()
                if (name.isEmpty() || rawNum.isEmpty()) continue

                val entry = grouped.getOrPut(key) { name to linkedSetOf() }
                for (part in PhoneNormalizer.splitPhones(rawNum)) {
                    if (part.length <= MAX_PHONE_LENGTH && PhoneNormalizer.normalizeToDigits(part) != null) {
                        entry.second.add(part)
                    }
                }
            }
        }

        val out = linkedMapOf<String, ScannedContact>()
        for ((key, pair) in grouped) {
            val (name, phoneSet) = pair
            if (phoneSet.isEmpty()) continue
            val sortedPhones = phoneSet.sorted().take(MAX_PHONES_PER_CONTACT)
            val hash = sha256("$name|${sortedPhones.joinToString(",")}")
            out[key] = ScannedContact(key = key, name = name, phones = sortedPhones, hash = hash)
        }
        return out
    }

    /**
     * The server accepts keys of at most 64 characters, and one longer key rejects the whole
     * batch. Android lookup keys (especially Google-synced ones) are often longer, so those
     * become a stable hash of the lookup key.
     */
    private fun contactKeyFor(lookup: String?, contactId: Long): String {
        val raw = lookup?.takeIf { it.isNotBlank() } ?: return "cid:$contactId"
        return if (raw.length <= MAX_KEY_LENGTH) raw else "lk:" + sha256(raw).take(40)
    }

    private fun errorCodeFor(httpCode: Int): String = when {
        httpCode == 401 -> ERROR_UNAUTHORIZED
        httpCode == 403 -> ERROR_FORBIDDEN
        httpCode in 400..499 -> ERROR_REJECTED
        else -> ERROR_SERVER
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val CHUNK_SIZE = 500

        // Limits from ContactSyncUpsertSchema in @geargrid/contracts.
        private const val MAX_KEY_LENGTH = 64
        private const val MAX_NAME_LENGTH = 255
        private const val MAX_PHONE_LENGTH = 50
        private const val MAX_PHONES_PER_CONTACT = 20

        // Reported to the web app as contactsSyncError; it translates each code.
        const val ERROR_NOT_PAIRED = "not_paired"
        const val ERROR_NO_PERMISSION = "no_permission"
        const val ERROR_UNAUTHORIZED = "unauthorized"
        const val ERROR_FORBIDDEN = "forbidden"
        const val ERROR_REJECTED = "rejected"
        const val ERROR_SERVER = "server"
        const val ERROR_NETWORK = "network"
        private const val UNIQUE_PERIODIC = "geargrid_contact_sync_periodic"
        private const val UNIQUE_ONESHOT = "geargrid_contact_sync_oneshot"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<ContactSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_PERIODIC)
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_ONESHOT)
        }

        fun enqueueOneShot(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<ContactSyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_ONESHOT, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
