## QA-11 — Playback connection is not released before the next stream — **CONFIRMED on emulator 2026-09-12**
**Severity: High.** After leaving a movie stream and starting Live playback, the panel displayed `Another device may be using this account.` No other device was active. Force-stopping Tvivo released the stale session. This is a user-visible false account-conflict failure and needs an explicit player-teardown check.

