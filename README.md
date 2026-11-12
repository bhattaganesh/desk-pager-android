# Desk Pager for Android (`v0.3.0`)

Companion Android receiver for [Desk Pager for Windows](https://github.com/bhattaganesh/desk-pager-windows). Delivers two-tier posture, eye-rest, and walk reminders over [ntfy.sh](https://ntfy.sh) (free, no account) with a strict repeating Long-Break alarm that automatically stops when you step away from your PC.

## Quick Start

1. Download `desk-pager.apk` from the [latest release](https://github.com/bhattaganesh/desk-pager-android/releases/latest) and install it on your phone (Android 8.0+).
2. Open **Desk Pager** on your Windows PC and switch to the **Phone Pairing** tab.
3. Pair your phone using either method:
   - Scan the QR code with your phone camera, open the `deskpager://` link in Desk Pager, and tap **Pair Phone** to confirm, or
   - Copy the `deskpager-…` secret code from your PC, tap **Paste** in the Android app, and tap **Pair Phone**.
4. Grant **Notifications** and tap **Allow unrestricted battery** so reminders wake your phone reliably during Doze mode. Both badges under **Delivery Readiness** will show **Enabled**.
5. Tap **Test Phone Vibration & Alert** (or **Test Strict Long-Break Alarm**) in the app to verify vibration and sound immediately.

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

- Your 128-bit pairing topic is stored in the app's private `SharedPreferences` (`Context.MODE_PRIVATE`), excluded from cloud backups and device transfer, Masked in the UI (`deskpager-••••••••••••<last4>`), and never written to logs.
- Any incoming `deskpager://pair?topic=…` deep link requires explicit user confirmation (**Pair Phone**) before changing the active topic.

## Build & Verify

Requires JDK 17+ (tested with JDK 21) and Android SDK platform 36:

```powershell
.\gradlew testDebugUnitTest lintRelease assembleRelease
```

The signed release APK is output to `app/build/outputs/apk/release/app-release.apk`. Release signing reads `DESKPAGER_KEYSTORE`, `DESKPAGER_KEYSTORE_PASSWORD`, `DESKPAGER_KEY_ALIAS`, and `DESKPAGER_KEY_PASSWORD` from `~/.gradle/gradle.properties` (falling back to debug signing if unset).
