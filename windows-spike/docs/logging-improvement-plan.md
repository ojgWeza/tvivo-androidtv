# Logging improvement plan (learned from the Sentry trial, 2026-10-20)

Goal: when something goes wrong on a senior's PC, one look at the log (or the support zip, or Sentry in Debug)
tells us WHAT failed, WHERE, HOW OFTEN, and WHAT LED UP TO IT, without ever exposing credentials, hosts, URLs or titles.

## What the Sentry trial showed (one 45 s run)
- Grouping by event name turned hundreds of repeats into 3 readable issues with counts. The flat log cannot do that.
- Breadcrumbs (the last ~100 lines before a problem) gave context we normally have to scroll for.
- Weak spots: titles carried timestamps/numbers; "problem" vs "info" was guessed from the event name; no stable
  error codes; one real defect surfaced immediately (artwork InvalidDataException) that the log buried in noise.

## Plan
### L1 - Structure (cheap, high value)
1. One schema for every line: `event=<area.name> level=<info|warn|error> code=<TVV-xxxx> key=value ...`.
   Levels become explicit instead of guessed; Sentry maps them 1:1.
2. Stable error codes for known failures (catalog auth, 403 cooldown, close timeout, artwork decode, DB busy...) in a
   Core enum `DiagnosticCode`, documented in docs/diagnostics-for-support.md so support can say "TVV-2103".
3. Every line carries `session`, app `version`, and a short `trace` id when it belongs to one user action
   (open title, refresh, close). Ties the Sentry trail to the support zip.

### L2 - Noise control
4. Sampling/rate limits per event name: `perf.*`, `artwork.*`, `playback.vlc.warning` max N per minute, then one
   summary line "suppressed K". Keeps the 1 MiB rotation from losing the interesting hour.
5. De-duplicate identical consecutive lines (count instead of repeat).
6. Move high-volume perf samples to a separate ring buffer file so `tvivo-launch.log` stays about behaviour.

### L3 - Context on problems
7. Attach a compact state snapshot to every error: page, player mode, catalog size bucket, sleep timer on/off,
   theme, engine, free-RAM bucket. No titles, ids or hosts (redactor stays the single choke point).
8. Keep the last 50 lines as the in-memory breadcrumb trail and write them as one block when an error is raised
   (works without Sentry, goes into the support zip).
9. Exceptions: type, HResult, first app stack frame only; message stays omitted (existing privacy rule).

### L4 - Sentry (Debug only, opt-in via `TVIVO_SENTRY_DSN`; Release/MSI never reference it)
10. Fingerprint by `code`, title = code + event name only (no timestamps).
11. Tag release/version, engine, theme, page; mark `perf.stall` over 1 s as warning, over 3 s as error.
12. Sessions on, so crash-free rate per build is visible.
13. Never send anything that is not already redacted; add a unit test that feeds the adversarial redactor corpus
    through `SentryBridge.OnLog` formatting.

### L5 - Make it useful in daily work
14. `docs/diagnostics-for-support.md`: error-code table with plain-language meaning and first fix to try.
15. "Save diagnostics for support" zip includes the trail block, code summary (counts per code) and version.
16. Overnight/QA runs read the code summary instead of grepping.

## Order and cost
L1 (items 1-3) and L4 (10-11) first - one Sol slice, Core tests for schema/codes, no UI change.
L2 next (sampling is pure logic, easy to unit-test). L3 and L5 after that. Nothing here changes behaviour
visible to the user; each slice is tested with fakes and verified once in Sentry.

## Open points
- Do we ever want opt-in Sentry for real users? Not in this plan; needs an explicit privacy decision from the owner.
- First real defect to look at from the trial: artwork `InvalidDataException` (TVIVO-WINUI-3).
