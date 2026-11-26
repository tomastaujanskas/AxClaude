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

        var urlById: String? = null
        var urlGuess: String? = null
        val images = mutableListOf<VisibleImage>()

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < 5000) {
            val node = stack.removeLast()
            visited++

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val inToolbar = bounds.centerY() < screen.top + toolbarZone ||
                bounds.centerY() > screen.bottom - toolbarZone

            val id = node.viewIdResourceName ?: ""
            val text = node.text?.toString()?.trim()

            // 1. Address bar: known IDs first, then anything URL-shaped in the toolbar area.
            if (!text.isNullOrEmpty()) {
                if (urlById == null && isAddressBarId(id) && looksLikeUrl(text)) {
                    urlById = text
                } else if (urlGuess == null && inToolbar && looksLikeUrl(text)) {
                    urlGuess = text
                }
            }

            // 2. Images visible on screen (web page images, not toolbar icons).
            val cls = node.className?.toString() ?: ""
            val isWebImage = cls == "android.widget.Image"
            val isBigImage = cls.contains("Image") &&
                bounds.width() >= minImagePx && bounds.height() >= minImagePx && !inToolbar
            if ((isWebImage || isBigImage) && node.isVisibleToUser && Rect.intersects(bounds, screen)) {
                val desc = (node.contentDescription ?: node.text)?.toString()?.trim().orEmpty()
                if (images.none { it.bounds == bounds }) {
                    images.add(VisibleImage(desc, Rect(bounds)))
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        val url = (urlById ?: urlGuess)?.let { normaliseUrl(it) }
        // Keep the previous URL if this pass didn't find one (e.g. toolbar hidden while scrolling).
        val previous = SnapshotStore.latest
        val keptUrl = url ?: previous?.takeIf { it.packageName == pkg }?.url

        SnapshotStore.latest = PageSnapshot(
            packageName = pkg,
            url = keptUrl,
            images = images.sortedWith(compareBy<VisibleImage>({ it.bounds.top }, { it.bounds.left })),
            takenAt = System.currentTimeMillis()
        )
    }

    companion object {
        private val URL_REGEX = Regex(
            "^(https?://)?([a-z0-9-]+\\.)+[a-z]{2,}(:\\d+)?(/\\S*)?$",
            RegexOption.IGNORE_CASE
        )

        fun looksLikeUrl(text: String): Boolean =
            text.length in 4..4000 && !text.contains(' ') && URL_REGEX.matches(text)

        fun isAddressBarId(id: String): Boolean {
            val lower = id.lowercase()
            return lower.endsWith("mozac_browser_toolbar_url_view") || // Firefox
                lower.contains("url_bar") ||                            // Chrome
                lower.contains("location_bar") ||                       // Samsung Internet
                lower.contains("url_view") ||
                lower.contains("addressbar")
        }

        fun normaliseUrl(text: String): String =
            if (text.startsWith("http://", true) || text.startsWith("https://", true)) text
            else "https://$text"
    }
}
