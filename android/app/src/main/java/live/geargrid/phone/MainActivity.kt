package live.geargrid.phone

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
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
        applyWindowInsetsAndInitialStatusBar()
        // The WebView can be inspected from a computer only in debug builds.
        WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        installBackHandler()
        installDownloads()
        handlePushIntent(intent)
    }

    /** Pad the WebView container for system bars, camera cut-outs, and the soft keyboard. */
    private fun applyWindowInsetsAndInitialStatusBar() {
        val rootView = bridge?.webView?.parent as? View
        if (rootView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
            ViewCompat.requestApplyInsets(rootView)
        }
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applyStatusBarTheme(systemDark, null)
    }

    /** Match the status bar & root inset background to the active web theme. */
    fun applyStatusBarTheme(dark: Boolean, colorHex: String? = null) {
        val fallback = if (dark) "#0F172A" else "#FFFFFF"
        val bgColor = runCatching { Color.parseColor(colorHex?.takeIf { it.isNotBlank() } ?: fallback) }
            .getOrElse { Color.parseColor(fallback) }
        runOnUiThread {
            (bridge?.webView?.parent as? View)?.setBackgroundColor(bgColor)
            @Suppress("DEPRECATION")
            window.statusBarColor = bgColor
            @Suppress("DEPRECATION")
            window.navigationBarColor = bgColor
            WindowCompat.getInsetsController(window, window.decorView)?.apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    /** Back closes overlays first, steps back in history, returns from full-system root to Alerts, and asks to exit on Alerts. */
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = bridge?.webView
                if (web == null) {
                    showExitDialog()
                    return
                }
                web.evaluateJavascript("Boolean(window.__geargridHandleBack && window.__geargridHandleBack())") { result ->
                    if (result == "true") return@evaluateJavascript
                    val path = runCatching { Uri.parse(web.url ?: "").path.orEmpty().trimEnd('/') }.getOrDefault("")
                    val onHome = path.isEmpty() || path == "/device" || path == "/device/alerts"
                    if (onHome) {
                        showExitDialog()
                        return@evaluateJavascript
                    }
                    if (web.canGoBack()) {
                        web.goBack()
                        return@evaluateJavascript
                    }
                    web.loadUrl("${resolveWebOrigin()}/device/alerts")
                }
            }
        })
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this@MainActivity)
            .setMessage(R.string.exit_confirm)
            .setPositiveButton(R.string.exit_yes) { _, _ -> finish() }
            .setNegativeButton(R.string.exit_no, null)
            .show()
    }

    /** Reports, exports and other http(s) downloads go to Android's download manager. */
    private fun installDownloads() {
        bridge?.webView?.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (!url.startsWith("http")) return@setDownloadListener
            runCatching {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType))
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        CredentialStore(this).setBackgroundedAtMs(System.currentTimeMillis())
    }

    /**
     * After time in the background the application signs out (pairing is untouched), and the phone's own
     * lock is asked again before the application shows. Both only matter while someone is signed in.
     */
    private fun enforceFullAppSession(store: CredentialStore) {
        val backgroundedAt = store.getBackgroundedAtMs()
        store.setBackgroundedAtMs(0L)
        if (backgroundedAt <= 0L) return
        val awayMs = System.currentTimeMillis() - backgroundedAt
        if (store.consumePendingWebSignOut() || (store.isFullAppSignedIn() && awayMs > store.getFullAppSessionMinutes() * 60_000L)) {
            signOutFullApp(store)
            return
        }
        val onApplicationPage = runCatching { !Uri.parse(bridge?.webView?.url ?: "").path.orEmpty().startsWith("/device") }.getOrDefault(false)
        if (store.isFullAppSignedIn() && store.isAppLockEnabled() && onApplicationPage && awayMs > APP_LOCK_GRACE_MS) {
            AppLock.authenticate(this as FragmentActivity, getString(R.string.app_name)) { ok, _ ->
                if (!ok) bridge?.webView?.post { bridge?.webView?.loadUrl("${resolveWebOrigin()}/device/alerts") }
            }
        }
    }

    private fun signOutFullApp(store: CredentialStore) {
        store.setFullAppSignedIn(false)
        val web = bridge?.webView ?: return
        web.post {
            CookieManager.getInstance().removeAllCookies(null)
            web.evaluateJavascript(
                "try{localStorage.removeItem('geargrid-auth-storage');sessionStorage.clear();}catch(e){}window.location.href='/device/alerts';",
                null
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePushIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val store = CredentialStore(this)
        // Optional: the shop can block screenshots of the application.
        if (store.blockScreenshots()) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enforceFullAppSession(store)
        if (store.hasCredentials()) {
            BriefSyncWorker.schedulePeriodic(this)
            BriefSyncWorker.enqueueOneShot(this)
            OutboxWorker.enqueueOneShot(this)
            if (store.isContactSyncEnabled()) {
                ContactSyncWorker.enqueueOneShot(this)
            }
            syncFcmTokenIfAvailable(store)
            // Permissions granted in Android Settings reach the server the next time the app opens.
            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
            } catch (_: Exception) {
                "1.0.0"
            }
            DeviceStateReporter.reportIfChanged(this, versionName)
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
        val rawInvoiceId = intent.getStringExtra(GearGridMessagingService.EXTRA_INVOICE_ID)
        val invoiceId = rawInvoiceId?.trim()?.toLongOrNull() ?: return
        if (invoiceId <= 0) return

        val eventId = intent.getStringExtra(GearGridMessagingService.EXTRA_EVENT_ID)?.trim().orEmpty()
        intent.removeExtra(GearGridMessagingService.EXTRA_INVOICE_ID)
        intent.removeExtra(GearGridMessagingService.EXTRA_PUSH_TYPE)
        intent.removeExtra(GearGridMessagingService.EXTRA_EVENT_ID)

        val origin = resolveWebOrigin()
        val query = if (eventId.isNotEmpty()) "?from=${Uri.encode(eventId)}" else ""
        val targetUrl = "$origin/device/invoice/$invoiceId$query"
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

    companion object {
        private const val APP_LOCK_GRACE_MS = 60_000L
    }
}
