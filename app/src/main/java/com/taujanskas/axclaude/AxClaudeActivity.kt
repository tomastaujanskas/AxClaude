package com.taujanskas.axclaude

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.CheckBox
import android.widget.ImageView
import androidx.core.content.FileProvider
import java.io.File
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Opens when you tap "Ax Claude" in the text-selection menu. Shows what will be sent,
 * lets you add an optional question, then shares everything to the Claude app as one text.
 */
class AxClaudeActivity : Activity() {

    private var selected: String = ""
    private var snapshot: PageSnapshot? = null
    @Volatile private var imageResult: ImageResolver.Result? = null
    @Volatile private var imagesDone = false

    private lateinit var imagesText: TextView
    private lateinit var selectedView: TextView
    private var readClipboardPending = false
    private var shotFile: File? = null
    private lateinit var includeShot: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ax)
        Screenshots.cleanup(this)

        selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        snapshot = SnapshotStore.fresh()

        selectedView = findViewById(R.id.selectedText)
        val pageText = findViewById<TextView>(R.id.pageText)
        imagesText = findViewById(R.id.imagesText)
        val question = findViewById<EditText>(R.id.question)

        // Opened from the bubble: the selection was copied, read it once we have focus.
        readClipboardPending = intent.getBooleanExtra(EXTRA_READ_CLIPBOARD, false)
        showSelected()

        val url = snapshot?.url
        if (url != null) {
            pageText.text = "🔗 $url"
        } else if (!isServiceEnabled()) {
            pageText.text = "🔗 Web address unavailable: tap here to turn on Ax Claude's page reader"
            pageText.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        } else {
            pageText.text = "🔗 Couldn't read the web address"
        }

        startImageLookup()
        setUpScreenshot()

        // Hidden troubleshooting: long-press the title to copy what the app saw.
        findViewById<TextView>(R.id.title).setOnLongClickListener {
            copyDebugInfo()
            true
        }

        findViewById<Button>(R.id.cancel).setOnClickListener {
            shotFile?.delete()
            finish()
        }
        findViewById<Button>(R.id.send).setOnClickListener {
            sendToClaude(question.text?.toString()?.trim().orEmpty())
        }
    }

    private fun setUpScreenshot() {
        includeShot = findViewById(R.id.includeShot)
        val path = intent.getStringExtra(EXTRA_SCREENSHOT) ?: return
        val file = File(path)
        // Only accept files from our own private screenshot folder.
        if (!file.exists() || file.parentFile?.canonicalPath != Screenshots.dir(this).canonicalPath) return
        shotFile = file
        findViewById<View>(R.id.shotRow).visibility = View.VISIBLE
        Thread {
            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            val thumb = BitmapFactory.decodeFile(file.path, opts)
            runOnUiThread { if (thumb != null) findViewById<ImageView>(R.id.shotPreview).setImageBitmap(thumb) }
        }.start()
    }

    private fun showSelected() {
        selectedView.text = when {
            selected.isNotEmpty() -> "“$selected”"
            readClipboardPending -> "(Reading selected text…)"
            else -> "(No text selected)"
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets the focused app read the clipboard.
        if (hasFocus && readClipboardPending) {
            readClipboardPending = false
            val cm = getSystemService(ClipboardManager::class.java)
            val clip = cm.primaryClip
            val text = if (clip != null && clip.itemCount > 0)
                clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty() else ""
            if (text.isNotEmpty()) selected = text
            showSelected()
        }
    }

    private fun startImageLookup() {
        val snap = snapshot
        val visible = snap?.images.orEmpty()
        if (snap == null || visible.isEmpty()) {
            imagesDone = true
            imagesText.text = if (snap == null) "" else "🖼 No images on screen"
            imagesText.visibility = if (snap == null) View.GONE else View.VISIBLE
            return
        }
        val url = snap.url
        if (url == null) {
            imageResult = ImageResolver.Result(visible.map { it to null }, "no URL, so no page fetch")
            imagesDone = true
            showImageSummary()
            return
        }
        imagesText.text = "🖼 ${visible.size} image(s) on screen: finding their addresses…"
        Thread {
            val result = ImageResolver.resolve(url, visible)
            imageResult = result
            imagesDone = true
            runOnUiThread { if (!isFinishing) showImageSummary() }
        }.start()
    }

    private fun showImageSummary() {
        val r = imageResult ?: return
        val withUrl = r.matched.count { it.second != null }
        imagesText.text = "🖼 ${r.matched.size} image(s) on screen, $withUrl with web address"
    }

    private fun buildText(question: String): String {
        val sb = StringBuilder()
        if (question.isNotEmpty()) {
            sb.append(question).append("\n\n---\n")
        }
        snapshot?.url?.let { sb.append("Page: ").append(it).append("\n") }

        if (selected.isNotEmpty()) {
            sb.append("\nSelected text:\n\"\"\"\n").append(selected).append("\n\"\"\"\n")
        }

        val r = imageResult
        if (r != null && r.matched.isNotEmpty()) {
            sb.append("\nImages visible on screen:\n")
            r.matched.forEach { (img, page) ->
                val alt = img.description.ifEmpty { page?.alt.orEmpty() }
                val line = when {
                    page != null && alt.isNotEmpty() -> "- ${page.url} (\"$alt\")"
                    page != null -> "- ${page.url}"
                    alt.isNotEmpty() -> "- \"$alt\" (address not found)"
                    else -> "- (unlabelled image, address not found)"
                }
                sb.append(line).append("\n")
            }
        } else if (!imagesDone) {
            sb.append("\n(Images on screen: lookup still running when sent)\n")
        }
        return sb.toString().trim()
    }

    private fun sendToClaude(question: String) {
        val text = buildText(question)
        val shot = shotFile?.takeIf { includeShot.isChecked && it.exists() }
        if (shot == null) shotFile?.delete()

        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (shot != null) {
                val uri = FileProvider.getUriForFile(this@AxClaudeActivity, "$packageName.files", shot)
                type = if (shot.name.endsWith(".jpg")) "image/jpeg" else "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri("Screenshot", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
            setPackage(CLAUDE_PACKAGE)
        }

        if (shot != null) {
            // In case Claude only picks up the image, keep the text on the clipboard too.
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Ax Claude", text))
            Toast.makeText(this, "Text also copied: paste it if it's missing", Toast.LENGTH_LONG).show()
        }

        try {
            startActivity(send)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Claude app not found. Pick an app to share with", Toast.LENGTH_SHORT).show()
            send.setPackage(null)
            startActivity(Intent.createChooser(send, "Share with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    private fun copyDebugInfo() {
        val snap = SnapshotStore.latest
        val info = buildString {
            append("Ax Claude debug\n")
            append("service on: ").append(isServiceEnabled()).append('\n')
            if (snap == null) {
                append("no snapshot yet\n")
            } else {
                val age = (System.currentTimeMillis() - snap.takenAt) / 1000
                append("snapshot age: ${age}s, url: ${snap.url}, from: ${snap.urlSource}\n")
                append("images: ").append(snap.images.joinToString { "\"${it.description}\" ${it.bounds.toShortString()}" }).append('\n')
                append("page fetch: ").append(imageResult?.debug ?: "not finished").append('\n')
                append("--- browser UI nodes ---\n").append(snap.debug)
            }
        }
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Ax Claude debug", info))
        Toast.makeText(this, "Debug info copied", Toast.LENGTH_SHORT).show()
    }

    private fun isServiceEnabled(): Boolean = MainActivity.isServiceEnabled(this)

    companion object {
        const val CLAUDE_PACKAGE = "com.anthropic.claude"
        const val EXTRA_READ_CLIPBOARD = "com.taujanskas.axclaude.READ_CLIPBOARD"
        const val EXTRA_SCREENSHOT = "com.taujanskas.axclaude.SCREENSHOT"
    }
}
