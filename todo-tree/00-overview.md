# TODOs

Open work, with enough context to pick up cold. `01-open.md` is the index of
everything still open; read it first, then load only the section file the
current task needs. Closed work and its lessons live in `docs/decisions.md`
("Archived todo-tree closures") and `docs/ui-scope.md` — this tree holds open
items only, no dates, no session narrative. Maintenance rules:
`D:\HCode\bible-detail\todo-and-bible-maintenance.md`.

**Current state:** Android TV — Phases 0-4 built and running against the real
panel on the API 34 emulator; 149+ unit tests pass; VOD/live/series sync in
full; movie playback works end to end. Phase 5 (hardening) is in progress.
Desktop — active product target (not a POC) per `handoff.md`; foundation and
catalog/player shell exist, D-Desktop-1..10 is the current open batch and
takes priority over the Android list.

**Credentials were re-entered by hand on 2026-09-08** after an earlier session
cleared app data and destroyed the previous set. Never clear app data — use
Account → "Sign in to a different account" to reach Login without wiping
anything; only `Sign out` calls `store.wipe()`.
