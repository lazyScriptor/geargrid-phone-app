package live.geargrid.phone.call

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Silently locates a built-in call recording in Android's MediaStore after a call ends.
 * Never shows UI, toasts, or notifications.
 */
object CallRecordingFinder {

    private const val MAX_RECORDING_BYTES = 50L * 1024L * 1024L

    private val CALL_RECORDING_FOLDER_HINTS = listOf(
        "recordings/call",
        "callrecordings",
        "call_recordings",
        "call recording",
        "call_rec",
        "miui/sound_recorder/call_rec",
        "sound_recorder/call_rec",
        "record/call",
        "sounds/callrecord",
        "recorder/call",
        "voice recorder/call"
    )

    data class MatchedRecording(
        val contentUri: String,
        val filePath: String?,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val durationSeconds: Int?
    )

    fun hasAudioPermission(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Queries MediaStore.Audio.Media for a recording created between [startedAtMs] and
     * [endedAtMs] + 30s. Retries once after 1.5s if not yet indexed by MediaScanner.
     */
    suspend fun findRecording(
        context: Context,
        startedAtMs: Long,
        endedAtMs: Long,
        rawNumber: String?,
        contactName: String?
    ): MatchedRecording? {
        if (!hasAudioPermission(context)) return null
        findOnce(context, startedAtMs, endedAtMs, rawNumber, contactName)?.let { return it }
        delay(1_500L)
        return findOnce(context, startedAtMs, endedAtMs, rawNumber, contactName)
    }

    private fun findOnce(
        context: Context,
        startedAtMs: Long,
        endedAtMs: Long,
        rawNumber: String?,
        contactName: String?
    ): MatchedRecording? {
        val windowStartSec = (startedAtMs / 1000L) - 5L
        val windowEndSec = ((endedAtMs + 30_000L) / 1000L) + 5L

        val numberTail = rawNumber
            ?.filter { it.isDigit() }
            ?.takeLast(9)
            ?.takeIf { it.length >= 6 }

        val nameTokens = contactName
            ?.lowercase()
            ?.split(Regex("\\s+"))
            ?.map { it.trim() }
            ?.filter { it.length >= 3 }
            .orEmpty()

        val projectionList = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projectionList.add(MediaStore.Audio.Media.RELATIVE_PATH)
        } else {
            @Suppress("DEPRECATION")
            projectionList.add(MediaStore.Audio.Media.DATA)
        }

        val selection = "(${MediaStore.Audio.Media.DATE_MODIFIED} >= ? AND ${MediaStore.Audio.Media.DATE_MODIFIED} <= ?) " +
            "OR (${MediaStore.Audio.Media.DATE_ADDED} >= ? AND ${MediaStore.Audio.Media.DATE_ADDED} <= ?)"
        val selectionArgs = arrayOf(
            windowStartSec.toString(),
            windowEndSec.toString(),
            windowStartSec.toString(),
            windowEndSec.toString()
        )

        var bestMatch: MatchedRecording? = null
        var bestScore = 0

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projectionList.toTypedArray(),
                selection,
                selectionArgs,
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val durIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val pathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                }

                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    if (size <= 0L || size > MAX_RECORDING_BYTES) continue

                    val displayName = (if (nameIdx >= 0) cursor.getString(nameIdx) else null)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "call_recording.m4a"
                    val relOrDataPath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                    val mimeType = (if (mimeIdx >= 0) cursor.getString(mimeIdx) else null)
                        ?.trim()
                        ?.takeIf { it.startsWith("audio/") } ?: "audio/mp4"
                    val durationMs = if (durIdx >= 0) cursor.getLong(durIdx) else 0L
                    val durationSec = if (durationMs > 0L) (durationMs / 1000L).toInt() else null

                    val pathLower = "$relOrDataPath/$displayName".lowercase().replace('\\', '/')
                    val folderMatch = CALL_RECORDING_FOLDER_HINTS.any { hint -> pathLower.contains(hint) }

                    val nameDigits = displayName.filter { it.isDigit() }
                    val numberMatch = numberTail != null && nameDigits.contains(numberTail)

                    val nameLower = displayName.lowercase()
                    val contactMatch = nameTokens.any { token -> nameLower.contains(token) }

                    // Require either a known call-recording folder or a number/contact match in filename
                    // so unrelated audio (e.g. music or voice notes) is never picked up.
                    if (!folderMatch && !numberMatch && !contactMatch) continue

                    var score = 0
                    if (folderMatch) score += 100
                    if (numberMatch) score += 50
                    if (contactMatch) score += 30

                    if (score > bestScore) {
                        bestScore = score
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id
                        ).toString()
                        bestMatch = MatchedRecording(
                            contentUri = contentUri,
                            filePath = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) relOrDataPath else null,
                            displayName = displayName,
                            mimeType = mimeType,
                            sizeBytes = size,
                            durationSeconds = durationSec
                        )
                    }
                }
            }
        }

        return bestMatch
    }
}
