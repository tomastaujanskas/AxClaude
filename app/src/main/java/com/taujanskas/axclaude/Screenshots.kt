package com.taujanskas.axclaude

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/** Temporary screenshots, kept only in the app's private cache and deleted after a few minutes. */
object Screenshots {

    /** How long a screenshot is kept, so Claude has time to read it. */
    const val KEEP_MS = 10 * 60 * 1000L

    fun dir(context: Context): File = File(context.cacheDir, "shots").apply { mkdirs() }

    /** Saves [bitmap] and returns the file, or null on failure. */
    fun save(context: Context, bitmap: Bitmap): File? {
        cleanup(context)
        return try {
            val file = File(dir(context), "screen-${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Very large PNGs: re-save as high-quality JPEG to stay well under upload limits.
            if (file.length() > 4_500_000) {
                val jpg = File(file.path.removeSuffix(".png") + ".jpg")
                FileOutputStream(jpg).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                file.delete()
                jpg
            } else file
        } catch (e: Exception) {
            null
        }
    }

    /** Deletes screenshots older than [KEEP_MS] (or all of them if [all]). */
    fun cleanup(context: Context, all: Boolean = false) {
        val now = System.currentTimeMillis()
        dir(context).listFiles()?.forEach {
            if (all || now - it.lastModified() > KEEP_MS) it.delete()
        }
    }
}
