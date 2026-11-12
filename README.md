# Desk Pager for Android (`v0.4.0`)

Companion Android receiver for [Desk Pager for Windows](https://github.com/bhattaganesh/desk-pager-windows). Delivers two-tier posture, eye-rest, and walk reminders over [ntfy.sh](https://ntfy.sh) (free, no account) with Multi-Laptop support (Home + Office) and a strict repeating Long-Break alarm that automatically stops when you step away from your PC.

## Required Downloads (Mobile + Desktop)

Desk Pager requires **both** this Android app on your phone and the Windows desktop application on your laptop(s). Multiple releases are available — always download the **latest version** from the links below:

1. **Android Mobile Application (`desk-pager.apk`):**
   - **Latest Release (`v0.4.0`):** [https://github.com/bhattaganesh/desk-pager-android/releases/latest](https://github.com/bhattaganesh/desk-pager-android/releases/latest)
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
6. Tap **Test Phone Vibration & Alert** (or **Test Strict Long-Break Alarm**) in the app to verify vibration and sound immediately.

## Two-Tier Alert Channels & Strict Long-Break Alarm

- **Tier 1 — Quick Stretch, Eye Rest & Water (`stretch` channel):**
  - Delivers a high-priority notification with a gentle double-pulse vibration (`[0, 260, 150, 260]` ms) so you can stand briefly, stretch your spine, rest your eyes, and sip water right at your desk.
- **Tier 2 — Long Break (`buzz` channel + Strict Repeating Alarm):**
  - When you reach your 2-hour cumulative sitting limit, Desk Pager posts an ongoing alarm notification and runs a repeating multi-pulse vibration + alarm tone every 3.5 seconds until you **either**:
    1. Tap **Taking Break (Stop Alarm)** on the notification,
    2. Tap **Stop Break Alarm — I'm Taking My Break** inside the Android app, **or**
    3. Simply walk away from your Windows PC for **30+ seconds** (or lock your PC) — the Windows app automatically sends a `deskpager-clear` signal that stops your phone alarm hands-free!
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
