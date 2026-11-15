package live.geargrid.phone.call

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import live.geargrid.phone.CredentialStore
import live.geargrid.phone.GearGridMessagingService
import live.geargrid.phone.MainActivity
import live.geargrid.phone.PhoneStrings
import live.geargrid.phone.db.BriefEntity
import live.geargrid.phone.db.ContactIndexEntity
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.time.Instant
import java.time.Duration

object CallerOverlayManager {
    private const val FALLBACK_NOTIFICATION_ID = 9101
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null

    fun showBrief(
        context: Context,
        phoneDisplay: String,
        brief: BriefEntity?,
        contact: ContactIndexEntity?,
        live: Boolean = false
    ) {
        val appContext = context.applicationContext
        val store = CredentialStore(appContext)
        if (!store.isCallerBriefEnabled()) return

        if (brief == null && contact == null && store.getUnknownCallerMode() == "show_nothing") {
            return
        }

        val isLocked = (appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isKeyguardLocked == true
        val parsed = CallDesignParser.parse(store.getCallDesignJson())
        store.setCallScreenUnsupported(parsed.unsupported)
        // Strictest of the profile's lock-screen mode and the design's own.
        val lockMode = CallScreenLogic.stricterLockMode(store.getLockScreenMode(), parsed.design.lockedMode)
        if (isLocked && lockMode == "hidden") return

        val language = PhoneStrings.resolveLanguage(store.getAppLanguage())
        val data = buildCardData(brief, contact, phoneDisplay)
        val footer = footerFor(appContext, store, brief, live, language)

        val computedSummary = brief?.let { computeLiveSummaryLine(it) }.orEmpty()
        val fallbackTitle = brief?.displayName ?: contact?.displayName ?: PhoneStrings.get(language, "not_in_geargrid")
        val fallbackSubtitle = if (!isLocked || lockMode == "full") {
            when {
                brief != null && computedSummary.isNotBlank() -> computedSummary
                contact != null -> "${PhoneStrings.get(language, "saved_contact")} · $phoneDisplay"
                else -> phoneDisplay
            }
        } else ""

        if (Settings.canDrawOverlays(appContext)) {
            mainHandler.post {
                showCard(appContext, store, data, footer, isLocked, lockMode, language, fallbackTitle, fallbackSubtitle)
            }
        } else {
            showFallbackNotification(appContext, fallbackTitle, fallbackSubtitle, data.blacklisted)
        }
    }

    /** "Sample call" from the phone or the web designer: the real renderer with sample data. */
    fun showSample(context: Context) {
        val appContext = context.applicationContext
        val store = CredentialStore(appContext)
        val language = PhoneStrings.resolveLanguage(store.getAppLanguage())
        val data = CallCardData(
            isUnknown = false, isPhoneContact = false, name = PhoneStrings.get(language, "sample_name"),
            company = "GearGrid Rentals", rating = 4.0, blacklisted = false, balanceDue = 18500.0, hasDues = true,
            depositBalance = 5000.0, idRetained = true,
            activeItems = listOf(ActiveItemLine(2, "Scaffolding set", "2026-11-18"), ActiveItemLine(1, "Plate compactor", null)),
            overdueDays = 2, invoiceCount = 6, rentalDates = "02 Nov – 14 Nov", lastPayment = "LKR 10,000 · 05 Nov · Cash",
            lastReview = null, phoneDisplay = "+94 77 123 4567"
        )
        if (!Settings.canDrawOverlays(appContext)) return
        mainHandler.post {
            showCard(appContext, store, data, FooterInfo(PhoneStrings.get(language, "footer_live"), false), false, "full", language, data.name, "")
        }
    }

    private fun buildCardData(brief: BriefEntity?, contact: ContactIndexEntity?, phoneDisplay: String): CallCardData {
        if (brief == null) {
            return CallCardData(
                isUnknown = true, isPhoneContact = contact != null, name = contact?.displayName ?: "",
                company = null, rating = null, blacklisted = false, balanceDue = 0.0, hasDues = false,
                depositBalance = 0.0, idRetained = false, activeItems = emptyList(), overdueDays = 0,
                invoiceCount = 0, rentalDates = null, lastPayment = null, lastReview = null, phoneDisplay = phoneDisplay
            )
        }
        val today = LocalDate.now(ZoneId.of("Asia/Colombo")).toEpochDay()
        val items = ArrayList<ActiveItemLine>()
        var maxOverdue = 0
        runCatching {
            val arr = JSONArray(brief.activeItemsJson)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val due = o.optString("due", "").takeIf { it.isNotBlank() && it != "null" }
                items.add(ActiveItemLine(o.optInt("qty", 1), o.optString("name", ""), due))
                // Overdue days are worked out now from the due date, so they never go stale on the phone.
                maxOverdue = maxOf(maxOverdue, CallScreenLogic.overdueDays(due, today))
            }
        }
        val lastPayment = runCatching {
            brief.lastPaymentJson?.let { raw ->
                val o = org.json.JSONObject(raw)
                val amount = if (o.has("amount") && !o.isNull("amount")) "LKR " + String.format(Locale.US, "%,.0f", o.optDouble("amount")) + " · " else ""
                amount + listOf(o.optString("date", ""), o.optString("method", "")).filter { it.isNotBlank() && it != "null" }.joinToString(" · ")
            }
        }.getOrNull()
        val dates = listOfNotNull(brief.firstRental, brief.lastRental).joinToString(" – ").ifBlank { null }
        return CallCardData(
            isUnknown = false, isPhoneContact = false, name = brief.displayName, company = brief.companyName,
            rating = brief.rating, blacklisted = brief.status == "Blacklisted", balanceDue = brief.balanceDue,
            hasDues = brief.hasDues, depositBalance = brief.depositBalance, idRetained = brief.idRetained,
            activeItems = items, overdueDays = maxOverdue, invoiceCount = brief.invoiceCount, rentalDates = dates,
            lastPayment = lastPayment, lastReview = brief.lastReviewComment, phoneDisplay = phoneDisplay
        )
    }

    /** Footer: Live (server answered during this call), Synced N min ago, or Offline with the data's date. */
    private fun footerFor(context: Context, store: CredentialStore, brief: BriefEntity?, live: Boolean, language: String): FooterInfo? {
        val mode = store.getSyncFooterMode()
        if (mode == "never") return null
        if (live) return if (mode == "only_when_stale") null else FooterInfo(PhoneStrings.get(language, "footer_live"), false)
        // The phone's last completed sync is when it last confirmed its data with the server.
        val syncedAt = store.getBriefsSyncedAt() ?: brief?.syncedAt ?: return null
        val instant = runCatching { Instant.parse(syncedAt) }.getOrNull() ?: return null
        val ageMinutes = Duration.between(instant, Instant.now()).toMinutes().coerceAtLeast(0)
        val stale = ageMinutes > store.getSyncStaleAfterMinutes()
        return when {
            stale -> {
                val whenText = java.time.format.DateTimeFormatter.ofPattern("HH:mm, d MMM", Locale.ENGLISH)
                    .format(instant.atZone(ZoneId.of("Asia/Colombo")))
                FooterInfo(PhoneStrings.format(language, "footer_offline", whenText), true)
            }
            mode == "only_when_stale" -> null
            else -> FooterInfo(PhoneStrings.format(language, "footer_synced", ageMinutes.toInt()), false)
        }
    }

    data class FooterInfo(val text: String, val warning: Boolean)

    private fun computeLiveSummaryLine(brief: BriefEntity): String {
        val base = brief.summaryLine.trim()
        val rawItems = brief.activeItemsJson.trim()
        if (rawItems.isBlank() || rawItems == "[]") return base

        val today = LocalDate.now()
        var overdueCount = 0
        var maxOverdueDays = 0
        runCatching {
            val arr = JSONArray(rawItems)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val dueStr = obj.optString("due", "").trim()
                if (dueStr.isEmpty() || dueStr == "null") continue
                val dueDate = runCatching { LocalDate.parse(dueStr) }.getOrNull() ?: continue
                val days = ChronoUnit.DAYS.between(dueDate, today).toInt()
                if (days > 0) {
                    overdueCount++
                    if (days > maxOverdueDays) maxOverdueDays = days
                }
            }
        }
        if (overdueCount <= 0) return base

        val overdueSegment = "$overdueCount ${if (overdueCount == 1) "item" else "items"} " +
            "overdue $maxOverdueDays ${if (maxOverdueDays == 1) "day" else "days"}"
        if (base.isBlank()) return overdueSegment

        val segmentPattern = Regex("""^\d+ items? (?:overdue \d+ days?|out)$""")
        val parts = base.split(" · ").toMutableList()
        val idx = parts.indexOfFirst { segmentPattern.matches(it.trim()) }
        if (idx >= 0) {
            parts[idx] = overdueSegment
        } else {
            parts.add(overdueSegment)
        }
        return parts.joinToString(" · ")
    }

    fun dismiss(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            removeOverlayLocked(appContext)
        }
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(FALLBACK_NOTIFICATION_ID)
    }

    /**
     * D1: Show an interactive after-call card allowing operators to pick quick tags,
     * add a short note, and set a follow-up reminder date.
     */
    fun showAfterCallCard(
        context: Context,
        clientCallId: String,
        callerLabel: String,
        customerId: Long? = null,
        isUnknownCaller: Boolean = false,
        onOpenOnDesktop: (() -> Unit)? = null,
        onSaveLead: ((name: String, note: String?) -> Unit)? = null,
        onSubmitNote: (tags: List<String>, note: String?, followUpOn: String?) -> Unit
    ) {
        val appContext = context.applicationContext
        val store = CredentialStore(appContext)
        if (!store.isAfterCallCardEnabled()) return
        if (!Settings.canDrawOverlays(appContext)) return

        val availableTags = store.getAfterCallTags()
        val autoDismissSeconds = store.getAfterCallDismissSeconds()
        val position = store.getOverlayPosition()

        mainHandler.post {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            removeOverlayLocked(appContext)

            val dp = { v: Int ->
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    v.toFloat(),
                    appContext.resources.displayMetrics
                ).toInt()
            }

            val selectedTags = linkedSetOf<String>()
            var selectedFollowUpOn: String? = null

            val root = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0F172A"))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(2), Color.parseColor("#38BDF8"))
                }
                elevation = dp(10).toFloat()
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }

            val header = TextView(appContext).apply {
                text = "CALL ENDED · $callerLabel"
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(Typeface.DEFAULT_BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            root.addView(header)

            val tagsRow = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(4))
            }
            for (tag in availableTags) {
                val chip = Button(appContext).apply {
                    text = tag
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Color.parseColor("#E2E8F0"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B"))
                        cornerRadius = dp(16).toFloat()
                        setStroke(dp(1), Color.parseColor("#334155"))
                    }
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    setOnClickListener {
                        if (selectedTags.contains(tag)) {
                            selectedTags.remove(tag)
                            setTextColor(Color.parseColor("#E2E8F0"))
                            background = GradientDrawable().apply {
                                setColor(Color.parseColor("#1E293B"))
                                cornerRadius = dp(16).toFloat()
                                setStroke(dp(1), Color.parseColor("#334155"))
                            }
                        } else {
                            selectedTags.add(tag)
                            setTextColor(Color.parseColor("#0F172A"))
                            background = GradientDrawable().apply {
                                setColor(Color.parseColor("#38BDF8"))
                                cornerRadius = dp(16).toFloat()
                            }
                        }
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(34)
                ).apply { rightMargin = dp(6) }
                tagsRow.addView(chip, lp)
            }
            root.addView(HorizontalScrollView(appContext).apply { addView(tagsRow) })

            val noteInput = EditText(appContext).apply {
                hint = "Quick note (optional)..."
                setHintTextColor(Color.parseColor("#94A3B8"))
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.parseColor("#334155"))
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                maxLines = 2
            }
            val noteLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            root.addView(noteInput, noteLp)

            val todayColombo = LocalDate.now(ZoneId.of("Asia/Colombo"))
            val followUpOptions = listOf(
                "No follow-up" to null,
                "Tomorrow" to todayColombo.plusDays(1).toString(),
                "+3 days" to todayColombo.plusDays(3).toString(),
                "+1 week" to todayColombo.plusDays(7).toString()
            )
            val followButtons = mutableListOf<Button>()
            val followRow = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, dp(8))
            }
            followUpOptions.forEachIndexed { idx, (label, dateIso) ->
                val btn = Button(appContext).apply {
                    text = label
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    val isDefault = idx == 0
                    setTextColor(if (isDefault) Color.parseColor("#F59E0B") else Color.parseColor("#CBD5E1"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B"))
                        cornerRadius = dp(12).toFloat()
                    }
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    setOnClickListener {
                        selectedFollowUpOn = dateIso
                        followButtons.forEachIndexed { bIdx, b ->
                            b.setTextColor(
                                if (bIdx == idx) Color.parseColor("#F59E0B")
                                else Color.parseColor("#CBD5E1")
                            )
                        }
                    }
                }
                followButtons.add(btn)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(30)
                ).apply { rightMargin = dp(6) }
                followRow.addView(btn, lp)
            }
            root.addView(HorizontalScrollView(appContext).apply { addView(followRow) })

            val actionsRow = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            if (customerId != null && onOpenOnDesktop != null) {
                val desktopBtn = Button(appContext).apply {
                    text = "Open on desktop"
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Color.parseColor("#38BDF8"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B"))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), Color.parseColor("#38BDF8"))
                    }
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    setOnClickListener {
                        removeOverlayLocked(appContext)
                        onOpenOnDesktop()
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                actionsRow.addView(desktopBtn, lp)
            }
            var leadNameInput: EditText? = null
            if (isUnknownCaller && onSaveLead != null) {
                val leadInput = EditText(appContext).apply {
                    hint = "Lead name (save unknown caller)..."
                    setHintTextColor(Color.parseColor("#94A3B8"))
                    setTextColor(Color.parseColor("#F8FAFC"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B"))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), Color.parseColor("#475569"))
                    }
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    maxLines = 1
                }
                leadNameInput = leadInput
                val leadLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                root.addView(leadInput, leadLp)

                val leadBtn = Button(appContext).apply {
                    text = "Save as lead"
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Color.parseColor("#FDE68A"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B"))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), Color.parseColor("#F59E0B"))
                    }
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    setOnClickListener {
                        val name = leadNameInput?.text?.toString()?.trim().orEmpty()
                        if (name.isEmpty()) {
                            leadNameInput?.error = "Enter lead name"
                            return@setOnClickListener
                        }
                        val noteText = noteInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        removeOverlayLocked(appContext)
                        onSaveLead(name, noteText)
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                actionsRow.addView(leadBtn, lp)
            }
            val dismissBtn = Button(appContext).apply {
                text = "Dismiss"
                isAllCaps = false
                setTextColor(Color.parseColor("#94A3B8"))
                background = null
                setOnClickListener { removeOverlayLocked(appContext) }
            }
            val saveBtn = Button(appContext).apply {
                text = "Save note"
                isAllCaps = false
                setTextColor(Color.parseColor("#0F172A"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#22C55E"))
                    cornerRadius = dp(8).toFloat()
                }
                setPadding(dp(14), dp(4), dp(14), dp(4))
                setOnClickListener {
                    val tagsCopy = selectedTags.toList()
                    val noteText = noteInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    val followIso = selectedFollowUpOn
                    removeOverlayLocked(appContext)
                    onSubmitNote(tagsCopy, noteText, followIso)
                }
            }
            actionsRow.addView(dismissBtn)
            actionsRow.addView(saveBtn)
            root.addView(actionsRow)

            val isBottom = position == "bottom"
            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                if (isBottom) setPadding(dp(12), 0, dp(12), dp(28))
                else setPadding(dp(12), dp(36), dp(12), 0)
                addView(
                    root,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = (if (isBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
            }

            try {
                wm.addView(container, params)
                overlayView = container
                if (autoDismissSeconds > 0) {
                    val r = Runnable { dismiss(appContext) }
                    dismissRunnable = r
                    mainHandler.postDelayed(r, autoDismissSeconds.toLong() * 1000L)
                }
            } catch (_: Exception) {
                // Ignore if overlay permission disappeared concurrently
            }
        }
    }

    private var cachedTokens: CallTokens? = null

    private fun loadTokens(context: Context): CallTokens =
        cachedTokens ?: context.assets.open("callScreenTokens.json").bufferedReader().use { CallTokens.parse(it.readText()) }
            .also { cachedTokens = it }

    private fun showCard(
        context: Context,
        store: CredentialStore,
        data: CallCardData,
        footer: FooterInfo?,
        locked: Boolean,
        lockMode: String,
        language: String,
        fallbackTitle: String,
        fallbackSubtitle: String,
        warn: Boolean = false
    ) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        removeOverlayLocked(context)

        val tokens = runCatching { loadTokens(context) }.getOrNull()
        if (tokens == null) {
            showFallbackNotification(context, fallbackTitle, fallbackSubtitle, data.blacklisted)
            return
        }
        val parsed = CallDesignParser.parse(store.getCallDesignJson())
        val design = parsed.design
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        fun dp(v: Int) = (v * density).toInt()

        // Phone choice (within the shop's bounds) over the design's scale. SP text already follows the system
        // font, so when the shop does not respect it the scale is divided back out.
        val fontScale = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
        val chosen = CallScreenLogic.effectiveScale(
            design.textScale, store.getPhoneCallTextScale(), store.getAllowPhoneTextScale(),
            store.getCallScaleMin(), store.getCallScaleMax(), 1f, false
        )
        val textScale = if (store.getRespectSystemFont()) chosen else chosen / fontScale
        val maxHeightPx = (metrics.heightPixels * tokens.maxCardHeightPercent / 100) - dp(150)

        val rendered = CallScreenRenderer.build(
            context, design, tokens, data,
            RenderOptions(
                language = language, textScale = textScale, locked = locked, lockedMode = lockMode,
                footerText = footer?.text, footerIsWarning = footer?.warning == true || warn,
                shopName = store.getShopName(), maxHeightPx = maxHeightPx.coerceAtLeast(dp(120)),
                onAction = { action -> handleAction(context, action) },
                onDismiss = { dismiss(context) }
            )
        )

        val margin = if (design.widthMode == "inset") dp(tokens.insetMarginDp) else 0
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, if (design.position == "top") dp(36) else 0, margin, if (design.position == "bottom") dp(28) else 0)
            addView(rendered.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (design.position) {
                "bottom" -> Gravity.BOTTOM
                "middle" -> Gravity.CENTER_VERTICAL
                else -> Gravity.TOP
            } or Gravity.CENTER_HORIZONTAL
        }

        if (design.draggable) attachDrag(rendered.view, container, params, wm)

        try {
            wm.addView(container, params)
            overlayView = container
            if (rendered.rule.vibrate) vibrateOnce(context)
            val seconds = if (design.autoDismissSeconds > 0) design.autoDismissSeconds else store.getOverlayAutoDismissSeconds()
            if (seconds > 0) {
                val r = Runnable { dismiss(context) }
                dismissRunnable = r
                mainHandler.postDelayed(r, seconds.toLong() * 1000L)
            }
        } catch (_: Exception) {
            showFallbackNotification(context, fallbackTitle, fallbackSubtitle, data.blacklisted)
        }
    }

    private fun attachDrag(handle: View, container: View, params: WindowManager.LayoutParams, wm: WindowManager) {
        var startY = 0f
        var startParamY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startParamY = params.y
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawY - startY).toInt()
                    if (kotlin.math.abs(delta) > 12) {
                        params.y = startParamY + if ((params.gravity and Gravity.BOTTOM) == Gravity.BOTTOM) -delta else delta
                        runCatching { wm.updateViewLayout(container, params) }
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun vibrateOnce(context: Context) {
        runCatching {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(350, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun handleAction(context: Context, action: String) {
        when (action) {
            "dismiss" -> dismiss(context)
            "add_note" -> {
                dismiss(context)
                CallStateTracker.openAfterCallCardForActiveCall(context)
            }
            "open_on_desktop" -> {
                dismiss(context)
                CallStateTracker.openActiveCustomerOnDesktop(context)
            }
            "open_app" -> {
                dismiss(context)
                context.startActivity(Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
            "call_back_later" -> {
                dismiss(context)
                CallStateTracker.openAfterCallCardForActiveCall(context)
            }
        }
    }

    private fun removeOverlayLocked(context: Context) {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        val current = overlayView ?: return
        overlayView = null
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        try {
            wm.removeView(current)
        } catch (_: Exception) {
            // Already detached
        }
    }

    private fun showFallbackNotification(
        context: Context,
        title: String,
        subtitle: String,
        isBlacklisted: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        GearGridMessagingService.ensureNotificationChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            FALLBACK_NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smallIcon = context.applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.sym_call_incoming

        val prefixedTitle = if (isBlacklisted) "BLACKLISTED · $title" else title
        val notification = NotificationCompat.Builder(context, GearGridMessagingService.CHANNEL_CALLS)
            .setSmallIcon(smallIcon)
            .setContentTitle(prefixedTitle)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(FALLBACK_NOTIFICATION_ID, notification)
    }
}
