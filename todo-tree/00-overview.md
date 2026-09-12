# TODOs

Open work, with enough context to pick up cold. Items here were considered and
consciously deferred, or found by testing and not yet fixed — not a backlog of
ideas. The one exception is **Part 2c**, filed 2026-09-10: gaps that were never
work items, ordered by cost against what they unblock.

Everything that was *not* deferred is folded into `docs/ui-scope.md`,
`docs/architecture.md` and `docs/decisions.md`.

**Status as of 2026-09-08:** Phases 0-4 are built and running against the real
panel on the API 34 Android TV emulator, plus an Account screen that was not in
the original plan. **145 unit tests pass.** VOD (48,761 rows), live (6,425
channels) and series (13,264 shows) all sync in full; movie playback works end to
end and the season/episode picker was driven on-emulator.

**Updated 2026-09-10, end of session.** Q-15..Q-27 are all fixed; everything except
Q-24 and Q-27 was driven on the API 34 emulator across Movies, Live and Series.
**149 unit tests pass.**

Landed this session beyond the defects: the three virtual rail folders
(`RECENTLY ADDED` 100, `CONTINUE WATCHING` 50, `FAVOURITES`), the pre-run detail page
that replaced activate-to-play, `get_vod_info` for movie descriptions, and a real
Room migration in place of `fallbackToDestructiveMigration` — which would have dropped
the two tables the new folders are built from.

**Updated 2026-09-10, second session (QA sweep + fixes).** A full report-only sweep
found 14 defects; those plus a 14-item list from the user were fixed in one pass and
re-driven on the emulator. **149 unit tests still pass** and the DB moved to **v5**
(`rating` on `vod_streams` and `series`) over a real migration that preserved the
three resume rows and one favourite on the device.

Closed and verified on-emulator this session: **Q-22** (see its entry — the earlier
"not fixed" call was wrong), **U-1..U-13** below, and **QA-1** (live pre-run page used
the 2:3 poster frame) and **QA-2** (episode-picker `Refresh` was orange text).

Still genuinely open: **Q-4 and Q-5** (both need an open stream, and `max_connections`
is 1 — they land with the physical-TV session), **Q-8** (movie title block eats
vertical space), **Q-14** (sideloaded APK crashes on launch on a phone, still no
logcat), **U-14** (player seek — written but unverifiable without a stream) and
**QA-3..QA-7** (the visual findings below). Q-11's inset bug is fixed and verified.
Phase 5 (hardening) is in progress.

**Credentials were re-entered by hand on 2026-09-08** after the Phase 4 session
cleared app data and destroyed the previous set. Do not clear app data.

**To QA the login screen, use Account → "Sign in to a different account" and
enter a deliberately wrong account.** That path does not wipe anything: it routes
to Login and keeps the current credentials until a new sign-in is *accepted*.
Only the separate `Sign out` button calls `store.wipe()`.

