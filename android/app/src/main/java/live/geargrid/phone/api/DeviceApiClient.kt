package live.geargrid.phone.api

import live.geargrid.phone.CredentialStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class DeviceApiClient(private val store: CredentialStore) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

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
                throw HttpException(response.code, raw)
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
                throw HttpException(response.code, raw)
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    class HttpException(val code: Int, override val message: String) : IOException("HTTP $code: $message")
}
