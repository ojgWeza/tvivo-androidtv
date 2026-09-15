# Project Learnings

Durable findings from implementation and device validation. These are operational
rules for future work, not a replacement for `AGENTS.md`, `CLAUDE.md`, or the
design decisions.

## Handset Home activation is not the TV Card activation path

**Observed:** On the Xiaomi Mi 10, pointer press and release reached a Home tile,
but `androidx.tv.material3.Card` did not invoke its click callback. The route did
not change, even though the tile was enabled and exposed as clickable.

**Rule:** Keep `Card` as the Android TV D-pad activation owner. On non-TV devices,
use the handset `detectTapGestures` path in `ui/home/HomeScreen.kt` and dispatch
its completed tap through the same `activate` / `onSelect` callback chain. Do not
generalize a handset touch workaround to TV input without device evidence.

**Validation chain:** `touch received (press)` → `touch received (release)` →
`tile activated` → `onSelect invoked` → `route requested` → `route state mutated`
→ `destination composed`.

## Pointer events are evidence of input delivery, not navigation

**Observed:** The original Home issue produced pointer press/release logs but no
post-click route events.

**Rule:** Diagnose interactive navigation end to end. Log and test the callback,
selected content type, requested route, state mutation, and destination
composition. Do not attribute a no-navigation report to orientation or coordinate
mapping without identifying the first missing event.

## Diagnostics need a schema and a redaction boundary

**Observed:** Event messages are only useful after a device-only problem when the
log is persistent, exportable, ordered, and safe to share.

**Rule:** `DiagnosticLog` entries use `timestamp`, `severity`, `screen`, `event`,
and `payload`. Keep the log bounded and persisted. Enforce redaction in the logger,
not only at call sites: passwords, usernames, tokens, authorization values, URLs,
server hosts, and provider data must never reach Logcat or exports.

## Green builds are not handset validation

**Observed:** Unit tests and a debug build validated route mappings and diagnostic
serialization, while the actual Home defect required a Mi 10 to expose it.

**Rule:** Treat successful compilation and unit tests as necessary but insufficient
for touch, IME, focus, orientation, and visual destination changes. Run approved
instrumented/device validation after the safe checks, without opening streams and
without clearing app data.

## Preserve credentials while testing Login

**Rule:** Never clear app data to reach Login. Use Account → Sign in to a different
account, which retains the current credentials until a new account is accepted.
This is also the safe route for handset IME validation on an authenticated device.
