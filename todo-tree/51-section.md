## N-11 — Diagnostic log verbosity on production
**Cost: small. Production-facing decision, not code.**

2026-09-12 refactored `DiagnosticLog` with a structured schema and added call
sites at every navigation/sync/failure point. The log is now queryable and safe
to export (redaction is enforced in the logger, not at call sites). On the
emulator, a healthy 45-minute session produces only four lines (app start + three
TTL skips). That is correct and expected.

On a real TV, the log is persistent and bounded (200 entries); a longer session
or one with background sync + user navigation may fill it faster. Measure actual
verbosity on the physical TV during Phase 5 validation. If routine events (e.g.,
category selection, image loading, non-error sync progress) are worth recording,
decide that explicitly as part of T-D4 (Decide whether Diagnostics should record
routine events) rather than retroactively adding call sites.

**Current state:** bounded, structured, and safe; validate density on hardware.

