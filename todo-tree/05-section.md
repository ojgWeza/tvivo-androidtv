## Q-14 — Sideloaded APK crashes on launch on a phone
**Severity: Unknown until triaged. Reported 2026-09-08. Reproducible — the user has
tried this several times and it crashes on every launch. Still not investigated, because
no logcat has been captured from the phone.**

The debug APK installs on an Android **phone**, and crashes immediately on
"Open" — every attempt, not intermittently. No logcat captured yet, so there is no root
cause here, only the report. **Getting the trace is the blocker; nothing else about this
is worth guessing at until it exists.**

**First step is a stack trace, not a fix:** `adb logcat -c` before launching,
then `adb logcat -d AndroidRuntime:E *:S` right after the crash. Everything below
is a candidate list to check the trace against, not a diagnosis.

- **Updated 2026-09-12:** `MainActivity` now has both `LEANBACK_LAUNCHER` and
  `LAUNCHER` intent filters, and `android.software.leanback` is optional. A
  sideloaded build therefore has a normal phone-launcher entry; this removes one
  unsupported-entry-path variable but does not explain a crash without its trace.
- `androidx.tv.material3` is a TV surface library; nothing guarantees it behaves
  on a handset form factor.
- Layouts are built to a fixed 1920x1080 10-foot geometry (`docs/ui-scope.md`),
  so a phone is outside every measurement in the design.

**Cross-project lesson (PrayerQiblaApp, confirmed on a Xiaomi Mi 10):** its
apparently similar post-install crash was `Failed to create an instance of
androidx.work.impl.WorkDatabase` inside
`androidx.startup.InitializationProvider`; release R8 shrinking had broken a
reflection-dependent WorkManager/Room path before Flutter started. Tvivo already
sets `isMinifyEnabled = false`, so this is evidence for what to inspect in the
trace, not evidence that Q-14 has the same cause. Tvivo now also sets
`isShrinkResources = false` explicitly and initializes WorkManager only after
the sanitized crash handler is installed, so a comparable initialization
failure can survive into on-device Diagnostics on the next launch.

**Phone is not a target.** `PRODUCT.md` scopes this to Android TV, so the
outcome may legitimately be "the phone is unsupported, and the failure should be
a clear message rather than a crash" — but that is a decision to make *after*
reading the trace, not instead of reading it.

---

