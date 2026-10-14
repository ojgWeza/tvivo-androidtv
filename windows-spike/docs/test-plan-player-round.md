# Test plan: player round (Haiku tester)

Written 2026-10-15 from source at branch `codex/winui-catalog-design-plan` (head af1f041 + local changes).
Covers: player reset / old-picture invariant (`docs/player-invariants.md`), final-failure screen,
cinema pointer, pause, For You, Copy diagnostics, mini-player.

## 0. Ground rules (read twice)

- You do NOT build, edit source, edit settings files, or delete anything. Never delete result files or logs.
- You do NOT improvise. If a step cannot be done exactly as written: record SKIPPED + why, go to the next check.
  Do not invent a substitute check.
- Software input only (SendInput / mouse_event / UI Automation). Before EVERY input burst, confirm the
  foreground window belongs to `Tvivo.App`. If it does not: stop input, record it, wait for orchestrator.
- The owner's terminal must be closed (her typing steals focus). If you see it open, ask the orchestrator first.
- Never touch, move, close or type into any other window.
- One provider stream at a time, each as short as the check needs (max ~60 s, except C's 45 s wait).
- Dark theme for the whole round. Audio muted for the whole round, and restored at the end.
- Close Tvivo only via `Process.CloseMainWindow()`. Wait up to 30 s. If it does not exit: screenshot, record
  `Responding`, report HANG with the last 20 log lines. Do not kill it unless the orchestrator says so.
- After every close: no `Tvivo.App` process and no helper you started (PowerShell jobs, timers) left running.

## 1. Paths and facts

- App (use only this): `D:\HCode\Tvivo\windows-spike\src\Tvivo.App\bin\x64\<Debug|Release>\net9.0-windows10.0.22621.0\win-x64\Tvivo.App.exe`.
  Orchestrator says Debug or Release. Never launch a `19041` build (stale UI).
- Log: `%LOCALAPPDATA%\Tvivo\tvivo-launch.log` (rolls to `tvivo-launch.log.1` at 1 MB; read both).
  Every line has `session=<id>`. Take the session id from the `event=app.start` line of the launch you made and
  only count lines with that id.
- Screenshots: `D:\Scratch\player-round-<yyyyMMdd-HHmm>\<checkid>-<n>.png`. Create the folder; never reuse an old one.
- Engine switch: Account page -> Player tab -> "Playback engine" combo ("Native player" / "LibVLC").
  "Playback attempts" combo on the same tab sets N for retries. Note N before section C.
- Player controls (UI Automation names): "Pause", "Cinema mode", "Mini player", "Back to catalog",
  "Copy diagnostics". Retry button = `RetryPlaybackButton`; status text = `StatusText` in `PlaybackStatusPanel`.
- Mini player works only on the LibVLC engine (the button does nothing on Native).
- Failing Live item: the orchestrator must name one Live channel known to return 404. Do not hunt for one.
  If none is named, B and C are SKIPPED.

## 2. Log events and what each proves

| Line (prefix `event=`) | Proves |
|---|---|
| `app.start version=.. config=.. engine=.. theme=..` | which build/engine/theme this launch used |
| `spotlight.selection mode=.. utcDay=.. orderedIdsHash=.. selectedIdHash=..` | which spotlight item was chosen (hashed, no titles) |
| `content-selected outcome=started kind=..` / `outcome=opened-series` / `outcome=resolved` | a card click reached the player (or series page) |
| `player.reset reason=<NewItem/Retry/EngineFallback/AutoplayNext/FreshStart> generation=G surfaceCleared=true` | old picture was hidden and curtain raised BEFORE the new item opened. `surfaceCleared=false` = FAIL |
| `playback.start.ref kind=.. engine=.. itemRef=<8 hex>` | which engine opened which (hashed) item |
| `playback.retry attempt=a of N reason=..` | an automatic retry; `a` must never exceed N |
| `player.curtain-lowered generation=G` | first frame arrived for generation G; picture now visible |
| `playback.first_progress generation=G positionMs=..` | position really advanced (not proof of a visible frame) |
| `playback.final-failure stay=true reason=..` + `player.final-failure reveal=false` | gave up, stays on player, old surface NOT re-shown |
| `playback.interrupt reason=.. positionMs=..` | a stall/unexpected end/error was detected during playback |
| `playback.resume attempt=..` / `playback.resume.ok` / `playback.resume.failed` | recovery tries and outcome |
| `cinema.hide` | idle hide of controls + pointer in cinema |
| `cinema.cursor-set hidden=true/false` | pointer hide requested / undone |
| `cinema.cursor-popup hwnd=.. isPopup=true|false ..` | hit-target popup exists; `isPopup=true` = it sits under the pointer |
| `cinema.cursor-popup-failed stage=..` | popup could not be made: FAIL for D |
| `cinema.cursor-popup-destroyed reason=pointer-move|pointer-poll|click|wheel` | why the pointer woke |
| `cinema.show reason=..` | controls shown again, with the reason |
| `window-mini.enter` / `window-mini.exit` / `window-mini.close-teardown` | mini-player window mode transitions |

Rule: a FAIL on a log line needs the exact line copied into the results. A PASS needs at least one line too.

## 3. Pre-checks (all must pass, else stop the whole round)

P1 No `Tvivo.App` process running. P2 Power: `Get-WinEvent System` Kernel-Power Id 105 count in the last 10 min
<= 2, and battery status = AC (Win32_Battery). Record both. P3 Free RAM (`Win32_OperatingSystem.FreePhysicalMemory`)
>= 2500 MB; record value. P4 Record current system mute state; mute it. If you cannot read the mute state, do not
toggle blindly: instead mute with Tvivo's own volume control at every playback, and record that you did.
P5 Record log size/last line before launch. P6 Owner terminal closed (ask if unsure).
Repeat P2 and P3 before sections B, D and F; if one fails mid-round, stop and report.

## A. First start / basics

A1 Launch. Precondition: P1-P6. Action: start exe, wait for catalog (max 30 s). Expected: window shows catalog,
no error dialog. Evidence: `app.start` line (config, engine, theme), screenshot A1-1. If theme=Light: click the
sun/moon toggle once to go dark, record that you did (restore at the end). PASS: catalog visible within 30 s.
A2 Movies tab. Action: click "Movies". Wait 10 s. Screenshot A1-2. Evidence: the `spotlight.selection mode=..` line
for Movies. PASS: grid/shelves and spotlight visible, no blank page.
A3 Spotlight stable. Close (CloseMainWindow), relaunch, open Movies, copy the Movies `spotlight.selection` line. Do
this 3 launches total (A1 counts as 1), all in the same UTC day. PASS: `selectedIdHash` and `orderedIdsHash` are
identical in all 3. FAIL: any differs (copy all 3 lines). If the UTC day changes mid-test, SKIPPED.

## B. OLD-PICTURE regression (the main check)

Precondition: failing Live item named by orchestrator; engine noted from `app.start`.
B1 Open a movie (any, from Movies). Wait for `player.curtain-lowered`. Wait 10 s. Screenshot B-movie1 (the "old
picture" reference). Click "Pause". Wait 2 s. Screenshot B-paused.
B2 Click "Back to catalog", open the named failing Live item. Screenshots at 1, 2, 3, 5, 10, 20 s after the click:
B-fail-01s ... B-fail-20s.
B3 Go back, open a different movie. Screenshots at 1, 2, 3, 5 s, then once after `player.curtain-lowered`.
Expected logs: for B2 and B3 each a `player.reset reason=NewItem ... surfaceCleared=true`; for the failing item NO
`player.curtain-lowered` with its generation; it ends with `playback.final-failure stay=true` and
`player.final-failure reveal=false`. Any `reason=EngineFallback` reset must also say `surfaceCleared=true`.
PASS: no B-fail or B3 pre-frame screenshot shows any part of the B-paused movie frame (they show the dark curtain
/ status only), and all log expectations hold. FAIL: any screenshot shows the old frame, or `surfaceCleared=false`,
or `reveal=true`.
B4 Repeat B1-B3 on the other engine (switch in Account -> Player, close and relaunch so the switch is clean). If the
switch is not offered, SKIPPED for that engine.

## C. Final-failure screen

Precondition: the failing Live item open and its `playback.final-failure` logged (B2 state is fine).
C1 Screenshot C-1. Expected: status text (default "Couldn't start playback") beside the player controls, Retry
button visible, "Back to catalog" visible, Pause disabled. PASS: all four true (use UI Automation to read them).
C2 No message box: list top-level windows of the Tvivo process and check for a ContentDialog in the tree.
PASS: none. C3 Wait 45 s without input. Screenshot C-45s. PASS: still on the player failure screen.
C4 Click Retry. Expected: `player.reset reason=Retry ... surfaceCleared=true`, then at most N
`playback.retry attempt=a of N` lines, then exactly one new `playback.final-failure`. Measure time from click to
final-failure. PASS: bounded (no attempt > N, ends with final-failure, no old picture in a screenshot at 2 s).
C5 Click "Back to catalog". PASS: catalog visible within 5 s (screenshot C-back), app responsive.
If Back hangs (no repaint 30 s, `Responding=False`): record as HANG, copy last 20 lines, stop the round.

## D. Cinema pointer (logs only; the owner judges real visibility)

The tester cannot reliably see the real mouse pointer in screenshots. You report logs; the OWNER decides whether
the pointer was visible. Do not claim "pointer hidden" from a screenshot.
D1 Open a movie (LibVLC first, then Native in D5), wait for `player.curtain-lowered`, click "Cinema mode". Put the
pointer in the middle of the video, then no input for 8 s. Expected within ~3-4 s: `cinema.hide`,
`cinema.cursor-set hidden=true`, `cinema.cursor-popup ... isPopup=true`; and for the rest of the 8 s NO
`cinema.cursor-popup-destroyed` and no `cinema.show`. PASS: all true. FAIL: `isPopup=false`,
`cinema.cursor-popup-failed`, or a wake with no input.
D2 Wake by move: move the pointer 30 px in small steps. Expected: `cinema.cursor-popup-destroyed
reason=pointer-move` (or `pointer-poll`) and `cinema.show reason=..`. Then no input: `cinema.hide` again after ~3 s.
D3 Same with one left click on the video (expect `reason=click`; note: a click may also toggle pause, record it).
D4 Same with one wheel notch (expect `reason=wheel`).
Do D2-D4 as 3 rounds total. PASS: every round wakes with the right reason and hides again in 2.5-5 s.
D5 Repeat D1-D2 once on Native. D6 Exit cinema, go back, close app. Afterwards enumerate top-level windows of
class `Tvivo.CinemaCursorHitTarget`. PASS: none exist and no Tvivo.App process.

## E. Pause / resume

E1 Open a movie, wait for `player.curtain-lowered`, click Pause, wait 20 s, click Pause (play) again, wait 10 s.
Expected: zero `playback.interrupt` lines during the 20 s pause and the 10 s after; `playback.first_progress`
or rising position after resume. PASS: zero interrupts, screenshots E-paused / E-resumed show the same movie.

## F. Real stream-drop recovery

`docs/playback-interruption-verification.md` describes a loopback HTTP server with byte ranges and one dropped
connection. There is no harness for it in the repo, and Tvivo only plays URLs from the provider account, so
pointing it at loopback means changing the owner's provider setup. That is not allowed for the tester.
F = SKIPPED (owner-supervised). Record "SKIPPED: owner-supervised, no local harness".
Passive check only: across the whole round, copy any `playback.interrupt` / `playback.resume*` lines you saw.

## G. For You cards

G1 Click "For You". Screenshot G-0. If any of the three kinds is absent, that sub-check is SKIPPED.
G2 Movie card: click, expect `content-selected outcome=started kind=Movie` and `player.curtain-lowered`. Back.
G3 Series card: click, expect `content-selected outcome=opened-series kind=Series` and the series page; pick the
first episode: `outcome=started kind=Episode` then `player.curtain-lowered`. Back.
G4 Live card: expect `outcome=started kind=Live` and `player.curtain-lowered`. Back.
PASS per card: the lines appear and the picture plays within 20 s.

## H. Copy diagnostics content / privacy

H1 In the player (after G), click "Copy diagnostics". Expected: note "Diagnostics copied HH:mm:ss" appears.
Read the clipboard as text; save it to `H-clipboard.txt` in the results folder.
H2 Search it for: `http`, `rtsp`, `://`, the provider host, any username, the title text of each item you played,
`title="`, `category="`, `series="`. Copy matching lines into the results with the title text replaced by `<TITLE>`.
PASS: no URLs, hosts, credentials or titles. Known risk from source: after a failed start the app writes
`event=playback.target ... title="..." category="..."` and Copy diagnostics does not filter it, so H may FAIL after
section B/C. Report it as found; do not decide whether it is intended.

## I. Mini-player close (LibVLC only)

I1 Engine LibVLC. Open a movie, wait for `player.curtain-lowered`, click "Mini player" (not in cinema).
Screenshot I-1: catalog with the small player. I2 Click the mini player's close (X) button (`CompactCloseButton`).
Expected (from source): it returns to the full player; playback continues. Screenshot I-2.
I3 From cinema: click "Cinema mode", then "Mini player". Expect `window-mini.enter`. Click X. Expect
`window-mini.close-teardown destination=..` and playback stops, back to catalog. Screenshot I-3.
PASS: each step matches, no frozen window, no leftover small window. Also note: the X button's accessible name
is "Return to player" (same as the expand button) - report what the screen reader name says vs. what X does.

## 4. Wrap-up

Close Tvivo (CloseMainWindow). Restore theme if you changed it (relaunch, toggle, close). Restore mute state from
P4. Re-check: no Tvivo.App, no helpers, no `Tvivo.CinemaCursorHitTarget` windows. Fill the template below.

## 5. Results template (one per round)

```
Round: <date time>   Build: <Debug|Release> 22621   Engine at start: <..>   Tester: Haiku
Pre-checks: P1 __ P2 Id105=__ AC=__ P3 freeMB=__ P4 mute-before=__ method=__ P5 __ P6 __
Results folder: D:\Scratch\player-round-________

| Check | Engine | PASS/FAIL/SKIPPED | Evidence line (exact, titles -> <TITLE>) | Screenshot(s) |
|---|---|---|---|---|
| A1 launch | | | | A1-1 |
| A2 Movies | | | | A1-2 |
| A3 spotlight x3 | | | 3 selectedIdHash: | |
| B1-B3 old picture | | | reset/final-failure lines: | B-* |
| B4 other engine | | | | |
| C1 failure screen | | | | C-1 |
| C2 no message box | | | | |
| C3 stays 45 s | | | | C-45s |
| C4 Retry bounded | | | N=__ attempts seen=__ secs=__ | |
| C5 Back | | | | C-back |
| D1 idle popup 8 s | | | isPopup= | |
| D2-D4 wake x3 | | | reasons: | |
| D5 Native | | | | |
| D6 no leftover popup | | | | |
| E1 pause 20 s | | | interrupts=__ | E-* |
| F stream drop | | SKIPPED | owner-supervised | |
| G2 movie / G3 series / G4 live | | | | G-* |
| H1-H2 diagnostics | | | | H-clipboard.txt |
| I1-I3 mini close | | | | I-* |

Hangs / unexpected dialogs / foreground lost: ______
Wrap-up: theme restored __ mute restored __ no leftovers __
For the OWNER to judge: was the pointer visible during D1 idle? (tester does not answer this)
```
