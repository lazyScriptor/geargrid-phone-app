package live.geargrid.phone.call

import android.Manifest
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import live.geargrid.phone.GearGridMessagingService
import live.geargrid.phone.MainActivity
import live.geargrid.phone.db.BriefEntity
import live.geargrid.phone.db.ContactIndexEntity
import java.util.Locale

object CallerOverlayManager {
    private const val FALLBACK_NOTIFICATION_ID = 9101
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null

    fun showBrief(
        context: Context,
        phoneDisplay: String,
        brief: BriefEntity?,
        contact: ContactIndexEntity?
    ) {
        val appContext = context.applicationContext
        val isBlacklisted = brief?.status == "Blacklisted"
        val title = brief?.displayName
            ?: contact?.displayName
            ?: "Not in GearGrid"
        val subtitle = when {
            brief != null && brief.summaryLine.isNotBlank() -> brief.summaryLine
            contact != null -> "Saved in phone contacts · $phoneDisplay"
            else -> phoneDisplay
        }
        val ratingText = brief?.rating?.let { String.format(Locale.US, "★ %.1f", it) }

        if (Settings.canDrawOverlays(appContext)) {
            mainHandler.post {
                showWindowOverlay(appContext, title, phoneDisplay, subtitle, ratingText, isBlacklisted)
            }
        } else {
            showFallbackNotification(appContext, title, subtitle, isBlacklisted)
        }
    }

    fun dismiss(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            removeOverlayLocked(appContext)
        }
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(FALLBACK_NOTIFICATION_ID)
    }

    private fun showWindowOverlay(
        context: Context,
        title: String,
        phoneDisplay: String,
        subtitle: String,
        ratingText: String?,
        isBlacklisted: Boolean
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
        }
        content.addView(phoneView)

        val summaryView = TextView(context).apply {
            text = subtitle
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        content.addView(summaryView)

        root.addView(content)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(36), dp(12), 0)
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
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

        try {
            wm.addView(container, params)
            overlayView = container
        } catch (_: Exception) {
            showFallbackNotification(context, title, subtitle, isBlacklisted)
        }
    }

    private fun removeOverlayLocked(context: Context) {
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
