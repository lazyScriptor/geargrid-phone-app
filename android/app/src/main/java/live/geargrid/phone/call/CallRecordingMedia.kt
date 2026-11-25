package live.geargrid.phone.call

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import live.geargrid.phone.PhoneNormalizer
import live.geargrid.phone.db.AppDatabase

/**
 * Reads the phone's audio library for [RecordingRules]. Silent: no UI, toast, notification or log of
 * file names.
 */
object CallRecordingMedia {

    fun hasPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Audio files last written around the end of the call. [RecordingRules] decides which one fits. */
    fun candidates(context: Context, call: RecordedCall): List<AudioCandidate> {
        val fromSeconds = (call.endedAtMs - RecordingRules.FINISHED_BEFORE_END_MS) / 1000L - 1L
        val toSeconds = (call.endedAtMs + RecordingRules.FINISHED_AFTER_END_MS) / 1000L + 1L
        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE
        )
        val found = ArrayList<AudioCandidate>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            columns,
            "${MediaStore.Audio.Media.DATE_MODIFIED} BETWEEN ? AND ?",
            arrayOf(fromSeconds.toString(), toSeconds.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                found.add(
                    AudioCandidate(
                        id = cursor.getLong(0),
                        displayName = cursor.getString(1).orEmpty(),
                        relativePath = cursor.getString(2).orEmpty(),
                        addedMs = cursor.getLong(3) * 1000L,
                        modifiedMs = cursor.getLong(4) * 1000L,
                        durationMs = cursor.getLong(5),
                        sizeBytes = cursor.getLong(6),
                        mimeType = cursor.getString(7)?.takeIf { it.startsWith("audio/") } ?: "audio/mp4"
                    )
                )
            }
        }
        return found
    }

    fun uriFor(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    /** The names a number goes by on this phone. Used only to compare with file names, never sent. */
    suspend fun contactNames(context: Context, number: String?): List<String> {
        if (number.isNullOrBlank()) return emptyList()
        val names = LinkedHashSet<String>()
        PhoneNormalizer.normalizeToDigits(number)?.let { digits ->
            val dao = AppDatabase.getInstance(context).briefDao()
            dao.findBriefByPhone(digits)?.let { brief ->
                names.add(brief.displayName)
                brief.companyName?.let { names.add(it) }
            }
            dao.findContactByPhone(digits)?.let { names.add(it.displayName) }
        }
        // The recorder names files after the address book entry, which may differ from the shop's.
        val canReadContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (canReadContacts) {
            runCatching {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { names.add(it) }
                    }
                }
            }
        }
        return names.toList()
    }
}
