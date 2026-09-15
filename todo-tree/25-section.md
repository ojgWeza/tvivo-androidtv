## QA-11 — Playback connection is not released before the next stream — **IMPLEMENTED, emulator verification pending**
**Severity: High.** After leaving a movie stream and starting Live playback, the panel displayed `Another device may be using this account.` No other device was active. Force-stopping Tvivo released the stale session.

`PlayerActivity` now snapshots the VOD resume position, then detaches, stops and releases
Media3 before writing that snapshot to Room. This removes the suspected teardown gap: the
prior order kept the player alive during a synchronous database write without an explicit stop.
Verify by leaving a movie and immediately starting Live in one approved emulator session.
