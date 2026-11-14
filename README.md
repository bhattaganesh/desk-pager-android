# Desk Pager for Android (`v0.5.0`)

Companion Android receiver for [Desk Pager for Windows](https://github.com/bhattaganesh/desk-pager-windows). Delivers two-tier posture, eye-rest, and walk reminders over [ntfy.sh](https://ntfy.sh) (free, no account) with Multi-Laptop support (Home + Office) and a choice of alert style per reminder type: three vibrations, or vibrating or ringing until you stop it.

## Required Downloads (Mobile + Desktop)

Desk Pager requires **both** this Android app on your phone and the Windows desktop application on your laptop(s). Multiple releases are available — always download the **latest version** from the links below:

1. **Android Mobile Application (`desk-pager.apk`):**
   - **Latest Release (`v0.5.0`):** [https://github.com/bhattaganesh/desk-pager-android/releases/latest](https://github.com/bhattaganesh/desk-pager-android/releases/latest)
   - **All Android Releases:** [https://github.com/bhattaganesh/desk-pager-android/releases](https://github.com/bhattaganesh/desk-pager-android/releases)
2. **Companion Windows Desktop Application (`desk-pager.exe` — Required on your PC):**
   - **Latest Release (`v0.6.1`):** [https://github.com/bhattaganesh/desk-pager-windows/releases/latest](https://github.com/bhattaganesh/desk-pager-windows/releases/latest)
   - **All Windows Releases:** [https://github.com/bhattaganesh/desk-pager-windows/releases](https://github.com/bhattaganesh/desk-pager-windows/releases)

---

## Quick Start

1. Download and install `desk-pager.apk` from the [latest Android release](https://github.com/bhattaganesh/desk-pager-android/releases/latest) (Android 8.0+).
2. Download and run `desk-pager.exe` on your Windows PC from the [latest Windows release](https://github.com/bhattaganesh/desk-pager-windows/releases/latest), and open the **Phone Pairing** tab.
3. Pair your phone: tap **Scan QR code** in the Android app and point the camera at the QR code on your PC. The phone pairs straight away and shows **Paired with <your PC's name>**.
   - Scanning uses Google Play Services' code scanner, so the app needs no camera permission. If the scanner is not available on your phone, tap **Enter code instead**, copy the `deskpager-…` code from your PC, tap **Paste**, then **Pair**.
   - Scanning the QR code with the phone's own camera app also works: it opens Desk Pager, which asks you to confirm with **Add this PC** before anything changes.
4. **More than one PC (home and office):** tap **Add PC** and scan the second PC's QR code. Up to 5 PCs stay connected at the same time; remove one with the bin icon next to it.
5. If the app shows **Finish setting up**, tap **Turn on notifications** and **Allow background use** so reminders arrive while the screen is off.
6. Under **How reminders alert you**, pick a style for **Quick stretch** and **Long break**, then tap **Try it** to feel exactly what each setting does.

## Alert Styles

Each reminder type has its own setting under **How reminders alert you**, with the same three choices:

- **Vibrate 3 times:** three pulses, once. No Stop button; the notification clears when you tap it. Uses the notification vibration setting, so silent mode and Do Not Disturb are respected.
- **Keep vibrating until I stop it:** vibrates continuously, as an alarm.
- **Keep ringing until I stop it:** loops your default alarm sound (the notification sound if no alarm sound is set) and vibrates continuously.

**Quick stretch** defaults to *Vibrate 3 times* and also applies to any other buzz, such as the PC's test buzz. **Long break** defaults to *Keep vibrating until I stop it*.

An alert that keeps going stops when you do any of these:

1. Tap **Stop** on the notification, or swipe the notification away,
2. Tap **Stop** on the card at the top of the Android app,
3. Walk away from your Windows PC for **30+ seconds** (or lock it), which makes the Windows app send a `deskpager-clear` signal, or
4. Leave it for 10 minutes, after which it stops by itself.

Reminders use a single silent notification channel; the app does the vibrating and ringing itself so the chosen style is what you get.

- **Activity Counters & Recent History:**
  - Tracks total **Quick Stretches** and **Long Breaks** alongside the last 5 reminders received with timestamps.

## Privacy & Security

- Your 128-bit pairing topics are stored in the app's private `SharedPreferences` (`Context.MODE_PRIVATE`), excluded from cloud backups and device transfer, masked in the UI (`deskpager-••••••••••••<last4>`), and never written to logs.
- Any incoming `deskpager://pair?topic=…` deep link opens a confirmation dialog naming the PC, and changes nothing until you tap **Add this PC**. Only a code you scan or enter inside the app pairs without that dialog.

## Build & Verify

The UI is Jetpack Compose with Material 3. Besides the Android SDK the app has two library dependencies: Jetpack Compose (Compose BOM, Material 3, `activity-compose`) and Google's code scanner (`play-services-code-scanner`), which needs Google Play Services on the phone; manual code entry is the fallback without it.

Requires JDK 17+ (tested with JDK 21) and Android SDK platform 36:

```powershell
.\gradlew testDebugUnitTest lintRelease assembleRelease
```

The signed release APK is output to `app/build/outputs/apk/release/app-release.apk`. Release signing reads `DESKPAGER_KEYSTORE`, `DESKPAGER_KEYSTORE_PASSWORD`, `DESKPAGER_KEY_ALIAS`, and `DESKPAGER_KEY_PASSWORD` from `~/.gradle/gradle.properties` (falling back to debug signing if unset).
