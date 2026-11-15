package live.geargrid.phone.call

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * The call screen design (JSON from the shop's brief profile) and the shared layout tokens
 * (callScreenTokens.json, the same file the web designer preview uses). Pure Kotlin + org.json so the rules
 * can be unit tested without a device.
 */
const val CALL_DESIGN_SUPPORTED_VERSION = 1

data class DesignRow(val field: String, val style: String, val label: Boolean, val maxItems: Int)

data class DesignRule(
    val whenKey: String,
    val value: Double?,
    val accent: String?,
    val banner: Boolean,
    val vibrate: Boolean
)

data class CallDesign(
    val version: Int = 1,
    val size: String = "standard",
    val textScale: Float = 1f,
    val widthMode: String = "inset",
    val cornerRadiusDp: Int = 16,
    val position: String = "top",
    val opacity: Float = 1f,
    val elevation: Boolean = true,
    val themeMode: String = "auto",
    val accent: String = "brand",
    val statusBar: Boolean = true,
    val showLogo: Boolean = false,
    val showShopName: Boolean = false,
    val rows: List<DesignRow> = DEFAULT_ROWS,
    val rules: List<DesignRule> = emptyList(),
    val actions: List<String> = listOf("dismiss", "add_note", "open_on_desktop"),
    val autoDismissSeconds: Int = 0,
    val draggable: Boolean = true,
    val tapToExpand: Boolean = true,
    val showFor: String = "incoming",
    val collapsedRows: Int = 2,
    val lockedMode: String = "name_rating"
) {
    companion object {
        val DEFAULT_ROWS = listOf(
            DesignRow("name", "title", false, 3),
            DesignRow("rating", "stars", false, 3),
            DesignRow("balance_due", "emphasis", true, 3),
            DesignRow("active_items", "list", false, 3)
        )
    }
}

data class SizeTokens(val titleSp: Float, val emphasisSp: Float, val bodySp: Float, val captionSp: Float, val rowGapDp: Int)

data class StyleToken(val sizeKey: String, val bold: Boolean, val muted: Boolean)

data class Palette(val surface: String, val text: String, val muted: String, val border: String)

data class CallTokens(
    val sizes: Map<String, SizeTokens>,
    val styles: Map<String, StyleToken>,
    val horizontalPaddingDp: Int,
    val verticalPaddingDp: Int,
    val insetMarginDp: Int,
    val maxCardHeightPercent: Int,
    val defaultCollapsedRows: Int,
    val footerSizeFactor: Float,
    val minTextScale: Float,
    val maxTextScale: Float,
    val light: Palette,
    val dark: Palette,
    val accents: Map<String, String>,
    val footerWarning: String,
    val footerWarningDark: String
) {
    fun size(name: String): SizeTokens = sizes[name] ?: sizes.getValue("standard")

    companion object {
        fun parse(raw: String): CallTokens {
            val root = JSONObject(raw)
            val sizes = HashMap<String, SizeTokens>()
            val sizeObj = root.getJSONObject("sizes")
            for (key in sizeObj.keys()) {
                val o = sizeObj.getJSONObject(key)
                sizes[key] = SizeTokens(
                    o.getDouble("titleSp").toFloat(), o.getDouble("emphasisSp").toFloat(),
                    o.getDouble("bodySp").toFloat(), o.getDouble("captionSp").toFloat(), o.getInt("rowGapDp")
                )
            }
            val styles = HashMap<String, StyleToken>()
            val styleObj = root.getJSONObject("styles")
            for (key in styleObj.keys()) {
                val o = styleObj.getJSONObject(key)
                styles[key] = StyleToken(o.getString("size"), o.optBoolean("bold", false), o.optBoolean("muted", false))
            }
            val layout = root.getJSONObject("layout")
            fun palette(name: String): Palette {
                val p = root.getJSONObject("palettes").getJSONObject(name)
                return Palette(p.getString("surface"), p.getString("text"), p.getString("muted"), p.getString("border"))
            }
            val accentObj = root.getJSONObject("accents")
            val accents = HashMap<String, String>()
            for (key in accentObj.keys()) accents[key] = accentObj.getString(key)
            val footer = root.optJSONObject("footer")
            return CallTokens(
                sizes, styles,
                layout.getInt("horizontalPaddingDp"), layout.getInt("verticalPaddingDp"), layout.getInt("insetMarginDp"),
                layout.getInt("maxCardHeightPercent"), layout.getInt("defaultCollapsedRows"),
                layout.optDouble("footerSizeFactor", 0.85).toFloat(),
                layout.optDouble("minTextScale", 0.8).toFloat(), layout.optDouble("maxTextScale", 2.0).toFloat(),
                palette("light"), palette("dark"), accents,
                footer?.optString("warningText", "#B45309") ?: "#B45309",
                footer?.optString("warningTextDark", "#FBBF24") ?: "#FBBF24"
            )
        }
    }
}

object CallDesignParser {
    private val SIZES = setOf("compact", "standard", "large", "extra_large")
    private val STYLES = setOf("title", "emphasis", "body", "caption", "stars", "list", "chip")
    private val RULES = setOf(
        "status_blacklisted", "balance_due_over", "overdue_days_over", "rating_under", "id_retained", "unknown_caller"
    )
    private val ACTIONS = setOf("dismiss", "add_note", "open_on_desktop", "open_app", "call_back_later")
    private val LOCK_MODES = setOf("full", "name_rating", "name_only", "hidden")

    /** Result of reading a design. `unsupported` tells the console to say "update the app". */
    data class Result(val design: CallDesign, val unsupported: Boolean)

    /**
     * Unknown keys are ignored. A newer `version` than this app understands falls back to the built-in
     * design and is reported as unsupported.
     */
    fun parse(json: JSONObject?): Result {
        if (json == null) return Result(CallDesign(), false)
        val version = json.optInt("version", 1)
        if (version > CALL_DESIGN_SUPPORTED_VERSION) return Result(CallDesign(), true)

        val rows = ArrayList<DesignRow>()
        val rowsJson = json.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until rowsJson.length()) {
            val r = rowsJson.optJSONObject(i) ?: continue
            val field = r.optString("field", "")
            val style = r.optString("style", "body")
            if (field.isBlank() || style !in STYLES) continue
            rows.add(DesignRow(field, style, r.optBoolean("label", false), r.optInt("max_items", 3).coerceIn(1, 10)))
            if (rows.size >= 16) break
        }
        val rules = ArrayList<DesignRule>()
        val rulesJson = json.optJSONArray("rules") ?: JSONArray()
        for (i in 0 until rulesJson.length()) {
            val r = rulesJson.optJSONObject(i) ?: continue
            val whenKey = r.optString("when", "")
            if (whenKey !in RULES) continue
            rules.add(
                DesignRule(
                    whenKey,
                    if (r.has("value") && !r.isNull("value")) r.optDouble("value") else null,
                    r.optString("accent", "").takeIf { it in setOf("danger", "warning", "brand", "neutral") },
                    r.optBoolean("banner", false),
                    r.optBoolean("vibrate", false)
                )
            )
            if (rules.size >= 12) break
        }
        val actions = ArrayList<String>()
        val actionsJson = json.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until actionsJson.length()) {
            val a = actionsJson.optString(i, "")
            if (a in ACTIONS && a !in actions) actions.add(a)
            if (actions.size >= 3) break
        }
        val shape = json.optJSONObject("shape")
        val theme = json.optJSONObject("theme")
        val header = json.optJSONObject("header")
        val behaviour = json.optJSONObject("behaviour")
        val design = CallDesign(
            version = version,
            size = json.optString("size", "standard").takeIf { it in SIZES } ?: "standard",
            textScale = json.optDouble("text_scale", 1.0).toFloat().coerceIn(0.8f, 2f),
            widthMode = shape?.optString("width", "inset")?.takeIf { it == "inset" || it == "full" } ?: "inset",
            cornerRadiusDp = (shape?.optInt("corner_radius", 16) ?: 16).coerceIn(0, 28),
            position = shape?.optString("position", "top")?.takeIf { it in setOf("top", "middle", "bottom") } ?: "top",
            opacity = (shape?.optDouble("opacity", 1.0) ?: 1.0).toFloat().coerceIn(0.8f, 1f),
            elevation = shape?.optBoolean("elevation", true) ?: true,
            themeMode = theme?.optString("mode", "auto")?.takeIf { it in setOf("auto", "light", "dark") } ?: "auto",
            accent = if (theme?.optString("accent") == "neutral") "neutral" else "brand",
            statusBar = theme?.optBoolean("status_bar", true) ?: true,
            showLogo = header?.optBoolean("show_logo", false) ?: false,
            showShopName = header?.optBoolean("show_shop_name", false) ?: false,
            rows = if (rows.isEmpty()) CallDesign.DEFAULT_ROWS else rows,
            rules = rules,
            actions = if (actionsJson.length() == 0) listOf("dismiss") else actions,
            autoDismissSeconds = (behaviour?.optInt("auto_dismiss_seconds", 0) ?: 0).coerceIn(0, 60),
            draggable = behaviour?.optBoolean("draggable", true) ?: true,
            tapToExpand = behaviour?.optBoolean("tap_to_expand", true) ?: true,
            showFor = behaviour?.optString("show_for", "incoming")?.takeIf { it == "incoming_and_outgoing" } ?: "incoming",
            collapsedRows = (behaviour?.optInt("collapsed_rows", 2) ?: 2).coerceIn(1, 10),
            lockedMode = json.optJSONObject("locked")?.optString("mode", "name_rating")?.takeIf { it in LOCK_MODES } ?: "name_rating"
        )
        return Result(design, false)
    }
}

/** What the card may show for one call, already limited by the brief profile. */
data class CallCardData(
    val isUnknown: Boolean,
    val isPhoneContact: Boolean,
    val name: String,
    val company: String?,
    val rating: Double?,
    val blacklisted: Boolean,
    val balanceDue: Double,
    val hasDues: Boolean,
    val depositBalance: Double,
    val idRetained: Boolean,
    val activeItems: List<ActiveItemLine>,
    val overdueDays: Int,
    val invoiceCount: Int,
    val rentalDates: String?,
    val lastPayment: String?,
    val lastReview: String?,
    val phoneDisplay: String
)

data class ActiveItemLine(val qty: Int, val name: String, val due: String?)

data class ActiveRule(val accent: String?, val banner: Boolean, val vibrate: Boolean)

object CallScreenLogic {
    private val ACCENT_RANK = mapOf("neutral" to 0, "brand" to 1, "warning" to 2, "danger" to 3)

    /**
     * Effective text scale = design size × (phone choice, else design scale), within the shop's bounds; the
     * Android system font scale is applied on top only when the shop respects it.
     */
    fun effectiveScale(
        designScale: Float,
        phoneScale: Float?,
        allowPhoneScale: Boolean,
        min: Float,
        max: Float,
        systemFontScale: Float,
        respectSystemFont: Boolean
    ): Float {
        var scale = designScale
        if (allowPhoneScale && phoneScale != null && phoneScale > 0f) scale = phoneScale.coerceIn(min, max)
        if (respectSystemFont) scale *= systemFontScale
        return Math.round(scale * 100f) / 100f
    }

    fun evaluateRules(design: CallDesign, data: CallCardData): ActiveRule {
        var accent: String? = null
        var banner = false
        var vibrate = false
        for (rule in design.rules) {
            val hit = when (rule.whenKey) {
                "status_blacklisted" -> data.blacklisted
                "balance_due_over" -> data.balanceDue > (rule.value ?: 0.0)
                "overdue_days_over" -> data.overdueDays > (rule.value ?: 0.0)
                "rating_under" -> data.rating != null && data.rating < (rule.value ?: 0.0)
                "id_retained" -> data.idRetained
                "unknown_caller" -> data.isUnknown
                else -> false
            }
            if (!hit) continue
            val r = rule.accent
            if (r != null && (accent == null || (ACCENT_RANK[r] ?: 0) > (ACCENT_RANK[accent] ?: 0))) accent = r
            if (rule.banner) banner = true
            if (rule.vibrate) vibrate = true
        }
        return ActiveRule(accent, banner, vibrate)
    }

    /** Fields visible while the phone is locked: the stricter of the design and the profile's lock-screen mode. */
    fun lockedFieldsAllowed(field: String, mode: String): Boolean = when (mode) {
        "full" -> true
        "name_rating" -> field in setOf("name", "company", "rating")
        "name_only" -> field in setOf("name", "company")
        else -> false
    }

    fun stricterLockMode(a: String, b: String): String {
        val order = listOf("hidden", "name_only", "name_rating", "full")
        return order[min(order.indexOf(a).let { if (it < 0) 1 else it }, order.indexOf(b).let { if (it < 0) 1 else it })]
    }

    /** How many of the rows (heights in px, in order) fit under the cap; at least one row is always shown. */
    fun rowsThatFit(rowHeightsPx: List<Int>, budgetPx: Int): Int {
        var used = 0
        var fit = 0
        for (h in rowHeightsPx) {
            used += h
            if (used > budgetPx) break
            fit += 1
        }
        return max(1, fit).coerceAtMost(max(1, rowHeightsPx.size))
    }

    /** Overdue days from a due date, computed at display time so they never go stale on the phone. */
    fun overdueDays(due: String?, todayEpochDay: Long): Int {
        if (due.isNullOrBlank() || due == "null") return 0
        val parts = due.take(10).split("-")
        if (parts.size != 3) return 0
        val y = parts[0].toIntOrNull() ?: return 0
        val m = parts[1].toIntOrNull() ?: return 0
        val d = parts[2].toIntOrNull() ?: return 0
        val epoch = runCatching { java.time.LocalDate.of(y, m, d).toEpochDay() }.getOrNull() ?: return 0
        return max(0, (todayEpochDay - epoch).toInt())
    }
}
