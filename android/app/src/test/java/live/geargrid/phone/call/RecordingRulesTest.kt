package live.geargrid.phone.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRulesTest {
    private val start = 1_800_000_000_000L
    private val end = start + 70_000L

    private val call = RecordedCall(
        startedAtMs = start,
        endedAtMs = end,
        talkSeconds = 60,
        number = "+94771234567",
        contactNames = listOf("Nimal Perera")
    )

    private fun file(
        name: String = "Call recording 0771234567_261125_143012.m4a",
        folder: String = "Recordings/Call/",
        added: Long = end + 1_000L,
        modified: Long = end + 1_000L,
        duration: Long = 60_000L,
        size: Long = 500_000L,
        id: Long = 1L
    ) = AudioCandidate(id, name, folder, added, modified, duration, size)

    @Test
    fun picksTheSamsungAndVivoRecordingOfTheCall() {
        assertEquals(1L, RecordingRules.pick(call, listOf(file()))?.id)
        val vivo = file(name = "0771234567_20261125143012.m4a", folder = "Recordings/Record/Call/")
        assertEquals(1L, RecordingRules.pick(call, listOf(vivo))?.id)
        val byName = file(name = "Call recording Nimal Perera_261125_143012.m4a")
        assertEquals(1L, RecordingRules.pick(call, listOf(byName))?.id)
        val vivoByName = file(name = "Nimal Perera 2026-11-25 14-30-12.m4a", folder = "Record/Call/")
        assertEquals(1L, RecordingRules.pick(call, listOf(vivoByName))?.id)
        val spaced = file(name = "+94 77 123 4567 20261125143012.m4a")
        assertEquals(1L, RecordingRules.pick(call, listOf(spaced))?.id)
    }

    @Test
    fun matchesSinhalaContactNames() {
        val sinhala = call.copy(contactNames = listOf("නිමල් පෙරේරා"))
        assertTrue(RecordingRules.namesTheCaller(sinhala, "Call recording නිමල් පෙරේරා_261125_143012.m4a"))
        assertFalse(RecordingRules.namesTheCaller(sinhala, "Call recording නිමල්_261125_143012.m4a"))
    }

    @Test
    fun knowsCallRecordingFolders() {
        listOf(
            "Recordings/Call/", "Call/", "Record/Call/", "Recordings/Record/Call/",
            "Sounds/CallRecord/", "Recordings/Call Recordings/", "MIUI/sound_recorder/call_rec/"
        ).forEach { assertTrue(it, RecordingRules.isCallRecordingFolder(it)) }
        listOf(
            "Recordings/Voice Recorder/", "Music/", "Download/", "DCIM/Call of the wild/",
            "WhatsApp/Media/WhatsApp Voice Notes/", "Music/Call/", ""
        ).forEach { assertFalse(it, RecordingRules.isCallRecordingFolder(it)) }
    }

    @Test
    fun ignoresAudioOutsideACallFolderEvenWithTheNumberInItsName() {
        assertNull(RecordingRules.pick(call, listOf(file(folder = "Download/"))))
    }

    @Test
    fun uploadsNothingWhenTheNameDoesNotIdentifyTheCaller() {
        listOf(
            "recording_261125_143012.m4a",
            "Call recording Kamal_261125_143012.m4a",
            "Call recording Nimal_261125_143012.m4a",
            "Call recording Nimal Perera Hardware_261125_143012.m4a",
            "Call recording 0719999999_261125_143012.m4a"
        ).forEach { assertNull(it, RecordingRules.pick(call, listOf(file(name = it)))) }
    }

    @Test
    fun aDifferentNumberInTheNameOutweighsAMatchingName() {
        assertFalse(RecordingRules.namesTheCaller(call, "Nimal Perera 0719999999_261125143012.m4a"))
    }

    @Test
    fun aHiddenNumberNeverTakesAFileNamedAfterANumber() {
        val hidden = call.copy(number = null, contactNames = emptyList())
        assertNull(RecordingRules.pick(hidden, listOf(file())))
        assertNull(RecordingRules.pick(hidden, listOf(file(name = "recording_261125_143012.m4a"))))
    }

    @Test
    fun uploadsNothingWhenTwoFilesFit() {
        assertNull(RecordingRules.pick(call, listOf(file(id = 1), file(id = 2))))
    }

    @Test
    fun ignoresThePreviousCallToTheSameNumber() {
        // Finished before this call began.
        val previous = file(added = start - 20_000L, modified = start - 20_000L)
        assertNull(RecordingRules.pick(call, listOf(previous)))
        assertEquals(2L, RecordingRules.pick(call, listOf(previous, file(id = 2)))?.id)
    }

    @Test
    fun ignoresTheNextCallToTheSameNumber() {
        // A 10 s call that began 3 s after a 10 s call ended, while the first one left no recording.
        val short = call.copy(endedAtMs = start + 12_000L, talkSeconds = 10)
        val nextStart = short.endedAtMs + 3_000L
        val next = file(added = nextStart + 10_000L, modified = nextStart + 10_000L, duration = 10_000L)
        assertNull(RecordingRules.pick(short, listOf(next)))
    }

    @Test
    fun requiresTheFileToFinishAroundTheEndOfTheCall() {
        assertNull(RecordingRules.pick(call, listOf(file(modified = end + 40_000L, duration = 100_000L))))
        assertNull(RecordingRules.pick(call, listOf(file(modified = end - 20_000L, duration = 50_000L))))
        // An old recording that something touched again just now.
        assertNull(RecordingRules.pick(call, listOf(file(added = start - 3_600_000L))))
    }

    @Test
    fun requiresALengthThatFitsTheCall() {
        assertNull(RecordingRules.pick(call, listOf(file(duration = 20_000L))))
        assertNull(RecordingRules.pick(call, listOf(file(duration = 300_000L))))
        // Some recorders include the ringing.
        assertEquals(1L, RecordingRules.pick(call, listOf(file(duration = 68_000L)))?.id)
    }

    @Test
    fun isStricterWhenTheLengthIsUnknown() {
        assertEquals(1L, RecordingRules.pick(call, listOf(file(duration = 0L)))?.id)
        assertNull(RecordingRules.pick(call, listOf(file(duration = 0L, modified = end + 20_000L))))
    }

    @Test
    fun anUnansweredCallHasNoRecording() {
        assertNull(RecordingRules.pick(call.copy(talkSeconds = 0), listOf(file())))
    }

    @Test
    fun skipsEmptyAndOversizedFiles() {
        assertNull(RecordingRules.pick(call, listOf(file(size = 0L))))
        assertNull(RecordingRules.pick(call, listOf(file(size = RecordingRules.MAX_BYTES + 1))))
    }

    @Test
    fun stopsSearchingOnlyAfterSeveralTriesAndHalfAnHour() {
        assertFalse(RecordingRules.stopSearching(2, 3_600_000L))
        assertFalse(RecordingRules.stopSearching(5, 60_000L))
        assertTrue(RecordingRules.stopSearching(3, 3_600_000L))
    }

    @Test
    fun retriesTemporaryFailuresAndDropsPermanentOnes() {
        val minute = 60_000L
        listOf(400, 409, 413, 415, 422).forEach { assertTrue(RecordingRules.dropAfterHttpError(it, "", minute)) }
        assertTrue(RecordingRules.dropAfterHttpError(403, """{"code":"call_recording_disabled"}""", minute))
        assertFalse(RecordingRules.dropAfterHttpError(403, """{"code":"shop_inactive"}""", minute))
        listOf(429, 500, 502, 503).forEach { assertFalse(RecordingRules.dropAfterHttpError(it, "", minute)) }
        assertFalse(RecordingRules.dropAfterHttpError(404, "", minute))
        assertTrue(RecordingRules.dropAfterHttpError(404, "", 90_000_000L))
    }
}
