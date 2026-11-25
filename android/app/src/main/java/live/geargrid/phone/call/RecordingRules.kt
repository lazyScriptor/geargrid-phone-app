package live.geargrid.phone.call

/**
 * Which audio file is the recording of a call, and what to do when its upload fails.
 * No Android imports, so these rules run in plain unit tests.
 *
 * The one rule above all others: never attach the wrong audio to a call. When in doubt, nothing is
 * uploaded, and the administrator's console shows that this phone is not sending recordings.
 */

/** What is known about a call that just ended. */
data class RecordedCall(
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** Talk time from Android's call record; null when it could not be read. */
    val talkSeconds: Int?,
    val number: String?,
    /** Every name this number goes by on the phone: shop customer, synced contact, address book. */
    val contactNames: List<String> = emptyList()
)

/** One file from the phone's audio library. */
data class AudioCandidate(
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val addedMs: Long,
    val modifiedMs: Long,
    /** 0 when the library does not know it. */
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String = "audio/mp4"
)

object RecordingRules {
    /** The server refuses larger files. */
    const val MAX_BYTES = 50L * 1024L * 1024L

    /** A recording is finished (last written) from shortly before the call ends to this long after. */
    const val FINISHED_BEFORE_END_MS = 15_000L
    const val FINISHED_AFTER_END_MS = 30_000L
    private const val FINISHED_AFTER_END_UNKNOWN_LENGTH_MS = 10_000L

    private const val CLOCK_SLACK_MS = 5_000L

    /** The audio library stores whole seconds. */
    private const val SECOND_MS = 999L

    const val MAX_JOB_AGE_MS = 14L * 86_400_000L
    private const val UNKNOWN_CALL_GIVE_UP_MS = 86_400_000L
    private const val SEARCH_MIN_TRIES = 3
    private const val SEARCH_MIN_AGE_MS = 30L * 60_000L

    /** Folder paths the brands' own call recorders write to, as consecutive folder names. */
    private val FOLDER_PATHS = listOf(
        listOf("recordings", "call"),
        listOf("record", "call"),
        listOf("recorder", "call"),
        listOf("sounds", "callrecord")
    )

    /** Folder names that mean "call recordings" wherever they sit. */
    private val FOLDER_NAMES = setOf(
        "call recordings",
        "call recording",
        "callrecordings",
        "callrecording",
        "call_recordings",
        "callrecord",
        "call_rec"
    )

    /** A Sri Lankan number written in a file name, with or without spaces or hyphens. */
    private val PHONE_IN_NAME = Regex("""(?<!\d)(?:\+?94|0)(?:[ \-]?\d){9}(?!\d)""")

    private val EXTENSION = Regex("""\.[A-Za-z0-9]{2,5}$""")

    /** The date and time recorders add at the end: 261125_143012, 20261125143012, 2026-11-25 14-30-12. */
    private val TRAILING_TIMESTAMP = Regex(
        """[ _\-]*(?:\d{4}[\-_.]\d{2}[\-_.]\d{2}[ _\-]*\d{2}[\-_.:]?\d{2}(?:[\-_.:]?\d{2})?|\d{6,8}[ _\-]?\d{4,6}|\d{10,17})(?:[ _\-]*\(\d+\))?$"""
    )

    private val LEADING_LABEL = Regex("""^(?:call recording|call record|call_rec|call)[ _\-]+""", RegexOption.IGNORE_CASE)

    fun isCallRecordingFolder(relativePath: String): Boolean {
        val folders = relativePath.lowercase().replace('\\', '/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        // Older Samsung phones use a top-level folder simply called "Call".
        if (folders == listOf("call")) return true
        if (folders.any { it in FOLDER_NAMES }) return true
        return FOLDER_PATHS.any { path -> folders.windowed(path.size).any { it == path } }
    }

    /** The only file that fits the call, or null when none or more than one does. */
    fun pick(call: RecordedCall, candidates: List<AudioCandidate>): AudioCandidate? =
        candidates.filter { fits(call, it) }.singleOrNull()

    fun fits(call: RecordedCall, file: AudioCandidate): Boolean =
        file.sizeBytes in 1..MAX_BYTES &&
            isCallRecordingFolder(file.relativePath) &&
            timesFit(call, file) &&
            namesTheCaller(call, file.displayName)

    private fun timesFit(call: RecordedCall, file: AudioCandidate): Boolean {
        if (call.talkSeconds == 0) return false
        // Created during this call, not an older file that was touched again.
        if (file.addedMs + SECOND_MS < call.startedAtMs - CLOCK_SLACK_MS) return false

        val earliestFinish = maxOf(call.startedAtMs, call.endedAtMs - FINISHED_BEFORE_END_MS)
        if (file.modifiedMs + SECOND_MS < earliestFinish) return false

        if (file.durationMs <= 0L) {
            return file.modifiedMs <= call.endedAtMs + FINISHED_AFTER_END_UNKNOWN_LENGTH_MS
        }
        if (file.modifiedMs > call.endedAtMs + FINISHED_AFTER_END_MS) return false
        // Audio that began after this call ended belongs to the next call.
        if (file.modifiedMs - file.durationMs > call.endedAtMs) return false

        val lengthSeconds = file.durationMs / 1000L
        val callSeconds = (call.endedAtMs - call.startedAtMs) / 1000L
        if (lengthSeconds > callSeconds + 10L) return false
        val talk = call.talkSeconds ?: return true
        return lengthSeconds >= talk - maxOf(10, talk / 5)
    }

    /**
     * The file name must carry this call's number or this caller's saved name, and no other number.
     */
    fun namesTheCaller(call: RecordedCall, displayName: String): Boolean {
        val name = displayName.replace(EXTENSION, "")
        val ownDigits = call.number.orEmpty().filter { it.isDigit() }
        val ownTail = ownDigits.takeLast(9).takeIf { it.length == 9 }

        val otherNumber = PHONE_IN_NAME.findAll(name)
            .map { match -> match.value.filter { it.isDigit() }.takeLast(9) }
            .any { it != ownTail }
        if (otherNumber) return false

        val joinedDigits = name.replace(Regex("""(?<=\d)[ \-()]+(?=\d)"""), "")
        if (ownTail != null && joinedDigits.contains(ownTail)) return true

        // The whole name must be equal: "Nimal" is not "Nimal Perera".
        val label = words(name.replace(TRAILING_TIMESTAMP, "").replace(LEADING_LABEL, ""))
        if (label.isEmpty()) return false
        return call.contactNames.any { saved ->
            val wanted = words(saved)
            wanted.length >= 2 && wanted.any { it.isLetter() } && wanted == label
        }
    }

    /** Lower-case words only. Combining marks are kept so Sinhala and Tamil names stay whole. */
    private fun words(text: String): String {
        val out = StringBuilder()
        for (ch in text.lowercase()) {
            val type = Character.getType(ch)
            val keep = Character.isLetterOrDigit(ch) ||
                type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                ch == '‌' || ch == '‍'
            if (keep) out.append(ch) else if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
        }
        return out.toString().trim()
    }

    /** True when searching again is pointless: the library has had time to list the file. */
    fun stopSearching(triesSoFar: Int, ageMs: Long): Boolean =
        triesSoFar >= SEARCH_MIN_TRIES && ageMs >= SEARCH_MIN_AGE_MS

    /** True when a refused upload should be forgotten; false to try again later. */
    fun dropAfterHttpError(code: Int, body: String, ageMs: Long): Boolean = when (code) {
        400, 409, 413, 415, 422 -> true
        403 -> body.contains("call_recording_disabled")
        // The call itself may still be queued on this phone; a call the shop never logs stays unknown.
        404 -> ageMs >= UNKNOWN_CALL_GIVE_UP_MS
        else -> false
    }
}
