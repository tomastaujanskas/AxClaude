package com.taujanskas.axclaude

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches the supported browsers (listed in res/xml/accessibility_config.xml) and keeps a
 * snapshot of the current address and the images visible on screen. Nothing is stored on
 * disk or sent anywhere; the Ax Claude popup reads the snapshot when you tap Ax Claude.
 */
class ScreenReaderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private val captureRunnable = Runnable {
        pending = false
        try {
            captureNow()
        } catch (e: Exception) {
            // Never let a bad node tree crash the service.
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        // Coalesce bursts of events into one capture.
        if (!pending) {
            pending = true
            handler.postDelayed(captureRunnable, 300)
        }
    }

    override fun onInterrupt() {}

    private data class Item(val node: AccessibilityNodeInfo, val inWeb: Boolean)

    private fun captureNow() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName) return

        val screen = Rect()
        root.getBoundsInScreen(screen)
        if (screen.isEmpty) return

        val density = resources.displayMetrics.density
        val minImagePx = (64 * density).toInt()
        val toolbarZone = (screen.height() * 0.18f).toInt()

        // Best URL found so far, with a score (higher = more trustworthy).
        var bestUrl: String? = null
        var bestScore = 0
        var bestSource = ""
        fun offer(url: String, score: Int, source: String) {
            if (score > bestScore) {
                bestUrl = url; bestScore = score; bestSource = source
            }
        }

        val webImages = mutableListOf<VisibleImage>()
        val otherImages = mutableListOf<VisibleImage>()
        var sawWebContent = false
        val debug = StringBuilder()
        var debugLines = 0

        val stack = ArrayDeque<Item>()
        stack.addLast(Item(root, false))
        var visited = 0

        while (stack.isNotEmpty() && visited < 6000) {
            val (node, parentInWeb) = stack.removeLast()
            visited++

            val cls = node.className?.toString() ?: ""
            val isWebRoot = cls.contains("WebView") || cls.contains("GeckoView")
            val inWeb = parentInWeb || isWebRoot
            if (isWebRoot) sawWebContent = true

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val inToolbar = bounds.centerY() < screen.top + toolbarZone ||
                bounds.centerY() > screen.bottom - toolbarZone

            val id = node.viewIdResourceName ?: ""
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()

            // --- Address bar (browser UI only, never page content) ---
            if (!inWeb) {
                for ((value, kind) in listOf(text to "text", desc to "desc")) {
                    if (value.isEmpty()) continue
                    val found = findUrl(value) ?: continue
                    val isFullMatch = found.length >= value.length - 2
                    when {
                        isAddressBarId(id) -> offer(found, 100, "id:$id/$kind")
                        cls.contains("EditText") && inToolbar -> offer(found, 80, "edittext/$kind")
                        inToolbar && isFullMatch && kind == "text" -> offer(found, 60, "toolbar-text")
                        inToolbar && isFullMatch -> offer(found, 50, "toolbar-desc")
                        inToolbar -> offer(found, 30, "toolbar-partial/$kind")
                        isFullMatch -> offer(found, 20, "ui-text/$kind")
                    }
                }
                if ((text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty()) && debugLines < 60) {
                    debugLines++
                    debug.append(cls.substringAfterLast('.')).append(" id=")
                        .append(id.substringAfterLast('/')).append(" t=\"")
                        .append(text.take(60)).append("\" d=\"").append(desc.take(60))
                        .append("\" y=").append(bounds.centerY()).append('\n')
                }
            }

            // --- Images visible on screen ---
            val isWebImage = cls == "android.widget.Image"
            val isBigImage = cls.contains("Image") &&
                bounds.width() >= minImagePx && bounds.height() >= minImagePx && !inToolbar
            if ((isWebImage || isBigImage) && node.isVisibleToUser && Rect.intersects(bounds, screen)) {
                val label = desc.ifEmpty { text }
                val target = if (inWeb) webImages else otherImages
                if (target.none { it.bounds == bounds }) target.add(VisibleImage(label, Rect(bounds)))
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(Item(it, inWeb)) }
            }
        }

        val images = if (sawWebContent) webImages else otherImages
        val url = bestUrl?.let { normaliseUrl(it) }
        // Keep the previous URL if this pass didn't find one (e.g. toolbar hidden while scrolling).
        val previous = SnapshotStore.latest?.takeIf { it.packageName == pkg }
        val keptUrl = url ?: previous?.url
        val keptSource = if (url != null) bestSource else previous?.urlSource ?: "none"

        SnapshotStore.latest = PageSnapshot(
            packageName = pkg,
            url = keptUrl,
            urlSource = keptSource,
            images = images.sortedWith(compareBy<VisibleImage>({ it.bounds.top }, { it.bounds.left })),
            takenAt = System.currentTimeMillis(),
            debug = "pkg=$pkg visited=$visited web=$sawWebContent screen=$screen\n$debug"
        )
    }

    companion object {
        private val URL_FIND = Regex(
            "(https?://)?(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d+)?(?:/[^\\s\"'<>]*)?",
            RegexOption.IGNORE_CASE
        )

        /** Returns the first URL-looking token in [text], or null. */
        fun findUrl(text: String): String? {
            if (text.length > 4000) return null
            val m = URL_FIND.find(text) ?: return null
            val v = m.value.trimEnd('.', ',', ')', ';')
            // Must have a dot in the host and not be an obvious file name like "image.png".
            val host = v.substringAfter("://").substringBefore('/')
            if (!host.contains('.')) return null
            if (Regex("\\.(png|jpe?g|gif|webp|svg|pdf)$", RegexOption.IGNORE_CASE).containsMatchIn(host)) return null
            return v
        }

        fun isAddressBarId(id: String): Boolean {
            val lower = id.lowercase()
            return lower.endsWith("mozac_browser_toolbar_url_view") || // Firefox (classic toolbar)
                lower.contains("url_bar") ||                            // Chrome
                lower.contains("location_bar") ||                       // Samsung Internet
                lower.contains("url_view") ||
                lower.contains("address") ||                            // Firefox (new toolbar)
                lower.contains("url_box") ||
                lower.contains("toolbar_url")
        }

        fun normaliseUrl(text: String): String =
            if (text.startsWith("http://", true) || text.startsWith("https://", true)) text
            else "https://$text"
    }
}
