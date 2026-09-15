## Phase 5 — Hardening
RTL verification, empty/loading/error states, `RefreshWorker`, on-device
diagnostic log. Physical-TV validation happens once here — the live `.ts` path
carries the most exposure under that choice.

**Manual refresh is done**, at both scopes: per-category in the browse header,
and `Refresh everything` on the Account screen (every category plus both
catalogs, TTL ignored).
