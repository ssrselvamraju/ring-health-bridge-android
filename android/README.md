# Android application

The Android client is a Kotlin/Compose application targeting API 37 with a minimum
of API 34. It performs direct BLE framing/authentication, stores bounded raw history
in an app-private SQLite database, derives conservative record candidates, and
publishes only validated heart rate, RMSSD HRV, and completed sleep sessions.

## Toolchain

- JDK 17 or newer
- Android SDK Platform 37
- AGP 9.2.0, Kotlin Compose plugin 2.3.21, and Gradle 9.4.1 (pinned)

Set `LOCALAPPDATA` to a writable local directory when building outside Windows;
generated data is intentionally kept outside a cloud-synchronized checkout.

```text
cd android
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew assembleRelease
```

The Gradle wrapper distribution has a pinned SHA-256 checksum. Release assembly
uses the default unsigned release behavior; this project does not provide signing
material.

## Security model

- No `INTERNET` permission is declared.
- `android:allowBackup="false"` is retained, and extraction rules exclude all
  credential-, database-, preference-, file-, and device-transfer domains.
- Ring key material is encrypted with an Android Keystore key.
- The ADB-staged import implementation exists only in `src/debug`; `src/release`
  always disables it.
- Raw history and diagnostics stay in app-private storage and are not logged.
- The only exported components are Android-required entry points. The companion
  service and permission-usage alias are protected by platform permissions.

Do not add real health data, device identifiers, ring keys, captures, databases,
or signing material to source code or fixtures.
