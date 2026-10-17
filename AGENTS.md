# InstaPin — Pinterest-Style Hover Organizer & AI Repurposing Studio for Instagram (Moto G84 5G)

InstaPin is a native Android application (`com.bluemachines.instapin`) built in **Kotlin + Jetpack Compose** for the **Motorola Moto G84 5G**. It hovers over Instagram so you can save, categorize, and deeply analyze your daily 15–20 educational Instagram Reels without downloading heavy video files or modifying your Instagram account.

## Architecture Overview (3 Phases)

### Phase 1: Floating Pinterest Hover Bubble & Multi-Board Categorization
- **Floating Edge Bubble (`FloatingOverlayService.kt`):** Uses `SYSTEM_ALERT_WINDOW` to render a 120Hz Jetpack Compose floating button when Instagram is active.
- **Pinterest Radial Hold-and-Slide Menu (`RadialBoardMenu.kt`):**
  - **Hold & Slide:** Expands a circular radial menu of your top pinned Boards (*Video Editing*, *Trading*, *Health & Fitness*, *Jobs & Career*, *AI & Tech Tools*, plus *AI Auto-Sort*). Slide your thumb and release to pin in 1 second.
  - **Single Tap / Share to InstaPin (`ShareReceiverActivity.kt`):** Opens the Quick Save Overlay Sheet to assign **multiple Boards**, **multiple Moods** (*Recreate Soon*, *Hook Inspiration*, *Deep Study*, *Actionable Tip*), and notes.
- **Context & Metadata Extraction (`InstaPinAccessibilityService.kt` + `InstagramMetadataExtractor.kt`):**
  - Automatically reads `@author_handle`, caption text, hashtags, and saved date/time (`savedAt`), and normalizes the Instagram Reel permalink (`https://www.instagram.com/reel/<shortcode>/`).
- **Multi-Dimensional Discovery (`PinFeedScreen.kt` & `BoardsGridScreen.kt`):**
  - Filter and search your library by **Board**, **Mood**, **Date Range** (*Today*, *Last 7 Days*, *Last 30 Days*, *All Time*), **Author (`@creator`)**, and **Domain**.

### Phase 2: Deep Video Analysis & Content Repurposing Studio (`GeminiAiService.kt` + `PinDetailAndRepurposeScreen.kt`)
- Powered by **Google Gemini (`gemini-2.5-flash`)** (with an instant offline structured fallback when no API key is configured):
  1. **Executive Summary & Actionable Takeaways:** Core summary, step-by-step bullets, and tools/indicators/websites mentioned.
  2. **Transcript & Hook/Structure Breakdown:** Spoken/on-screen transcript, **0–3s Scroll-Stopper Hook** analysis, body retention pacing, and CTA breakdown.
  3. **Editing & Visual Style Notes:** Cut frequency, zoom/keyframes, B-roll usage, caption typography, and audio mood.
  4. **Recreate & Repurpose Studio:** 3 alternative hooks, a ready-to-record **Teleprompter Script** in your own voice, and a **Shot-by-Shot Filming & Editing Checklist**.

### Phase 3: Historical Account Import & Bulk AI Sorter (`InstagramExportJsonParser.kt` + `Phase3HistoricalImportScreen.kt`)
- Imports Instagram's official `liked_posts.json` and `saved_posts.json` export files.
- Deduplicates against existing pins, preserves original historical timestamps and `@author` handles, auto-categorizes every link into Domains and Boards, and presents a **Bulk Triage Inbox** for 1-tap approval.

---

## Building & Installing on Your Moto G84 5G

### Option 1: Open in Android Studio
1. Open Android Studio and select **Open** -> choose this folder (`Pintrest for Instagram `).
2. Connect your **Moto G84 5G** via USB (with *Settings -> Developer Options -> USB Debugging* enabled) or Wireless Debugging.
3. Click **Run ('app')** to install and launch directly on your phone.

### Option 2: Command-Line Build & ADB Install (macOS)
If you use Homebrew on macOS:
```bash
brew install --cask temurin@17 android-commandlinetools
gradle testDebugUnitTest
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Moto G84 5G First-Launch Setup (Inside App -> `Moto G84 Setup` Tab)
1. Tap **Grant** on **1. Display Over Other Apps** and toggle **InstaPin** on.
2. Tap **Enable** on **2. InstaPin Context Reader** (Accessibility Settings -> Installed Apps -> InstaPin -> Turn On).
3. (Optional) Paste your **Google Gemini API Key** (`AIza...`) in the AI Configuration card and tap **Save AI Settings**.
4. Tap **Start Hover Bubble** and open Instagram Reels!

### Protected Stable Baseline (`v1.0.15` / commit `58c6a1f`)
- **Stable working flow:** In Instagram, manually tap **Share -> Copy link** on the Reel -> tap the InstaPin floating bubble -> `ShareReceiverActivity` reads the copied Reel URL on window focus -> tap a category/board -> save.
- **Rules for future changes:**
  1. Never rewrite or remove this manual **Copy link -> tap bubble -> pick category -> save** flow.
  2. Ship every change as a new GitHub Actions release (`v1.0.17+`) with `versionCode = 100` and persistent debug signing so **Download & Update** installs in-place without losing pins, boards, or settings.
  3. Keep `v1.0.15` (`58c6a1f`) available in **Moto G84 Setup -> All Versions / Rollback** as the stable rollback baseline.

### Exact Reel Link Capture (Debugging)
- No local Android SDK/adb on the Mac: verify builds via GitHub Actions (`curl https://api.github.com/repos/unime1988/instapin-moto-g84/actions/runs?per_page=1`), then download the release APK.
- Android 10+ only lets the focused app read the clipboard, so clipboard reads happen in `ShareReceiverActivity` (on window focus), never in the overlay service.
- Accessibility automation must never scroll or swipe the Reels feed (it advances to the next Reel). Only scroll containers holding share-sheet labels.
- When capture fails on-device, ask the user for **Moto G84 Setup -> Last Reel Link Capture -> Copy diagnostics** — it lists the real Instagram labels/IDs at the failed step.
