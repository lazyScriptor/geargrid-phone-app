package live.geargrid.phone

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import live.geargrid.phone.call.ActiveCallSession
import live.geargrid.phone.call.CallerOverlayManager
import live.geargrid.phone.db.AppDatabase
import live.geargrid.phone.sync.BriefSyncWorker
import live.geargrid.phone.sync.ContactSyncWorker
import live.geargrid.phone.sync.ContactsObserver
import live.geargrid.phone.sync.OutboxWorker

/**
 * Central wipe handler for device revocation, suspension, cache clears, and manual unpair.
 * Clears scheduled workers, observers, active overlays, Room tables, and optionally credentials.
 */
object RevocationHandler {

    fun wipe(context: Context, keepCredentials: Boolean = false) {
        val appContext = context.applicationContext ?: context
        wipeFastSync(appContext, keepCredentials)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            CoroutineScope(Dispatchers.IO).launch {
                wipeDatabaseSync(appContext)
            }
        } else {
            wipeDatabaseSync(appContext)
        }
    }

    suspend fun wipeSuspend(context: Context, keepCredentials: Boolean = false) {
        val appContext = context.applicationContext ?: context
        wipeFastSync(appContext, keepCredentials)
        withContext(Dispatchers.IO) {
            wipeDatabaseSync(appContext)
        }
    }

    /** Suspended phones keep their pairing but the signed-in application is signed out. */
    fun signOutFullApp(context: Context) {
        runCatching { CredentialStore(context.applicationContext).setPendingWebSignOut(true) }
    }

    private fun wipeFastSync(appContext: Context, keepCredentials: Boolean) {
        runCatching { ContactSyncWorker.cancelPeriodic(appContext) }
        runCatching { BriefSyncWorker.cancelPeriodic(appContext) }
        runCatching { OutboxWorker.cancel(appContext) }
        runCatching { ContactsObserver.unregister(appContext) }
        runCatching { CallerOverlayManager.dismiss(appContext) }
        runCatching { ActiveCallSession.consume(appContext) }
        runCatching {
            appContext.getSharedPreferences("geargrid_call_fallback", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
        if (!keepCredentials) {
            runCatching { CredentialStore(appContext).clearCredentials() }
            // A revoked or unpaired phone also loses everything the WebView holds for the application.
            runCatching {
                android.os.Handler(Looper.getMainLooper()).post {
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.WebStorage.getInstance().deleteAllData()
                }
            }
        }
    }

    private fun wipeDatabaseSync(appContext: Context) {
        runCatching {
            AppDatabase.getInstance(appContext).clearAllTables()
        }
    }
}
