package live.geargrid.phone.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import live.geargrid.phone.CredentialStore

class ContactsObserver(
    private val context: Context,
    handler: Handler = Handler(Looper.getMainLooper())
) : ContentObserver(handler) {

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        val store = CredentialStore(context)
        if (store.hasCredentials() && store.isContactSyncEnabled() && store.hasContactsConsent()) {
            ContactSyncWorker.enqueueOneShot(context)
        }
    }

    companion object {
        @Volatile
        private var instance: ContactsObserver? = null

        /** Watching contacts without READ_CONTACTS throws SecurityException and closes the app. */
        fun register(context: Context) {
            if (instance != null) return
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
            val observer = ContactsObserver(context.applicationContext)
            try {
                context.applicationContext.contentResolver.registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI,
                    true,
                    observer
                )
                instance = observer
            } catch (_: SecurityException) {
                // Permission was revoked between the check and the call; the next app start retries.
            }
        }

        fun unregister(context: Context) {
            instance?.let {
                context.applicationContext.contentResolver.unregisterContentObserver(it)
            }
            instance = null
        }
    }
}
