package live.geargrid.phone.call

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import live.geargrid.phone.PhoneStrings
import java.util.Locale

/** A scroll view that never grows past [maxHeightPx], so a tall card scrolls instead of covering the call screen. */
class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}

data class RenderOptions(
    val language: String,
    val textScale: Float,
    val locked: Boolean,
    val lockedMode: String,
    val footerText: String?,
    val footerIsWarning: Boolean,
    val shopName: String?,
    val maxHeightPx: Int,
    val onAction: (String) -> Unit,
    val onDismiss: () -> Unit
)

data class RenderedCard(val view: View, val rule: ActiveRule, val darkMode: Boolean)

/**
 * Draws the call screen card from a [CallDesign] and the shared [CallTokens]. Text colour is always derived
 * from the surface colour, so the card stays readable in light and dark.
 */
object CallScreenRenderer {
    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun build(
        context: Context,
        design: CallDesign,
        tokens: CallTokens,
        data: CallCardData,
        options: RenderOptions
    ): RenderedCard {
        val dark = when (design.themeMode) {
            "dark" -> true
            "light" -> false
            else -> isNight(context)
        }
        val palette = if (dark) tokens.dark else tokens.light
        val size = tokens.size(design.size)
        val rule = CallScreenLogic.evaluateRules(design, data)
        val accentKey = rule.accent ?: if (design.accent == "brand") "brand" else "neutral"
        val accent = Color.parseColor(tokens.accents[accentKey] ?: tokens.accents.getValue("brand"))
        val onAccent = if (accentKey == "brand" || accentKey == "warning") Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC")
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun sp(v: Float) = v * options.textScale
        val textColour = Color.parseColor(palette.text)
        val muted = Color.parseColor(palette.muted)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(palette.surface))
                cornerRadius = (if (design.widthMode == "full") 0 else dp(design.cornerRadiusDp)).toFloat()
                setStroke(dp(2), accent)
            }
            alpha = design.opacity
            if (design.elevation) elevation = dp(10).toFloat()
            clipToOutline = true
        }

        if (rule.banner) {
            card.addView(TextView(context).apply {
                this.text = if (data.blacklisted) PhoneStrings.get(options.language, "blacklisted_banner") else PhoneStrings.get(options.language, "field_status_warning")
                setTextColor(onAccent)
                setBackgroundColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(size.captionSp))
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(tokens.horizontalPaddingDp), dp(6), dp(tokens.horizontalPaddingDp), dp(6))
                contentDescription = this.text
            })
        }

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(tokens.horizontalPaddingDp), dp(tokens.verticalPaddingDp), dp(tokens.horizontalPaddingDp), dp(tokens.verticalPaddingDp))
        }

        // Header: optional shop name and the close button.
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            this.text = if (design.showShopName) (options.shopName ?: "GearGrid") else ""
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(size.captionSp))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(context).apply {
            this.text = "✕"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(14), dp(8), dp(4), dp(8))
            minWidth = dp(48)
            gravity = Gravity.CENTER
            contentDescription = PhoneStrings.get(options.language, "action_dismiss")
            setOnClickListener { options.onDismiss() }
        })
        inner.addView(header)

        // Rows
        val allowedRows = design.rows.filter { row ->
            !options.locked || CallScreenLogic.lockedFieldsAllowed(row.field, options.lockedMode)
        }
        val rowViews = ArrayList<View>()
        for (row in allowedRows) {
            buildRow(context, row, tokens, size, data, options, palette, accent, onAccent, textColour, muted)?.let { rowViews.add(it) }
        }
        if (rowViews.isEmpty()) {
            rowViews.add(TextView(context).apply {
                this.text = if (data.isUnknown) PhoneStrings.get(options.language, "not_in_geargrid") else data.name
                setTextColor(textColour)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(size.titleSp))
                setTypeface(Typeface.DEFAULT_BOLD)
            })
        }

        val collapsedCount = if (design.tapToExpand && rowViews.size > design.collapsedRows) design.collapsedRows else rowViews.size
        val rowsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rowViews.forEachIndexed { index, v ->
            (v.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )).also {
                it.topMargin = if (index == 0) 0 else dp(size.rowGapDp)
                v.layoutParams = it
            }
            rowsBox.addView(v)
            if (index >= collapsedCount) v.visibility = View.GONE
        }
        val hiddenCount = rowViews.size - collapsedCount
        val moreView = TextView(context).apply {
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(size.captionSp))
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, dp(8), 0, dp(4))
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (hiddenCount > 0) View.VISIBLE else View.GONE
            this.text = PhoneStrings.format(options.language, "more_rows", hiddenCount)
            var expanded = false
            setOnClickListener {
                expanded = !expanded
                for (i in collapsedCount until rowViews.size) rowViews[i].visibility = if (expanded) View.VISIBLE else View.GONE
                this.text = if (expanded) PhoneStrings.get(options.language, "less_rows") else PhoneStrings.format(options.language, "more_rows", hiddenCount)
            }
        }
        val scroller = MaxHeightScrollView(context, options.maxHeightPx).apply {
            isVerticalScrollBarEnabled = false
            addView(rowsBox)
        }
        inner.addView(scroller)
        inner.addView(moreView)

        // Buttons
        if (design.actions.isNotEmpty()) {
            val actionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            for (action in design.actions) {
                actionRow.addView(TextView(context).apply {
                    this.text = PhoneStrings.get(options.language, "action_$action")
                    setTextColor(textColour)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(size.captionSp))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    gravity = Gravity.CENTER
                    minHeight = dp(48)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.parseColor(palette.border))
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(6) }
                    setOnClickListener { options.onAction(action) }
                })
            }
            inner.addView(actionRow)
        }

        // Footer: Live / Synced / Offline
        if (!options.footerText.isNullOrBlank()) {
            inner.addView(TextView(context).apply {
                this.text = options.footerText
                setTextColor(
                    if (options.footerIsWarning) Color.parseColor(if (dark) tokens.footerWarningDark else tokens.footerWarning) else muted
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(size.captionSp) * tokens.footerSizeFactor)
                setPadding(0, dp(6), 0, 0)
            })
        }
        card.addView(inner)
        return RenderedCard(card, rule, dark)
    }

    private fun buildRow(
        context: Context,
        row: DesignRow,
        tokens: CallTokens,
        size: SizeTokens,
        data: CallCardData,
        options: RenderOptions,
        palette: Palette,
        accent: Int,
        onAccent: Int,
        textColour: Int,
        muted: Int
    ): View? {
        val density = context.resources.displayMetrics.density
        val style = tokens.styles[row.style] ?: tokens.styles.getValue("body")
        val baseSp = when (style.sizeKey) {
            "titleSp" -> size.titleSp
            "emphasisSp" -> size.emphasisSp
            "bodySp" -> size.bodySp
            else -> size.captionSp
        } * options.textScale
        val lang = options.language

        fun label(): String = if (row.label) PhoneStrings.get(lang, "field_${row.field}") + ": " else ""

        fun textView(value: String, bold: Boolean = style.bold, colour: Int = if (style.muted) muted else textColour): TextView? {
            if (value.isBlank()) return null
            return TextView(context).apply {
                val builder = SpannableStringBuilder()
                val prefix = label()
                if (prefix.isNotEmpty()) {
                    builder.append(prefix)
                    builder.setSpan(ForegroundColorSpan(muted), 0, prefix.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                builder.append(value)
                this.text = builder
                setTextColor(colour)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp)
                setTypeface(if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
            }
        }

        fun money(v: Double): String = "LKR " + String.format(Locale.US, "%,.0f", v)

        // Unknown callers only ever show one line.
        if (data.isUnknown) {
            if (row.field != "name") return null
            val line = if (data.isPhoneContact) data.name else PhoneStrings.get(lang, "not_in_geargrid")
            val v = textView(line, bold = true) ?: return null
            if (data.isPhoneContact) {
                // keep the saved-contact note under the name via a second line
                return LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(v)
                    addView(TextView(context).apply {
                        this.text = PhoneStrings.get(lang, "saved_contact") + (if (data.phoneDisplay.isNotBlank()) " · ${data.phoneDisplay}" else "")
                        setTextColor(muted)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, size.captionSp * options.textScale)
                    })
                }
            }
            return v
        }

        return when (row.field) {
            "name" -> textView(data.name, bold = true)
            "company" -> textView(data.company.orEmpty())
            "rating" -> {
                val r = data.rating ?: return null
                if (row.style == "stars") {
                    val full = Math.round(r).toInt().coerceIn(0, 5)
                    TextView(context).apply {
                        this.text = "★".repeat(full) + "☆".repeat(5 - full)
                        setTextColor(accent)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp)
                        contentDescription = String.format(Locale.US, "%.1f / 5", r)
                    }
                } else textView(String.format(Locale.US, "★ %.1f", r))
            }
            "status_warning" -> if (data.blacklisted) textView(PhoneStrings.get(lang, "blacklisted"), true, Color.parseColor(tokens.accents.getValue("danger"))) else null
            "balance_due" -> when {
                data.balanceDue > 0 -> textView(money(data.balanceDue))
                data.hasDues -> textView(PhoneStrings.get(lang, "has_dues"))
                else -> null
            }
            "deposit_balance" -> if (data.depositBalance > 0) textView(money(data.depositBalance)) else null
            "id_retained" -> if (data.idRetained) textView(PhoneStrings.get(lang, "id_kept")) else null
            "active_items" -> {
                if (data.activeItems.isEmpty()) return null
                val max = row.maxItems.coerceAtLeast(1)
                val lines = data.activeItems.take(max).joinToString("\n") { item ->
                    "• ${item.qty} × ${item.name}" + (item.due?.takeIf { it.isNotBlank() && it != "null" }?.let { " · $it" } ?: "")
                }
                textView(lines, bold = false)
            }
            "overdue" -> if (data.overdueDays > 0) textView(PhoneStrings.format(lang, "overdue_days", data.overdueDays), true, Color.parseColor(tokens.accents.getValue("warning"))) else null
            "invoice_count" -> if (data.invoiceCount > 0) textView(data.invoiceCount.toString()) else null
            "rental_dates" -> textView(data.rentalDates.orEmpty())
            "last_payment" -> textView(data.lastPayment.orEmpty())
            "last_review_comment" -> textView(data.lastReview.orEmpty())
            else -> null
        }?.let { v ->
            if (row.style == "chip" && v is TextView) {
                v.setTextColor(onAccent)
                v.background = GradientDrawable().apply { setColor(accent); cornerRadius = 999f }
                v.setPadding((10 * density).toInt(), (3 * density).toInt(), (10 * density).toInt(), (3 * density).toInt())
                v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            v
        }
    }
}
