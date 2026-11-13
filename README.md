# Desk Pager for Android (`v0.4.2`)

Companion Android receiver for [Desk Pager for Windows](https://github.com/bhattaganesh/desk-pager-windows). Delivers two-tier posture, eye-rest, and walk reminders over [ntfy.sh](https://ntfy.sh) (free, no account) with Multi-Laptop support (Home + Office) and a choice of alert style per reminder type: three vibrations, or vibrating or ringing until you stop it.

## Required Downloads (Mobile + Desktop)

Desk Pager requires **both** this Android app on your phone and the Windows desktop application on your laptop(s). Multiple releases are available — always download the **latest version** from the links below:

1. **Android Mobile Application (`desk-pager.apk`):**
   - **Latest Release (`v0.4.2`):** [https://github.com/bhattaganesh/desk-pager-android/releases/latest](https://github.com/bhattaganesh/desk-pager-android/releases/latest)
   - **All Android Releases:** [https://github.com/bhattaganesh/desk-pager-android/releases](https://github.com/bhattaganesh/desk-pager-android/releases)
2. **Companion Windows Desktop Application (`desk-pager.exe` — Required on your PC):**
   - **Latest Release (`v0.5.0`):** [https://github.com/bhattaganesh/desk-pager-windows/releases/latest](https://github.com/bhattaganesh/desk-pager-windows/releases/latest)
   - **All Windows Releases:** [https://github.com/bhattaganesh/desk-pager-windows/releases](https://github.com/bhattaganesh/desk-pager-windows/releases)

---

## Quick Start

1. Download and install `desk-pager.apk` from the [latest Android release](https://github.com/bhattaganesh/desk-pager-android/releases/latest) (Android 8.0+).
2. Download and run `desk-pager.exe` on your Windows PC from the [latest Windows release](https://github.com/bhattaganesh/desk-pager-windows/releases/latest), and open the **Phone Pairing** tab.
3. Pair your phone using either method:
   - Scan the QR code with your phone camera, open the `deskpager://` link in Desk Pager, and tap **Pair / Add PC** to confirm, or
   - Copy the `deskpager-…` secret code from your PC, tap **Paste** in the Android app, and tap **Pair / Add PC**.
4. **Pair Multiple Laptops (Home + Office):**
   - If you use both a Personal Laptop and an Office Laptop, tap **`+ Add PC`** on your phone and scan your second laptop's QR code. Both laptops remain connected simultaneously over a single stream — you never have to re-enter or switch codes.
5. Grant **Notifications** and tap **Allow unrestricted battery** so reminders wake your phone reliably during Doze mode. Both badges under **Delivery Readiness** will show **Enabled**.
6. Pick a style under **Quick stretch alert** and **Long break alert**, then tap **Try quick stretch alert** or **Try long break alert** to feel exactly what each setting does.

## Alert Styles

Each reminder type has its own setting on the main screen, with the same three choices:

- **Vibrate 3 times:** three pulses, once. No Stop button; the notification clears when you tap it. Uses the notification vibration setting, so silent mode and Do Not Disturb are respected.
- **Keep vibrating until I stop it:** vibrates continuously, as an alarm.
- **Keep ringing until I stop it:** loops your default alarm sound (the notification sound if no alarm sound is set) and vibrates continuously.

**Quick stretch alert** defaults to *Vibrate 3 times* and also applies to any other buzz, such as the PC's test buzz. **Long break alert** defaults to *Keep vibrating until I stop it*.

An alert that keeps going stops when you do any of these:

1. Tap **Stop** on the notification, or swipe the notification away,
2. Tap **Stop alert** inside the Android app,
3. Walk away from your Windows PC for **30+ seconds** (or lock it), which makes the Windows app send a `deskpager-clear` signal, or
4. Leave it for 10 minutes, after which it stops by itself.

Reminders use a single silent notification channel; the app does the vibrating and ringing itself so the chosen style is what you get.

- **Activity Counters & Recent History:**
  - Tracks total **Quick Stretches** and **Long Breaks** alongside the last 5 reminders received with timestamps.

## Privacy & Security

- Your 128-bit pairing topics are stored in the app's private `SharedPreferences` (`Context.MODE_PRIVATE`), excluded from cloud backups and device transfer, masked in the UI (`deskpager-••••••••••••<last4>`), and never written to logs.
- Any incoming `deskpager://pair?topic=…` deep link requires explicit user confirmation (**Pair / Add PC**) before modifying your paired PCs.

## Build & Verify

Requires JDK 17+ (tested with JDK 21) and Android SDK platform 36:

```powershell
.\gradlew testDebugUnitTest lintRelease assembleRelease
```

The signed release APK is output to `app/build/outputs/apk/release/app-release.apk`. Release signing reads `DESKPAGER_KEYSTORE`, `DESKPAGER_KEYSTORE_PASSWORD`, `DESKPAGER_KEY_ALIAS`, and `DESKPAGER_KEY_PASSWORD` from `~/.gradle/gradle.properties` (falling back to debug signing if unset).
