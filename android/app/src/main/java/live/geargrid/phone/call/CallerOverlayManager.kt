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
import live.geargrid.phone.db.BriefEntity
import live.geargrid.phone.db.ContactIndexEntity
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

object CallerOverlayManager {
    private const val FALLBACK_NOTIFICATION_ID = 9101
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null

    fun showBrief(
        context: Context,
        phoneDisplay: String,
        brief: BriefEntity?,
        contact: ContactIndexEntity?
    ) {
        val appContext = context.applicationContext
        val store = CredentialStore(appContext)
        if (!store.isCallerBriefEnabled()) return

        if (brief == null && contact == null && store.getUnknownCallerMode() == "show_nothing") {
            return
        }

        val isLocked = (appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isKeyguardLocked == true
        val lockMode = store.getLockScreenMode()
        if (isLocked && lockMode == "hidden") return

        val isBlacklisted = brief?.status == "Blacklisted"
        val title = brief?.displayName
            ?: contact?.displayName
            ?: "Not in GearGrid"
        val computedSummary = brief?.let { computeLiveSummaryLine(it) }.orEmpty()
        val rawSubtitle = when {
            brief != null && computedSummary.isNotBlank() -> computedSummary
            contact != null -> "Saved in phone contacts · $phoneDisplay"
            else -> phoneDisplay
        }
        val rawRatingText = brief?.rating?.let { String.format(Locale.US, "★ %.1f", it) }

        val visiblePhone = if (!isLocked || lockMode == "full") phoneDisplay else ""
        val visibleSubtitle = if (!isLocked || lockMode == "full") rawSubtitle else ""
        val visibleRatingText = when {
            !isLocked -> rawRatingText
            lockMode == "full" || lockMode == "name_rating" -> rawRatingText
            else -> null
        }

        val position = store.getOverlayPosition()
        val autoDismissSeconds = store.getOverlayAutoDismissSeconds()

        if (Settings.canDrawOverlays(appContext)) {
            mainHandler.post {
                showWindowOverlay(
                    appContext,
                    title,
                    visiblePhone,
                    visibleSubtitle,
                    visibleRatingText,
                    isBlacklisted,
                    position,
                    autoDismissSeconds
                )
            }
        } else {
            showFallbackNotification(appContext, title, visibleSubtitle, isBlacklisted)
        }
    }

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

    private fun showWindowOverlay(
        context: Context,
        title: String,
        phoneDisplay: String,
        subtitle: String,
        ratingText: String?,
        isBlacklisted: Boolean,
        position: String = "top",
        autoDismissSeconds: Int = 0
    ) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        removeOverlayLocked(context)

        val dp = { v: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val accentColor = if (isBlacklisted) Color.parseColor("#DC2626") else Color.parseColor("#F59E0B")
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(2), accentColor)
            }
            elevation = dp(10).toFloat()
            clipToOutline = true
        }

        if (isBlacklisted) {
            val banner = TextView(context).apply {
                text = "BLACKLISTED CUSTOMER"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#DC2626"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(14), dp(6), dp(14), dp(6))
            }
            root.addView(banner)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badgeView = TextView(context).apply {
            text = "GEARGRID CALLER"
            setTextColor(Color.parseColor("#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(Typeface.DEFAULT_BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(badgeView)

        if (!ratingText.isNullOrBlank()) {
            val ratingView = TextView(context).apply {
                text = ratingText
                setTextColor(Color.parseColor("#FBBF24"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = dp(8).toFloat()
                }
            }
            headerRow.addView(ratingView)
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12), dp(2), dp(4), dp(2))
            setOnClickListener { dismiss(context) }
        }
        headerRow.addView(closeBtn)
        content.addView(headerRow)

        val nameView = TextView(context).apply {
            text = title
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(Typeface.DEFAULT_BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        content.addView(nameView)

        val phoneView = TextView(context).apply {
            text = phoneDisplay
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, dp(6))
            visibility = if (phoneDisplay.isBlank()) View.GONE else View.VISIBLE
        }
        content.addView(phoneView)

        val summaryView = TextView(context).apply {
            text = subtitle
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        }
        content.addView(summaryView)

        root.addView(content)

        val isBottom = position == "bottom"
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (isBottom) {
                setPadding(dp(12), 0, dp(12), dp(28))
            } else {
                setPadding(dp(12), dp(36), dp(12), 0)
            }
            addView(
                root,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
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
            gravity = (if (isBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
        }

        try {
            wm.addView(container, params)
            overlayView = container
            if (autoDismissSeconds > 0) {
                val r = Runnable { dismiss(context) }
                dismissRunnable = r
                mainHandler.postDelayed(r, autoDismissSeconds.toLong() * 1000L)
            }
        } catch (_: Exception) {
            showFallbackNotification(context, title, subtitle, isBlacklisted)
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
