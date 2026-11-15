package live.geargrid.phone.call

import live.geargrid.phone.OfflineSearch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CallScreenDesignTest {
    private val tokens = CallTokens.parse(File("src/main/shared-assets/callScreenTokens.json").readText())

    private fun data(
        blacklisted: Boolean = false,
        balance: Double = 0.0,
        overdue: Int = 0,
        rating: Double? = 4.0,
        idRetained: Boolean = false,
        unknown: Boolean = false
    ) = CallCardData(
        isUnknown = unknown, isPhoneContact = false, name = "Nimal", company = null, rating = rating,
        blacklisted = blacklisted, balanceDue = balance, hasDues = false, depositBalance = 0.0, idRetained = idRetained,
        activeItems = emptyList(), overdueDays = overdue, invoiceCount = 1, rentalDates = null, lastPayment = null,
        lastReview = null, phoneDisplay = ""
    )

    @Test
    fun tokensCoverEverySizeAndStyle() {
        for (size in listOf("compact", "standard", "large", "extra_large")) assertNotNull(tokens.sizes[size])
        for (style in listOf("title", "emphasis", "body", "caption", "stars", "list", "chip")) assertNotNull(tokens.styles[style])
        assertEquals(70, tokens.maxCardHeightPercent)
        assertTrue(tokens.size("extra_large").titleSp > tokens.size("compact").titleSp)
    }

    @Test
    fun parserIgnoresUnknownKeysAndClampsValues() {
        val json = JSONObject(
            """{"version":1,"size":"gigantic","text_scale":5,"colour":"red",
               "shape":{"corner_radius":99,"opacity":0.1,"position":"left"},
               "rows":[{"field":"name","style":"title","extra":1},{"field":"x","style":"bogus"}],
               "rules":[{"when":"nonsense"},{"when":"balance_due_over","value":5000,"accent":"warning"}],
               "actions":["dismiss","add_note","open_app","call_back_later"]}"""
        )
        val result = CallDesignParser.parse(json)
        assertFalse(result.unsupported)
        val d = result.design
        assertEquals("standard", d.size)
        assertEquals(2f, d.textScale, 0.001f)
        assertEquals(28, d.cornerRadiusDp)
        assertEquals(0.8f, d.opacity, 0.001f)
        assertEquals("top", d.position)
        assertEquals(1, d.rows.size)
        assertEquals(1, d.rules.size)
        assertEquals(3, d.actions.size)
    }

    @Test
    fun newerVersionFallsBackAndIsReportedUnsupported() {
        val result = CallDesignParser.parse(JSONObject("""{"version":9,"rows":[{"field":"name","style":"title"}]}"""))
        assertTrue(result.unsupported)
        assertEquals(CallDesign().rows, result.design.rows)
    }

    @Test
    fun missingDesignUsesTheBuiltInLook() {
        val result = CallDesignParser.parse(null)
        assertFalse(result.unsupported)
        assertEquals("standard", result.design.size)
    }

    @Test
    fun rulesPickTheStrongestAccentAndCollectBannerAndVibrate() {
        val design = CallDesign(
            rules = listOf(
                DesignRule("balance_due_over", 10000.0, "warning", false, false),
                DesignRule("status_blacklisted", null, "danger", true, true)
            )
        )
        assertEquals(ActiveRule("warning", false, false), CallScreenLogic.evaluateRules(design, data(balance = 20000.0)))
        assertEquals(ActiveRule("danger", true, true), CallScreenLogic.evaluateRules(design, data(blacklisted = true, balance = 20000.0)))
        assertNull(CallScreenLogic.evaluateRules(design, data()).accent)
        val unknown = CallDesign(rules = listOf(DesignRule("unknown_caller", null, "neutral", false, false)))
        assertEquals("neutral", CallScreenLogic.evaluateRules(unknown, data(unknown = true)).accent)
    }

    @Test
    fun textScaleUsesPhoneChoiceWithinShopBoundsThenSystemFont() {
        assertEquals(1.2f, CallScreenLogic.effectiveScale(1.2f, null, true, 0.8f, 2f, 1f, true), 0.001f)
        assertEquals(1.6f, CallScreenLogic.effectiveScale(1.2f, 1.6f, true, 0.8f, 2f, 1f, true), 0.001f)
        assertEquals(1.5f, CallScreenLogic.effectiveScale(1.2f, 1.9f, true, 0.8f, 1.5f, 1f, true), 0.001f)
        assertEquals(1.2f, CallScreenLogic.effectiveScale(1.2f, 1.6f, false, 0.8f, 2f, 1f, true), 0.001f)
        assertEquals(1.56f, CallScreenLogic.effectiveScale(1.2f, null, true, 0.8f, 2f, 1.3f, true), 0.001f)
        assertEquals(1.2f, CallScreenLogic.effectiveScale(1.2f, null, true, 0.8f, 2f, 1.3f, false), 0.001f)
    }

    @Test
    fun cardHeightIsCappedByCollapsingLowerRows() {
        assertEquals(2, CallScreenLogic.rowsThatFit(listOf(100, 100, 100, 100), 250))
        assertEquals(4, CallScreenLogic.rowsThatFit(listOf(100, 100, 100, 100), 1000))
        assertEquals(1, CallScreenLogic.rowsThatFit(listOf(500, 100), 200))
    }

    @Test
    fun lockScreenShowsTheStricterOfDesignAndProfile() {
        assertEquals("name_only", CallScreenLogic.stricterLockMode("name_rating", "name_only"))
        assertEquals("hidden", CallScreenLogic.stricterLockMode("hidden", "full"))
        assertTrue(CallScreenLogic.lockedFieldsAllowed("rating", "name_rating"))
        assertFalse(CallScreenLogic.lockedFieldsAllowed("balance_due", "name_rating"))
        assertFalse(CallScreenLogic.lockedFieldsAllowed("name", "hidden"))
    }

    @Test
    fun overdueDaysAreComputedAtDisplayTime() {
        val today = java.time.LocalDate.of(2026, 11, 16).toEpochDay()
        assertEquals(4, CallScreenLogic.overdueDays("2026-11-12", today))
        assertEquals(0, CallScreenLogic.overdueDays("2026-11-20", today))
        assertEquals(0, CallScreenLogic.overdueDays(null, today))
        assertEquals(0, CallScreenLogic.overdueDays("garbage", today))
    }

    @Test
    fun offlineSearchPhoneRulesMatchTheServer() {
        assertEquals("771234567", OfflineSearch.phoneCore("0771234567"))
        assertEquals("771234567", OfflineSearch.phoneCore("+94 77 123 4567"))
        assertEquals("771234567", OfflineSearch.phoneCore("0094771234567"))
        assertNull(OfflineSearch.phoneCore("07"))
        assertTrue(OfflineSearch.looksLikePhone("077 123 4567"))
        assertFalse(OfflineSearch.looksLikePhone("Nimal 077"))
        assertEquals("0771234567", OfflineSearch.localPhone("+94771234567"))
    }
}
