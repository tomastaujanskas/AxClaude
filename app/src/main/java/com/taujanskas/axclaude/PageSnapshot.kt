package com.taujanskas.axclaude

import android.graphics.Rect

/** An image the accessibility service saw on screen. */
data class VisibleImage(
    val description: String,
    val bounds: Rect
)

/** What was on screen in the browser the last time we looked. */
data class PageSnapshot(
    val packageName: String,
    val url: String?,
    val images: List<VisibleImage>,
    val takenAt: Long
)

/** Shared between the accessibility service and the Ax Claude popup (same process). */
object SnapshotStore {
    @Volatile
    var latest: PageSnapshot? = null

    /** Ignore snapshots older than this. */
    const val MAX_AGE_MS = 2 * 60 * 1000L

    fun fresh(): PageSnapshot? {
        val s = latest ?: return null
        return if (System.currentTimeMillis() - s.takenAt <= MAX_AGE_MS) s else null
    }
}
