package live.geargrid.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.getcapacitor.BridgeActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import live.geargrid.phone.api.DeviceApiClient
import live.geargrid.phone.sync.BriefSyncWorker
import live.geargrid.phone.sync.ContactSyncWorker
import live.geargrid.phone.sync.OutboxWorker
import org.json.JSONObject

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        GearGridMessagingService.ensureNotificationChannels(this)
        registerPlugin(PhoneAssistantPlugin::class.java)
        super.onCreate(savedInstanceState)
        handlePushIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePushIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val store = CredentialStore(this)
        if (store.hasCredentials()) {
            BriefSyncWorker.schedulePeriodic(this)
            BriefSyncWorker.enqueueOneShot(this)
            OutboxWorker.enqueueOneShot(this)
            if (store.isContactSyncEnabled()) {
                ContactSyncWorker.enqueueOneShot(this)
            }
            syncFcmTokenIfAvailable(store)
        }
        handlePushIntent(intent)
    }

    private fun syncFcmTokenIfAvailable(store: CredentialStore) {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) return
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (token.isNullOrBlank()) return@addOnSuccessListener
                val prev = store.getPushToken()
                if (prev == token) return@addOnSuccessListener
                store.setPushToken(token)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val versionName = try {
                            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
                        } catch (_: Exception) {
                            "1.0.0"
                        }
                        DeviceApiClient(store).post(
                            "/device/register",
                            JSONObject().apply {
                                put("app_version", versionName)
                                put("push_token", token)
                                put("contact_sync_enabled", store.isContactSyncEnabled())
                            }
                        )
                    } catch (_: Exception) {
                        // Retry on next resume
                    }
                }
            }
        } catch (_: Exception) {
            // google-services.json not bundled in local dev build
        }
    }

    private fun handlePushIntent(intent: Intent?) {
        if (intent == null) return
        val pushType = intent.getStringExtra(GearGridMessagingService.EXTRA_PUSH_TYPE)
        val rawInvoiceId = intent.getStringExtra(GearGridMessagingService.EXTRA_INVOICE_ID)
        val invoiceId = rawInvoiceId?.trim()?.toLongOrNull() ?: return
        if (invoiceId <= 0) return

        if (!pushType.isNullOrBlank() && pushType != "invoice_created" && pushType != "payment_received" && pushType != "return_processed") {
            return
        }

        intent.removeExtra(GearGridMessagingService.EXTRA_INVOICE_ID)
        intent.removeExtra(GearGridMessagingService.EXTRA_PUSH_TYPE)

        val origin = resolveWebOrigin()
        val targetUrl = "$origin/device/invoice/$invoiceId"
        bridge?.webView?.postDelayed({
            bridge?.webView?.loadUrl(targetUrl)
        }, 150)
    }

    private fun resolveWebOrigin(): String {
        val rawServerUrl = bridge?.serverUrl
        if (!rawServerUrl.isNullOrBlank()) {
            try {
                val parsed = Uri.parse(rawServerUrl)
                val scheme = parsed.scheme
                val authority = parsed.encodedAuthority
                if ((scheme == "https" || scheme == "http") && !authority.isNullOrBlank()) {
                    return "$scheme://$authority"
                }
            } catch (_: Exception) {
                // Fall back to production origin
            }
        }
        return "https://app.geargrid.live"
    }
}
