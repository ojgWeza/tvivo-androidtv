## Q-4 — Player has no visible way back
**Severity: Medium. Fixed in code (`a407bad`); still unverified on-emulator.**
`ui/player/PlayerActivity.kt`

Verifying the hint means opening a stream, and `max_connections` is 1, so this
stays code-reviewed only until the Phase 5 physical-TV session.

Back *works* (verified: `KEYCODE_BACK` moves `PlayerActivity` → `MainActivity`).
The defect is discoverability — nothing on screen communicated how to leave, so
the exit depended on the user already knowing about the remote's Back button.
A corner hint now rides the controller's visibility.

Distinct from Q-2: the mechanism is fine here, the affordance is missing.

