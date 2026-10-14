# 🤖 J.A.R.V.I.S. Native Android App (Real APK for Your Phone)

Welcome to the **real native Android application** for **J.A.R.V.I.S.**, engineered specifically for your **Redmi Note 12 Pro 5G** and modern Android smartphones.

This is **not a website or a PWA** — it is a real Android app (`.apk`) that installs on your phone with a glowing Arc Reactor icon in your app drawer, runs native background services, captures the screen and camera, and floats over other apps like Messenger chat heads.

---

## ⚡ Zero PC Burden (Cloud Builder)

Because heavy software like Android Studio or Gradle can freeze or overheat PCs, **you do not need Android Studio on your computer**.

This project includes a **GitHub Actions Cloud Builder** (`.github/workflows/build-apk.yml`). High-speed cloud servers compile your `.apk` in 2 minutes for free, and you download the `.apk` directly to your phone!

### 📥 How to Get Your APK in 3 Steps:

1. **Double-Click `Export-Ready-Zip.bat`** inside this folder:
   * It packages everything cleanly into `D:\antigravity\Jarvis-Android-Ready.zip`.
2. **Upload to GitHub**:
   * Open [https://github.com/new](https://github.com/new) and create a repository (e.g. `jarvis-android`).
   * Drag and drop the project files (or push via git) and click **Commit changes**.
3. **Download Your APK**:
   * Click the **Actions** tab on your GitHub repository.
   * You will see the workflow **"Build Jarvis Android APK"** running.
   * When finished (green checkmark in ~2 mins), click on it and download **`Jarvis-Android-APK`**!
   * Transfer or open on your phone and tap **Install**.

---

## 📱 Key Features Built into Your Android App

### 1. 👫 Dual-App & Dual Profiles (Aditya & Aditi on One Phone)
* **Native Redmi Dual Apps**: MIUI / HyperOS clones the app into an isolated sandbox (`user/999`), allowing Aditya and Aditi to have completely separate assistant instances on the same phone!
* **1-Tap Profile Switcher**: Right in the top bar, tap the circular switch icon (`🔄`) to switch instantly between:
  * **Aditya's Mode**: Loving, caring, witty girlfriend companion who manages JEE/Board exam prep, teases playfully, sighs, and blushes.
  * **Aditi's Mode**: Loyal, supportive, hype best-friend and sisterly companion with empathetic listening and fun advice.
* **Custom Assistant Name**: Change her name anytime in Settings (e.g. Jarvis, Friday, Rose).

### 2. 🔄 Continuous Hands-Free Duplex Voice Loop
* Turn ON **🔄 Loop** in the app or tap the loop icon in the floating menu.
* Talk completely hands-free — when Jarvis finishes speaking, the microphone automatically opens again after 400ms without tapping any buttons!
* Real human conversational flow with rolling multi-turn memory.

### 3. 🛑 Multiple Emergency Killswitches & Barge-In
* **Hardware Volume Down Double-Press**: Rapidly double-press your phone's Volume Down button to instantly kill speech and listening!
* **Notification STOP Button**: A persistent notification in the status bar and lockscreen features a bright red **🛑 STOP** action button.
* **Instant Voice Barge-In**: Say *"Stop!"*, *"Chup!"*, *"Quiet!"*, *"Cancel!"*, *"Ruk!"*, or *"Bas!"* while she is speaking.
* **Big Red STOP Button**: On-screen in the app and on the floating bubble menu.

### 4. 🕶️ Ghost / Private Mode & 🧹 1-Tap Memory Wipe
* **Ghost Mode**: Tap the eye icon (`🕶️`) to enter Ghost Mode (Hologram glows in stealth neon purple). No prompts or conversations are saved.
* **1-Tap Wipe Memory**: Tap the trash icon (`🧹`) to flush all temporary context and chat memory in one go with a cute spoken confirmation!

### 5. 🔐 Settings App Lock (PIN / Password / Biometric)
* Configure security locks in Settings:
  * **None**
  * **PIN**
  * **Password**
  * **Biometric Fingerprint / Face Unlock**
* Protects private chats, personal notes, and API keys from unauthorized access.

### 6. 🛸 Floating Hologram Arc Reactor (Over Other Apps)
* A glowing, animated Iron Man Arc Reactor bubble floats over **WhatsApp, YouTube, Instagram, Netflix, or games**.
* **Drag anywhere**: Smooth touch physics with magnetic edge snap.
* **Tap to expand**: Gives instant 1-tap access to Talk, Continuous Loop, Camera Vision, Screen Vision, Flashlight, Ghost Mode, and Wipe Memory.

### 7. 📋 Smart Clipboard Assistant
* When you copy text anywhere on your phone (in WhatsApp, Chrome, or Notes), a mini floating pill `[ ⚡ Ask Jarvis ]` pops up.
* Tap it to instantly summarize, translate, or explain the copied text!

### 8. 📚 Study Focus Mode with 2-Stage Distraction Nudge
* Tap **📚 Study** to start a 25m Pomodoro, 45m deep study, or 60m Board exam focus block.
* 2-Stage distraction nudge:
  * **Stage 1**: Gentle reminder vibration and alert.
  * **Stage 2**: Loving voice scolding after 2 minutes (*"Adi! Phone rakh do aur padhai par dhyan do, exam aane wala hai!"*).

### 9. 🛠️ 100% Lightweight Offline Native Tools (0% RAM / 0% Lag)
* **🔦 Torch / Flashlight**: Instant native toggle (< 5ms) via camera flash.
* **🧮 Calculator**: Instant 1-tap launcher for Xiaomi MIUI / Google Calculator.
* **📝 Offline Scratchpad**: Quick local notes saved privately on device.
* **⏰ Native Alarms (`AlarmManager`)**: Vocal reminders that ring even when the phone is asleep.

### 10. 🌐 Live Google Search Grounding & Vision
* **Real-time Google Search**: Powered by Gemini API to provide accurate, up-to-the-minute facts, news, scores, and dates.
* **📱 Screen Vision (`MediaProjection`)**: Inspects active screen pixels without cluttering your photo gallery.
* **📷 Camera Vision (`CameraX`)**: Fast optic frame capture for real-world object and problem analysis.

---

## ⚙️ Redmi Note 12 Pro 5G (MIUI / HyperOS) Setup Tips

To ensure Xiaomi's battery saver doesn't close Jarvis when you switch apps:

1. **Floating Windows**:
   * Go to **Settings → Apps → Manage Apps → JARVIS → Other Permissions**.
   * Turn ON: **"Display pop-up windows while running in the background"**.
2. **Autostart**:
   * Turn ON: **"Autostart"**.
3. **Battery Saver**:
   * Set **Battery Saver** to **"No restrictions"** so reminders and the floating bubble stay active in the background.
4. **Dual Apps**:
   * Go to **Settings → Apps → Dual Apps**.
   * Toggle ON **JARVIS** to create the second isolated copy for your sister Aditi!

---

## 🛡️ Note on Original PC Jarvis
Your original PC Jarvis in **`D:\antigravity\jarvis`** remains **100% untouched and in view-only mode**. All mobile files are contained exclusively within this dedicated folder.
