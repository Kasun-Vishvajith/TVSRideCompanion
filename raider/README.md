# Raider

A separate, offline Android application for Raider 125. Package
`dev.raider.companion`; installs beside the original app. This is a new native
Views/Java application with no runtime dependencies, map SDK, database, Compose,
network permission, model selector or other vehicle driver. Requires Android 8+.

## Screens

- **Overview:** connect/stop, live speed, gear, RPM, odometer, navigation preview.
  Missing, disconnected and ten-second-old frame groups show `—`.
- **Settings:** rider name, Android's notification-access Settings page,
  navigation toggle, view/export rotating logs, select a different Raider.
- **Testing**, under Settings: establish a live baseline, start a 15-second
  capture, press a bike button, review unexplained byte transitions. Known RPM,
  gear/neutral, measurement/counter offsets and checksums are excluded. A press
  returning to its baseline is retained. Answer yes/no/unsure, add a note, save,
  reopen, delete or export observations. Up to 100 private observation files.

The tester is receive-only. It sends no calibration or experimental commands.
Candidate fields can change naturally; correlation does not establish meaning.
`5A12` is compared by shape only and labeled unverified. Only `5A10`, `5A11`,
`5A18`, `5A19` and `5A12` payload bytes enter the in-memory tester. Startup,
identity, credentials and text packet payloads never enter it.

## Runtime

The first frame draws without initializing Bluetooth, contacts or maps. Profile
loading, BLE and disk I/O use a single background worker. A remembered Raider
reconnects asynchronously after permission grants. Otherwise tap Connect to
scan for 20 seconds, without requiring an advertised service UUID. TVSBT names
can belong to other models: choose your own Raider; sharing the GATT service
cannot establish a vehicle model automatically.

A connected-device foreground service owns the ride, serialized GATT writes,
2-second presence, bounded retries and ten-second per-group data expiry.
Closing the activity leaves an explicitly started ride active. **Stop ride**,
including its notification action, closes GATT and cancels ride timers/retries.
There is no boot receiver, automatic background scan or wake lock. Screen-off
reliability and battery use need device measurement.

Notification access is an Android special-access toggle, opened from Settings.
Optional notification-posting denial does not block Bluetooth. The listener
accepts navigation-category notifications only from Google Maps and Waze.
This initial version mirrors title/text into the two candidate text packets,
with a straight placeholder pictogram and no inferred turn distances. It does
not yet implement icon parsing, localized maneuvers, caller ID, SMS or phone
control. Removal/repost has a 2.5-second continuity window and guidance expires
after 60 seconds without updates. Notification text is visible only in memory;
logs store packet metadata, never navigation text or device addresses.

Logs rotate across three files of approximately 2 MB, with a 4,000-line memory
ring and per-frame metadata sampling/suppression counts. Observation notes are
user-authored and may contain personal information; exports use Android's
local document picker. App backup is disabled.

## CI only

Do not run Gradle locally. The independent Gradle root is `raider/`, invoked
using the repository wrapper by `.github/workflows/raider.yml`. Every relevant
push/PR runs JVM tests, release lint, debug/optimized builds and permission
assertions. Download **raider-optimized** for ordinary use, **raider-debug** for
`run-as` diagnostics, and **raider-checks** for reports. The workflow reuses the
existing project signing secrets when available; without them, ephemeral CI
keys cannot reliably upgrade a previous installation. Both variants share the
same package, so install one variant at a time.

The optimized APK enables R8 and resource shrinking. No startup time, battery
saving or flicker fix is claimed: benchmark the signed optimized APK on the
actual phone. The existing flicker investigation remains separate.

## Evidence and attribution

This implementation uses the repository's blueprint, recorded Raider-specific
byte fixtures and existing protocol findings, under the repository's
[CC BY-NC 4.0 license](../LICENSE). It includes no vendor source, APK, private
capture or real device identifier. Arithmetic matches established big-endian
framing, single odometer conversion and RPM bytes 16–17. Gear additionally
requires agreement with the neutral flag. Candidate mappings and cluster text
rendering still require vehicle verification.

Android API references used:
- https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data
- https://developer.android.com/reference/android/service/notification/NotificationListenerService
- https://developer.android.com/develop/background-work/services/fgs/service-types
