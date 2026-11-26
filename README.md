# Ax Claude

Adds **Ax Claude** to Android's text-selection menu (next to Copy / Share / Ask Gemini).
Tap it on selected text and you get a small popup:

- the selected text
- the page's web address (read from Firefox's address bar)
- the web addresses of images visible on screen
- an optional box to type a question

**Send to Claude** combines it all into one message and shares it to the Claude app:

```
What does this mean for scaling?

---
Page: https://www.hellointerview.com/learn/system-design/...

Selected text:
"""
the gateway extracts the appropriate identifier...
"""

Images visible on screen:
- https://example.com/diagram.png ("Rate limiter architecture")
```

## Building the APK

### Option A: GitHub (no Android Studio needed)
1. Create a new **private** GitHub repo and push this folder to it.
2. The included workflow (`.github/workflows/build.yml`) builds automatically.
3. Repo → **Actions** → latest run → download **AxClaude-apk** (a zip containing `app-debug.apk`).

### Option B: Android Studio
Open the folder → let Gradle sync → **Build → Build APK(s)**.
Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Installing on the phone
1. Copy `app-debug.apk` to the phone and open it. Allow installing from that source when asked.
2. Open the **Ax Claude** app once and follow its setup screen:
   - Settings → Accessibility → Installed apps → **Ax Claude page reader** → On.
   - If it says **Restricted setting**: App info → ⋮ → **Allow restricted settings**, then try again.
     (Android does this for every app installed from a file.)

Without the page reader, Ax Claude still sends the selected text and your question, but no URL or images.

## How it works
- `AxClaudeActivity`: handles Android's `PROCESS_TEXT` action, which is what puts "Ax Claude" in the
  selection menu. It has `android:priority="999"` so Android lists it first among app actions.
- `ScreenReaderService`: an accessibility service limited to Firefox, Chrome and Samsung Internet. It keeps the
  latest address-bar text and on-screen image positions and alt text in memory only.
- `ImageResolver`: downloads the page's HTML (jsoup) and matches on-screen images to `<img>` tags by alt text
  to get their web addresses.

## Known limits
- **Menu position:** Copy, Search, Select all and Share are always first; Ax Claude should come before
  Ask Gemini, but the order is decided by Android/Samsung and isn't guaranteed. On narrow screens it may sit in
  the ⋮ overflow.
- **Image addresses:** Android's screen reader only exposes an image's alt text, not its address, so images
  with no alt text, or pages that need you to be logged in, may show "address not found".
- **Web address:** if Firefox's toolbar layout changes, the reader falls back to any URL-looking text in the
  toolbar area.
