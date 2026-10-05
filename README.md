# Ring Health Bridge

An unofficial local Health Connect bridge for Oura Ring Gen 3.

Ring Health Bridge is an experimental Android app that reads an Oura Gen 3
Horizon ring directly over Bluetooth Low Energy and writes a deliberately small
set of validated records to Google Health Connect. The app is local-only: it has
no `INTERNET` permission, cloud service, telemetry, or Oura account requirement.
An Oura subscription is not required.

Ring Health Bridge is an independent, unofficial open-source interoperability
project. It is not affiliated with, sponsored by, endorsed by, or supported by
Oura Health Oy. OURA and OURA RING are trademarks of Oura Health Oy.
It is also not affiliated with or endorsed by Google, Samsung, or Fitbit,
and it is not a medical device.

## Supported behavior

The publication path currently supports:

- heart rate;
- RMSSD heart-rate variability; and
- completed sleep sessions (start and end time only).

Gen 3 Horizon is the only physically validated ring model. Other generations,
firmware versions, phones, and Health Connect implementations are unsupported.
Ring-derived steps, SpO2, sleep stages, sleep scores, activity/readiness scores,
temperature, respiratory rate, active calories, and other research metrics are
**not published**. Some of those types appear only in read-only, local research
tools and must not be treated as validated measurements.

## Important pairing consequence

Direct operation requires an application authentication key installed on a
factory-reset ring. Factory reset erases data held on the ring and establishes an
exclusive app-authentication relationship. Do not reset a ring until its wanted
data is synchronized elsewhere and you accept losing the official app connection.

To return to the official Oura app, factory-reset the ring again and complete the
official app's normal pairing flow. That reset again erases data still held only on
the ring. See [`desktop/README.md`](desktop/README.md) before provisioning.

Never post a ring key, raw BLE capture, health database, device address, serial
number, or diagnostic export in an issue.

## Privacy and permissions

All raw history, keys, preferences, and diagnostics remain in app-private storage.
Android backup and device-to-device transfer rules exclude the complete app data
tree. Release builds cannot use the developer ADB key-import bridge.

The app requests Bluetooth/companion-device and foreground-service permissions.
For publication it requests Health Connect read/write access only for heart rate,
heart-rate variability, and sleep. Optional research screens may request read-only
access to resting heart rate, skin temperature, oxygen saturation, respiratory
rate, steps, and historical data. No step write permission is declared or used.

## Build and install

Requirements:

- Android Studio Quail 3 (2026.1.3) or newer, or JDK 17+;
- Android SDK Platform 37; and
- the Android SDK Platform Tools for developer provisioning.

From `android/`:

```text
./gradlew test lint assembleDebug
```

On Windows use `gradlew.bat`. Install the generated debug APK with Android Studio
or `adb install`. Build output is redirected to
`%LOCALAPPDATA%/OuraHealthBridge/android-build` on Windows; on other platforms the
`LOCALAPPDATA` variable must be set or the build configuration must be adjusted.

The public source does not contain a ring key. The optional Windows provisioning
helpers require a separate checkout of the pinned `open_oura` revision described
in [`desktop/UPSTREAM.md`](desktop/UPSTREAM.md). The ADB staging path exists only in
debug builds; release builds always return `DISABLED`.

## Limitations and battery impact

- BLE history sync can take several minutes and can increase ring and phone battery
  use, especially after long gaps.
- Optional periodic work is inexact and subject to Android Doze; installation alone
  does not enable it.
- Publication is conservative and may defer incomplete or recently ending sleep.
- The REAL_STEPS experiment changes an on-ring feature mode. It requires explicit
  confirmation, preserves a rollback target, leaves subscription state unchanged,
  and never publishes steps. It remains experimental.
- No signed or supported release APK is distributed from this candidate.

## Development

See [`android/README.md`](android/README.md), [`CONTRIBUTING.md`](CONTRIBUTING.md),
[`SECURITY.md`](SECURITY.md), and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
The project is licensed under the MIT License.

The launcher artwork was created with AI assistance and retained as an original
project asset. The earlier design artifact carried OpenAI/C2PA provenance; that
binary source artifact is intentionally not included in this snapshot.
