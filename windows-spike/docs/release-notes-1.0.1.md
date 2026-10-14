# Tvivo for Windows 11 — 1.0.1 (2026-10-15)

MSI: `Tvivo-1.0.1-x64.msi` (per-user, Windows 11 only, unsigned). Built with `installer\build-msi.ps1`
(no `-Win10`). Adds a desktop shortcut next to the Start Menu one.

## What changed since 1.0.0

**Playback**
- Playback attempts setting (Account → Player, default 5): transient failures (timeout, "not found")
  are retried on the same engine with 0.5 / 1 / 2 / 3 s pauses; the other engine then gets its own fresh budget.
  A 403 (connection limit) is never retried. A confirmed 404/410 gives up within about 8–10 s.
- The provider's first-request 404 is treated as retryable (an identical second request usually succeeds).
- Mid-playback recovery: stalls, errors and unexpected ends reconnect automatically (resumes about 2 s before
  the last good position; Live reconnects without seeking), using the same attempts budget. Recovery follows
  what the user asked for: pause, seek, stop and Back never trigger it.
- After the last try the player never leaves on its own: a one-line status and a Retry button appear beside the
  controls; Back is already there. No message box. Autoplay-next and shuffle do not skip a failed item.
- Starting any playback clears the previous picture at once and shows a popcorn "Loading…" image until the
  first real picture. See `player-invariants.md` (rules + regression checklist).
- LibVLC needs its video area visible and loaded (under the black curtain) before it opens; readiness is polled.

**Full screen**
- The pointer and controls hide after ~3 s without real mouse movement. Hiding uses a tiny invisible window
  under the pointer; movement, click or wheel brings everything back.

**Catalog**
- Titles lose leading separators (`: Title`), without touching titles like "(500) Days" or ".hack".
- The status label shows the real last-refresh time instead of "Updated just now".
- Spotlight favours new, unwatched items from categories the user has not opened lately (not the two most
  visited), and is stable for the day. Logged as `spotlight.selection`.
- For You cards play with a single click (missing stream addresses are looked up first).

**Diagnostics**: Copy diagnostics is filtered; titles are recorded only when a playback fails.

## Known open items
- Real-eye confirmation of the full-screen pointer on the installed build.
- Not re-run on the final build: failure screen, diagnostics privacy, mini-player, other playback engine
  (Native), spotlight stability across relaunches. All passed in earlier rounds.
- Real stream-drop recovery is covered by unit tests and one real provider drop; no safe local fixture yet
  (see `playback-interruption-verification.md`).
- Watched-count label after seeking into the credits; light-theme contrast of the loading skeleton and the
  new status line; player menus in both themes.
- The mini-player Close button's screen-reader name is "Return to player" (should say it closes the mini player).
- Installer is unsigned (Windows shows an unknown-publisher warning) and needs the x64 Visual C++ runtime.
- Opus test plan for the player: `test-plan-player-round.md`.
