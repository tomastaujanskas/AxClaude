package com.taujanskas.axclaude

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Setup screen: explains how to use Ax Claude and turns on the page reader. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        layout.addView(TextView(this).apply {
            text = "✳ Ax Claude"
            textSize = 24f
            setTextColor(0xFFD97757.toInt())
        })

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad, 0, pad / 2)
        }
        layout.addView(status)

        layout.addView(Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        layout.addView(Button(this).apply {
            text = "Open app info (for restricted settings)"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        })

        layout.addView(TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, 0)
            text = """
                How to use
                1. In Firefox, select some text.
                2. Tap "Ax Claude" in the menu that pops up (if you can't see it, tap ⋮ in that menu).
                3. Optionally type a question, then tap Send to Claude.

                Turning on the page reader (needed for the web address and images)
                1. Tap "Open accessibility settings" → Installed apps → Ax Claude page reader → On.
                2. If Android says the setting is restricted (normal for apps installed from a file):
                   tap "Open app info", then ⋮ (top right) → "Allow restricted settings", and try step 1 again.

                The page reader only watches Firefox, Chrome and Samsung Internet, keeps nothing on the phone,
                and sends nothing anywhere until you tap Send to Claude.
            """.trimIndent()
        })

        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (isServiceEnabled(this))
            "✓ Page reader is on. You're all set."
        else
            "✗ Page reader is off. Ax Claude will still send selected text, but not the web address or images."
    }

    companion object {
        fun isServiceEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ScreenReaderService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
