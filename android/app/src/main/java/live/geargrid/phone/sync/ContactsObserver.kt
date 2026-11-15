package live.geargrid.phone.sync

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
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

        fun register(context: Context) {
            if (instance != null) return
            val observer = ContactsObserver(context.applicationContext)
            context.applicationContext.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                observer
            )
            instance = observer
        }

        fun unregister(context: Context) {
            instance?.let {
                context.applicationContext.contentResolver.unregisterContentObserver(it)
            }
            instance = null
        }
    }
}
