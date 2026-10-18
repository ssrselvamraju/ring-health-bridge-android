# Android application

The Android client is a Kotlin/Compose application targeting API 37 with a minimum
of API 34. It performs direct BLE framing/authentication, stores bounded raw history
in an app-private SQLite database, derives conservative record candidates, and
publishes only validated heart rate, RMSSD HRV, and completed sleep sessions.

## Toolchain

- JDK 17 or newer
- Android SDK Platform 37.0
- AGP 9.4.1, Kotlin Compose plugin 2.3.21, and Gradle 9.8.0 (pinned)

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

## Guided setup and verification boundary

Settings exposes resumable desktop-assisted setup. The document picker reads at
most 129 bytes and accepts only an ASCII credential with exactly 32 hexadecimal
characters within a 128-byte file. Input, compact parsing, and parsed-key byte
buffers are cleared after use, including failure paths; this is best-effort
memory hygiene, not a guarantee that all runtime/provider copies are erased.
No persisted URI grant or broad storage permission is used. Imported keys are
wrapped with Android Keystore. Known-working credentials are protected from
replacement; an explicit safe reconfiguration flow is not implemented.

Setup is derived from stored credential state, companion association/bond,
authenticated exchange, Health Connect permissions and verified first sync.
Reset warnings are not persisted as permission for a later reset, and the app
does not issue reset commands. Android-only provisioning remains unvalidated.

The new share/save setup-status report deliberately excludes raw/free-text
diagnostics, health values, research results, timestamps and device identifiers.
Sleep-detail diagnostic clipboard actions are disabled in this public candidate.
External document providers/share destinations can transmit the user's report.

Before an APK release, physically verify fresh installation, malformed/oversized
credential files, picker cancellation, failed authentication/reselection,
Bluetooth-off, denied/revoked permissions, process recreation and first sync.
Compare bounded reconciliation against the previous full-history publication IDs
and exact read-back, including older backlog, and verify trial-trigger exclusion.
Bounded reconstruction is debug-only in this candidate; release retains the prior
full-history publication path pending that parity evidence.
Do not infer physical parity from JVM tests or successful assembly.
