package com.taujanskas.axclaude

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.Intent
import android.graphics.Bitmap
import android.view.Display
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Watches the supported browsers and:
 *  - keeps a snapshot of the current address and the images visible on screen;
 *  - shows the "✳ Ax Claude" bubble beside the browser's text-selection menu.
 * Nothing is stored on disk or sent anywhere; the Ax Claude popup reads the snapshot
 * when you open it.
 */
class ScreenReaderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private val captureRunnable = Runnable {
        pending = false
        try {
            val browserActive = captureNow()
            updateBubble(browserActive)
        } catch (e: Exception) {
            // Never let a bad node tree crash the service.
        }
    }

    private lateinit var bubble: SelectionBubble
    /** The selection menu's "Copy" item, clicked when the bubble is tapped. */
    private var copyNode: AccessibilityNodeInfo? = null
    /** Selected text reported by the browser itself, if it reports it. */
    private var eventSelection: String? = null
    private var eventSelectionAt = 0L
    private val autoHide = Runnable { bubble.hide() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        bubble = SelectionBubble(this) { onBubbleTapped() }
        Screenshots.cleanup(this)
    }

    override fun onDestroy() {
        if (::bubble.isInitialized) bubble.hide()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return
        val type = event.eventType
        val windowEvent = type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (pkg !in BROWSERS && !windowEvent) return

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED && pkg in BROWSERS) {
            rememberSelection(event)
        }

        // Coalesce bursts of events into one capture.
        if (!pending) {
            pending = true
            handler.postDelayed(captureRunnable, 250)
        }
    }

    override fun onInterrupt() {}

    private fun rememberSelection(event: AccessibilityEvent) {
        try {
            val from = event.fromIndex
            val to = event.toIndex
            val full = event.text.joinToString("").ifEmpty { event.source?.text?.toString().orEmpty() }
            if (from >= 0 && to > from && to <= full.length) {
                eventSelection = full.substring(from, to).trim()
                eventSelectionAt = System.currentTimeMillis()
            }
        } catch (e: Exception) {
        }
    }

    /** Taps Copy like a finger would; falls back to an accessibility click. */
    private fun pressCopy(node: AccessibilityNodeInfo): Boolean {
        try {
            node.refresh()
            val r = Rect()
            node.getBoundsInScreen(r)
            if (!r.isEmpty) {
                val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                    .build()
                if (dispatchGesture(gesture, null, null)) return true
            }
        } catch (e: Exception) {
        }
        // Fallback: click the nearest clickable ancestor.
        return try {
            var n: AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            (n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- Screenshot ----------------

    /** Takes a screenshot into the private cache; calls [done] on the main thread (null on failure). */
    private fun takeTempScreenshot(done: (java.io.File?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            done(null); return
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        Thread {
                            var file: java.io.File? = null
                            try {
                                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                                result.hardwareBuffer.close()
                                if (bmp != null) {
                                    file = Screenshots.save(this@ScreenReaderService, bmp)
                                    bmp.recycle()
                                }
                            } catch (e: Exception) {
                            }
                            handler.post { done(file) }
                        }.start()
                    }

                    override fun onFailure(errorCode: Int) {
                        done(null)
                    }
                })
        } catch (e: Exception) {
            done(null)
        }
        // Safety net: remove old screenshots later even if the popup is never opened again.
        handler.postDelayed({ Screenshots.cleanup(this) }, Screenshots.KEEP_MS + 5_000)
    }

    // ---------------- Bubble ----------------

    private fun bubbleEnabled(): Boolean =
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getBoolean(MainActivity.PREF_BUBBLE, true)

    private fun updateBubble(browserActive: Boolean) {
        if (!::bubble.isInitialized) return
        if (!browserActive || !bubbleEnabled()) {
            bubble.hide(); copyNode = null; return
        }
        val menu = findSelectionMenu()
        if (menu == null) {
            bubble.hide(); copyNode = null; return
        }
        copyNode = menu.second
        bubble.showNear(menu.first)
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, 30_000)
    }

    /** Finds the browser's floating text-selection menu: its bounds and its Copy item. */
    private fun findSelectionMenu(): Pair<Rect, AccessibilityNodeInfo>? {
        val wins: List<AccessibilityWindowInfo> = try { windows } catch (e: Exception) { emptyList() }
        for (w in wins) {
            val root = w.root ?: continue
            if (root.packageName?.toString() !in BROWSERS) continue
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            var visited = 0
            while (stack.isNotEmpty() && visited < 3000) {
                val n = stack.removeLast()
                visited++
                val label = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (label.equals("Copy", ignoreCase = true)) {
                    val parent = n.parent
                    if (parent != null && hasMenuSibling(parent)) {
                        val r = Rect()
                        parent.getBoundsInScreen(r)
                        // Floating menus are short and wide; skip anything page-sized.
                        if (r.height() < (120 * resources.displayMetrics.density)) return r to n
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    private fun hasMenuSibling(parent: AccessibilityNodeInfo): Boolean {
        val others = setOf("select all", "share", "search", "paste", "cut")
        for (i in 0 until parent.childCount) {
            val c = parent.getChild(i) ?: continue
            val label = (c.text ?: c.contentDescription)?.toString()?.trim()?.lowercase().orEmpty()
            if (label in others) return true
        }
        return false
    }

    private fun onBubbleTapped() {
        bubble.hide()
        handler.removeCallbacks(autoHide)

        val fromEvent = eventSelection
            ?.takeIf { System.currentTimeMillis() - eventSelectionAt < 60_000 }
            .orEmpty()
        val tapTime = System.currentTimeMillis()
        // Press the browser's own Copy so the exact selection lands on the clipboard.
        val copied = copyNode?.let { pressCopy(it) } ?: false
        copyNode = null

        // Let the menu and bubble disappear, then take the screenshot, then open the popup.
        handler.postDelayed({
            takeTempScreenshot { file ->
                val intent = Intent(this, AxClaudeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (fromEvent.isNotEmpty()) putExtra(Intent.EXTRA_PROCESS_TEXT, fromEvent)
                    putExtra(AxClaudeActivity.EXTRA_READ_CLIPBOARD, copied)
                    putExtra(AxClaudeActivity.EXTRA_TAP_TIME, tapTime)
                    if (file != null) putExtra(AxClaudeActivity.EXTRA_SCREENSHOT, file.absolutePath)
                }
                startActivity(intent)
            }
        }, 450L)
    }

    private data class Item(val node: AccessibilityNodeInfo, val inWeb: Boolean)

    /** Updates the snapshot. Returns true if a supported browser is in front. */
    private fun captureNow(): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: return false
        if (pkg == packageName || pkg !in BROWSERS) return false

        val screen = Rect()
        root.getBoundsInScreen(screen)
        if (screen.isEmpty) return true

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
        return true
    }

    companion object {
        val BROWSERS = setOf(
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
            "com.android.chrome", "com.sec.android.app.sbrowser"
        )

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
