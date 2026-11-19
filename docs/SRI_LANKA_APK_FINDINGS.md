# TVS Connect Sri Lanka: local static inspection

Inspected 2026-11-19. Package `com.tvsm.connect.srilanka`, version 5.3.0,
version code 37. Base APK SHA-256:
`109643b1b1045e0878316ee5e24863e9f3480aa3ae4b3c764c60af0a8e4702c6`.
The owner supplied the installed app through their phone. The APK and extracted
code stay in gitignored local research storage. No TVS account, API or network
traffic was used. This document records observations, not vendor source code.

## Owner's scope

The owner clarified on 2026-11-19 that this app is for Raider 125 only.
Investigate Raider behavior and shared code demonstrably relevant to Raider;
other vehicle features are not development targets. Shared vendor class names
are implementation details, not a reason to expand product scope.

## Variant routing follow-up

`BikeVariantsComponentImplement.getBikeVariants()` selects the Ntorq stack for
the internal identifiers U_716, U_577_BASE and U_577_PREMIUM. `getBikeType()`
selects an enum by the stored vehicle ID plus theme. No class explicitly named
Raider was found in the APK class-definition inventory; this does not establish
that Raider is unsupported, because these paths use internal identifiers.

The owner's Raider variant must be associated with one of those identifiers
before choosing its Eco/Power offset. In particular, do not turn the ordinary
byte-8 candidate into a universal Raider mapping: this APK's receiver explicitly
selects byte 11 for U_577_BASE. The duplicated base check in the decompiled
expression also warrants bytecode review before making claims about Premium.

The shared text formatter constructs 20-byte frames with a 0x5B start, a data
ID, up to 17 text bytes, zero padding and a final 0xFF. It sanitizes non-Latin
characters rather than supporting arbitrary script text. This confirms a wire
payload size, not a physical display width or Sinhala/Tamil rendering support.

## Eco / Power: a concrete candidate, not hardware confirmation

In `com.tvs.ble.feature.datareceiver.NtorqClusterDataReceiver`:

- The speedometer handler `q` dispatches `NtorqSpeedOMeter1` into the coroutine
  `d.invokeSuspend`; that coroutine calls `l(receivedBytes)` and passes the
  result with speed and odometer into the mode-statistics routine `w`.
- `l` reads zero-based byte index 8 for the ordinary path. Its U_577_BASE
  special case selects index 11. Exactly 1 maps to Power; all other values map
  to Economy in this vendor implementation.
- The same handler calls `m`, which uses the low nibble of the selected byte:
  0 maps to Street and 1 or 9 to Race. This is a different interpretation from
  the whole-byte Eco/Power helper and must not be silently conflated with it.
- The receiver maintains per-mode time, travelled distance and top-speed
  statistics. Thus the mode split is calculated locally in this path, even
  though the official app also has cloud-backed ride storage.
- Independently, `SampleGattAttributes.ECONOMY_POWER_STATUS` and
  `BluetoothConstants.SPEEDOMETER_MODE` both specify index 8;
  `U577_SPEEDOMETER_MODE` specifies index 11. These corroborate the offsets,
  but not the owner's vehicle variant routing.

These are observations of a shared Ntorq receiver, not proof of the selected
Raider variant's runtime path. Our Raider decoder currently calls byte 8
TRIP_STATUS through the inherited layout. Paired Eco/Power observations and
variant routing confirmation are still required before presenting a definitive
Raider mode. Do not map every unexpected byte to Eco merely because the vendor
helper does so. Unknown data should remain unknown in this app.

## Navigation and text

In `com.tvs.ble.feature.datasender.NtorqMobileToCluster`:

- Custom text line one passes data ID 76 (`0x4C`) to
  `BluetoothUtil.createMobileToClusterArray`; line two passes 99 (`0x63`).
- `sendNavigationData(String)` also passes 76 (`0x4C`). This is a text entry
  point, not evidence that every navigation control frame uses that ID.
- `sendNavigationInstructions(byte[])` is empty in this implementation.
  Finding the method name does not establish a working navigation sender.
- `sendRiderName` passes 82 (`0x52`), corroborating the greeting ID already
  implemented here.
- `sendVehicleMode` is empty here. This receiver/sender pair is not evidence
  for a working phone-side Eco/Power selector.
- Legacy `SampleGattAttributes` retains navigation text IDs 79/80
  (`0x4F`/`0x50`) and navigation control 78 (`0x4E`). Their coexistence with
  the `0x4C` custom-text sender reinforces the need to trace call sites rather
  than declaring one path obsolete from constants alone.

These text methods do not settle physical row width, wrapping, or whether
Raider renders our `0x4F`/`0x50` navigation text path. Those remain separate
hardware questions. Do not replace our working navigation transport based on
a shared method name alone.

## Link and other capabilities

`NtorqBLEConnectionConfig` specifies the known SmartXonnect service and read /
write characteristic UUIDs and characteristic write type 2 (write with response).
This corroborates the current property-aware response-write handling. It does
not prove that every vehicle using the shared configuration has identical
telemetry offsets.

The sender also has caller information, weather/news, profile-image and
calibration-related methods. Their existence does not prove Raider support.
Calibration writes remain out of scope without explicit owner authorization.
No new calibration or vehicle-mode writes were added during this inspection.

## Practical next steps

1. Confirm the Raider variant's receiver selection, then compare paired
   dashboard-labelled Eco/Power captures of `0x10` byte 8.
2. Add a read-only mode indicator with unknown and stale states, plus decoder
   regression fixtures, once the mapping is supported.
3. Reuse odometer deltas and elapsed time for per-mode ride summaries only
   after mode detection is reliable; handle gaps and resets explicitly.
4. Trace navigation builders and consumers before proposing any packet changes.

Static inspection was targeted, not a complete audit. Decompiled methods can
contain reconstruction warnings; disputed byte-level behavior needs bytecode
or hardware corroboration.


## Integration

The app now records both mode candidates in the opt-in marked diagnostic CSV,
with raw bytes and an explicit UNVERIFIED mapping label. Only valid primary
telemetry is used; values other than 0 and 1 remain UNKNOWN. Neither candidate
changes the dashboard mode or accumulates per-mode ride statistics. Manual
ECO/POWER labels remain observations for comparison, not a bike-control action.

The misleading Home Ride Mode switch now controls the actual resumeOnBoot
preference, shared with More and BootReceiver, and is labelled Reconnect after
reboot. It does not change the bike's operating mode.
