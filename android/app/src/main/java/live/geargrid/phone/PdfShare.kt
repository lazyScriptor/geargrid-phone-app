package live.geargrid.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Builds a one-page PDF from receipt HTML and opens Android's share sheet. Printing needs the thermal print
 * agent on the counter machine, which a phone cannot reach.
 */
object PdfShare {
    private const val MAX_HEIGHT_PX = 16000

    fun share(activity: Activity, html: String, filename: String, widthMm: Int, onDone: (shared: Boolean, error: String?) -> Unit) {
        activity.runOnUiThread {
            try {
                WebView.enableSlowWholeDocumentDraw()
                val density = activity.resources.displayMetrics.density
                val widthPx = (360 * density).toInt()
                val webView = WebView(activity)
                webView.settings.javaScriptEnabled = false
                webView.setBackgroundColor(Color.WHITE)
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.postDelayed({ renderAndShare(activity, view, widthPx, density, filename, widthMm, onDone) }, 300)
                    }
                }
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY)
                )
                webView.layout(0, 0, widthPx, 1)
                webView.loadDataWithBaseURL("https://app.geargrid.live/", html, "text/html", "utf-8", null)
            } catch (e: Exception) {
                onDone(false, e.message ?: "failed")
            }
        }
    }

    private fun renderAndShare(
        activity: Activity,
        webView: WebView,
        widthPx: Int,
        density: Float,
        filename: String,
        widthMm: Int,
        onDone: (Boolean, String?) -> Unit
    ) {
        try {
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val heightPx = (webView.contentHeight * density).toInt().coerceAtLeast(webView.measuredHeight)
            if (heightPx <= 0 || heightPx > MAX_HEIGHT_PX) {
                onDone(false, "too_tall")
                return
            }
            webView.layout(0, 0, widthPx, heightPx)
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            webView.draw(canvas)

            // 72 pt per inch; the page is as wide as the receipt paper.
            val pageWidthPt = (widthMm / 25.4f * 72f).toInt().coerceAtLeast(150)
            val scale = pageWidthPt.toFloat() / widthPx
            val pageHeightPt = (heightPx * scale).toInt().coerceAtLeast(100)
            val document = PdfDocument()
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidthPt, pageHeightPt, 1).create())
            page.canvas.scale(scale, scale)
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            document.finishPage(page)

            val dir = File(activity.cacheDir, "shared").apply { mkdirs() }
            val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "receipt.pdf" }
            val file = File(dir, if (safeName.endsWith(".pdf")) safeName else "$safeName.pdf")
            FileOutputStream(file).use { document.writeTo(it) }
            document.close()
            bitmap.recycle()

            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, null))
            onDone(true, null)
        } catch (e: Exception) {
            onDone(false, e.message ?: "failed")
        } finally {
            webView.destroy()
        }
    }
}
