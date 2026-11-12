# Desk Pager for Android

Shows a buzz on your phone when the [Desk Pager Windows app](https://github.com/bhattaganesh/desk-pager-windows) decides you have been sitting too long.
The two talk through [ntfy.sh](https://ntfy.sh) (free, no account).

## Use it

1. Download `desk-pager.apk` from the latest release and install it. Android will ask you to allow installing from this source.
2. On the PC, open the Desk Pager tray menu → **Pair phone…**.
3. Pair the phone, either way:
   - scan the QR code with the phone's camera and open the link in Desk Pager, or
   - copy the pairing code, send it to yourself, and paste it into the app, then tap **Pair**.
4. Allow notifications when asked, and tap **Allow background use** so buzzes arrive while the phone is idle.
5. On the PC: tray menu → **Send test buzz**. The phone should vibrate within a few seconds.

Needs Android 8.0 or newer.

## How it works

- A foreground service keeps one streaming HTTPS connection open to the paired ntfy topic. That is why a permanent "Listening for buzzes" notification is shown.
- Each message becomes a high-priority notification with vibration. Messages older than 10 minutes are dropped.
- The service restarts after a reboot and reconnects with backoff (5 s up to 60 s) when the network drops.
- It holds a partial wake lock while listening, which costs some battery. Push through Firebase would avoid that but needs a Google account and a backend.

## Privacy and security

- The pairing code is a random 128-bit ntfy topic. Anyone who has it can send buzzes to this phone, so don't share it.
- It is stored in the app's private storage, excluded from backups, and never logged.
- The app only talks to `https://ntfy.sh`. It collects nothing.

## Build

Needs JDK 17+ and the Android SDK (platform 36).

```
./gradlew testDebugUnitTest lintRelease assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk`. Release signing is read from `~/.gradle/gradle.properties`
(`DESKPAGER_KEYSTORE`, `DESKPAGER_KEYSTORE_PASSWORD`, `DESKPAGER_KEY_ALIAS`, `DESKPAGER_KEY_PASSWORD`); without them the
build falls back to the debug key.
