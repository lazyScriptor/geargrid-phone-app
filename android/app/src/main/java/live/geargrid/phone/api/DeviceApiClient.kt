package live.geargrid.phone.api

import live.geargrid.phone.CredentialStore
import live.geargrid.phone.RevocationHandler
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class DeviceApiClient(private val store: CredentialStore) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    fun checkOfflineWipe(): Boolean {
        val lastSync = store.getLastSyncSuccessMs()
        if (lastSync <= 0L) return false
        val offlineWipeDays = store.getOfflineWipeDays().coerceAtLeast(1)
        val cutoffMs = offlineWipeDays.toLong() * 86_400_000L
        if (System.currentTimeMillis() - lastSync > cutoffMs) {
            RevocationHandler.wipe(store.appContext, keepCredentials = true)
            return true
        }
        return false
    }

    fun rotateTokenIfNeeded() {
        if (!store.hasCredentials()) return
        val issuedAt = store.getTokenIssuedAtMs()
        if (issuedAt <= 0L) {
            store.setTokenIssuedAtMs(System.currentTimeMillis())
            return
        }
        val thirtyDaysMs = 30L * 86_400_000L
        if (System.currentTimeMillis() - issuedAt < thirtyDaysMs) return
        try {
            val res = post("/device/token/rotate", JSONObject())
            // The server answers { status, data: { device_token } }.
            val newToken = res.optJSONObject("data")?.optString("device_token", "")?.trim().orEmpty()
            if (newToken.isNotEmpty()) {
                store.updateToken(newToken)
            }
        } catch (e: HttpException) {
            if (e.code == 401 || e.code == 403) throw e
        } catch (_: Exception) {
            // Non-fatal: retry on next sync; old token remains valid within grace period
        }
    }

    private fun handleHttpError(code: Int, raw: String): Nothing {
        if (code == 401) {
            RevocationHandler.wipe(store.appContext, keepCredentials = false)
        } else if (code == 403) {
            val errorCode = runCatching {
                val obj = JSONObject(raw)
                obj.optString("code").ifBlank { obj.optString("error") }
            }.getOrDefault("")
            if (errorCode == "device_suspended" ||
                (errorCode != "shop_inactive" && errorCode != "device_pending" && errorCode != "device_revoked" && raw.contains("device_suspended"))
            ) {
                store.setDeviceStatus("SUSPENDED")
                RevocationHandler.wipe(store.appContext, keepCredentials = true)
            } else if (errorCode == "device_pending") {
                store.setDeviceStatus("PENDING")
            } else if (errorCode == "device_revoked" ||
                (errorCode != "shop_inactive" && raw.contains("device_revoked"))
            ) {
                RevocationHandler.wipe(store.appContext, keepCredentials = false)
            }
        }
        throw HttpException(code, raw)
    }

    fun post(path: String, body: JSONObject): JSONObject {
        val deviceId = store.getDeviceId()
            ?: throw IOException("Device is not paired")
        val token = store.getDeviceToken()
            ?: throw IOException("Device token missing")
        val tenantDb = store.getTenantDb()
            ?: throw IOException("Tenant DB missing")

        val base = store.getApiBaseUrl().trimEnd('/')
        val requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("$base$path")
            .header("x-device-id", deviceId)
            .header("x-tenant-db", tenantDb)
            .header("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                handleHttpError(response.code, raw)
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    fun get(pathWithQuery: String): JSONObject {
        val deviceId = store.getDeviceId()
            ?: throw IOException("Device is not paired")
        val token = store.getDeviceToken()
            ?: throw IOException("Device token missing")
        val tenantDb = store.getTenantDb()
            ?: throw IOException("Tenant DB missing")

        val base = store.getApiBaseUrl().trimEnd('/')
        val request = Request.Builder()
            .url("$base$pathWithQuery")
            .header("x-device-id", deviceId)
            .header("x-tenant-db", tenantDb)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                handleHttpError(response.code, raw)
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    /** Multipart upload that streams the file from [open] instead of holding it in memory. */
    fun postFile(
        path: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        fields: Map<String, String>,
        open: () -> InputStream
    ): JSONObject {
        val deviceId = store.getDeviceId()
            ?: throw IOException("Device is not paired")
        val token = store.getDeviceToken()
            ?: throw IOException("Device token missing")
        val tenantDb = store.getTenantDb()
            ?: throw IOException("Tenant DB missing")

        val base = store.getApiBaseUrl().trimEnd('/')
        val fileType = mimeType.toMediaTypeOrNull()
        val fileBody = object : RequestBody() {
            override fun contentType(): MediaType? = fileType

            override fun writeTo(sink: BufferedSink) {
                open().use { sink.writeAll(it.source()) }
            }
        }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(fieldName, fileName, fileBody)
        fields.forEach { (key, value) -> multipart.addFormDataPart(key, value) }

        val request = Request.Builder()
            .url("$base$path")
            .header("x-device-id", deviceId)
            .header("x-tenant-db", tenantDb)
            .header("Authorization", "Bearer $token")
            .post(multipart.build())
            .build()

        val uploadClient = client.newBuilder()
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        uploadClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                handleHttpError(response.code, raw)
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    class HttpException(val code: Int, override val message: String) : IOException("HTTP $code: $message")
}
