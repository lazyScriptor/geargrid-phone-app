# GearGrid Phone Assistant (`geargrid-phone-app`)

Android Capacitor shell (`minSdk 29`, `appId: live.geargrid.phone`) wrapping `https://app.geargrid.live/device` with the native Kotlin `PhoneAssistant` plugin (`EncryptedSharedPreferences`, Room local cache, and background `ContactSyncWorker`).

## Prerequisites

- Node.js 22+ and npm
- Android SDK Platform 35 + Build-Tools 35 + JDK 17 (`ANDROID_HOME` set)
- Firebase configuration (`google-services.json`, supplied by the product owner — **never commit this file**)

## Setup & Build

1. Install Capacitor dependencies and sync Android:
   ```bash
   cd geargrid-phone-app
   npm install
   npx cap sync android
   ```
2. Place `google-services.json` at:
   ```
   geargrid-phone-app/android/app/google-services.json
   ```
3. (Optional for signed release APKs) Create `geargrid-phone-app/android/keystore.properties` (git-ignored):
   ```properties
   storeFile=/absolute/path/to/geargrid-release.jks
   storePassword=YOUR_STORE_PASSWORD
   keyAlias=geargrid
   keyPassword=YOUR_KEY_PASSWORD
   ```
4. Build the release APK:
   ```bash
   cd android
   ./gradlew assembleRelease
   ```
   Output APK: `android/app/build/outputs/apk/release/app-release.apk`.

## Install on Device

```bash
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

## Pairing & Contact Sync

1. On the GearGrid POS web app: open **Profile → Signature → Pair a phone** to mint a short code (`ABC-XYZ`).
2. Open **GearGrid Phone** on the Android device and enter the code.
3. On the Setup Checklist screen, complete each permission (`Caller-ID`, `Display over other apps`, `Notifications`, `Call log`, `Battery exemption`).
4. Toggle **Contact sync** on when ready and accept the privacy consent notice. Only contact names and phone numbers are uploaded (no photos, emails, addresses, or notes). Turning contact sync off or unpairing deletes the phone's synced contacts from GearGrid.

## Samsung One UI & Vivo Funtouch/OriginOS Battery Setup

- **Samsung One UI:** App info → Battery → **Unrestricted**, and remove GearGrid Phone from **Sleeping apps** / **Deep sleeping apps**.
- **Vivo Funtouch / OriginOS:** iManager / Security → Permissions → **Autostart** ON, and Battery → **High background power consumption** ALLOWED so WorkManager sync and background listeners stay alive after swiping away the app.
