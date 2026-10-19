# Run 02 - Batch 1 (Lifecycle and close paths) - iteration 1

- Date: 2026-10-19, 14:51-15:13 local. Runner: Opus.
- Build tested: HEAD c6936d0, Debug x64 exe 03:26:18 (newer than every committed source; newest committed source
  03:25:48). Uncommitted Sol work in `src\` (static-audit S1-S7, files dated 04:01-15:11) was NOT built in: app
  `bin\` DLLs unchanged at 03:01-03:26 throughout the run (checked at end).
- Tvivo sessions: 6 launches (one at a time) + 2 second-instance probes. Streams: 0 intended, 1 unintended (DF-02).
- Harness: `D:\Scratch\overnight-tools` (ov-lib.ps1, sampler.ps1, new ov-row.ps1, lc11.ps1).

## Machine conditions (they shaped the result)

| Time | Available RAM | Other load |
|---|---|---|
| 14:52 | 2954 MB | Android-session Gradle build (D:\HCode\Tvivo, not touched) saturating D: - queue 64-65, 194 ms/read, 0 % idle |
| 14:56-15:05 | 2.1-3.3 GB | D: queue back to 0-10 after ~14:56 |
| 15:11 | 1801 -> 1314 MB | Android emulator back (qemu ~4.1 GB WS); Sol's `dotnet test` processes |
| 15:13 | 954 MB | same - below the 1.5 GB floor; no further launches (rule 5) |

Foreground: Tvivo was foreground in 1 of 6 launches (session 4b368663). In the others Windows kept the foreground on
another app. The app's second-instance activation also could not take it (`activated-existing=false` twice; the
OS refused `SetForegroundWindow`). Keyboard/UIA rows ran only in the foreground session. Per D-g, OS window-manager
messages (`WM_SYSCOMMAND` SC_CLOSE/SC_MINIMIZE) were used where the row allows an OS-level variant.

## Results

| ID | Status | Evidence (statuses/metrics only) |
|---|---|---|
| LC-01 | PASS (idle part); startup baseline invalid | Launch 14:52:04 under D: saturation: first log line +136 s, window +243 s, `cachedRender` 80676 ms (external I/O, not app; process used ~1 s CPU in 3 min). Idle 60 s after render: CPU 0.0-1.2 %, WS 266 MB flat, handles 1422-1465, threads 23-35, ping 0 ms, 0 `perf.stall`, 0 net/hover/artwork lines. Spotlight `count=12`. Clean-launch baseline from later launches: `cachedRender` 7685 / 10332 / 4223 ms, `authDone` 8055 / 13845 / 4546 ms (median 7685 / 8055). |
| LC-02 | PASS (watch) | UIA Invoke "Close Tvivo": CT 2778 ms, exited, 0 remaining; `close-requested via=x`, stages visits-flush 0 / resume-save 0 / engines-stop 0 / dispose 1609 ms, `totalMs=1659`. Dispose was slow because LibVLC had been used (DF-02 open). No WER/dump. CT is within 3 s but close to it. |
| LC-03 | BLOCKED (focus) | Relaunch c388f27d not foreground (8 s wait); Alt+F4 not sent. |
| LC-04 | PASS (SC_CLOSE half); WindowPattern half BLOCKED (focus) | SC_CLOSE to main hwnd: CT 1417 ms; `via=taskbar` stages 4/3/76/40 ms, `totalMs=175`; 0 exceptions; no WER/dump; privacy grep 0. |
| LC-05 | FAIL (DF-02) | Esc x3 on catalog: app alive, 0 navigation lines - OK. Search opened (focus `TopSearchBox`), typed 2 letters; focus then jumped to `WindowStateButton` and a movie started without Enter/click (`content-selected kind=Movie` -> `player.reset NewItem` -> engine-open); the following Esc returned from the player (`BACK-TRACE return-from-player`, `playback.stop reason=user-cancelled`). Repeated twice under control: not reproduced (focus stayed in the box, no selection). Esc with focus in the search box: search closed, no navigation, app alive - OK. |
| LC-06 | PARTIAL - guard PASS, activation BLOCKED (OS foreground lock) | Two second-instance launches: `app.second-instance activated-existing=false` (13 s and 2.3 s), exactly 1 `Tvivo.App` after 5 s both times; existing window not brought forward (harness not foreground). |
| LC-07 | PASS (SC_CLOSE variant) | SC_CLOSE 1.5 s after `app.start`, before `catalog.cache.load.complete`: CT 258 ms, `totalMs=52`, 0 exceptions, no WER. Next launch loaded (`catalog.render`, cachedRender 10332 ms); the 20 % startup rule is inconclusive under disk noise (clean range 4.2-10.3 s), see D-h. |
| LC-08 | BLOCKED (focus + DF-01) | Refresh needs UIA/keyboard (not foreground). The startup auto-refresh cannot stand in: it fails in 2-523 ms without reaching the network (DF-01), so no refresh is ever "in flight". |
| LC-09 | PASS (SC_MINIMIZE variant) | SC_MINIMIZE -> `IsIconic=True`; SC_CLOSE to the iconic hwnd: CT 231 ms, `totalMs=34`, no WER. |
| LC-10 | BLOCKED (focus) | F11 needs keyboard. |
| LC-11 | BLOCKED (memory) | Cycle 1 pre-check: Available 1314 MB < 1500 MB floor; loop stopped before launching. |

Counts: PASS 5 (LC-01, 02, 04, 07, 09), FAIL 1 (LC-05), BLOCKED 5 (LC-03, 06 activation, 08, 10, 11).

## Session-level metrics

| Session | Duration | perf.stall (n / max / >1000) | slowop | exceptions-like lines | close |
|---|---|---|---|---|---|
| c4794e69 (LC-01, LC-06, LC-04) | 14:52-15:01 | 0 / 0 / 0 in idle window | 0 | 9 refresh-failed + 1 hover-failed (DF-01) | SC_CLOSE CT 1417 ms |
| ab38978f (LC-09) | 15:01-15:02 | 3 / 928 / 0 (startup) | 1 (BuildShelves 1741 ms) | 6 refresh-failed + 1 hover-failed + 1 artwork InvalidData | SC_CLOSE CT 231 ms |
| fc11b119 (LC-07) | 15:04-15:05 | - | - | 0 | SC_CLOSE CT 258 ms |
| 4b368663 (LC-05, LC-02) | 15:05-15:10 | 38 / 4103 / 9 | 12 | 9 refresh-failed + 8 hover-failed (DF-01), 3 artwork InvalidData | UIA close CT 2778 ms |
| c388f27d (LC-03 attempt) | 15:10-15:11 | 2 / 639 / 0 | 1 | 8 (DF-01 pattern) | SC_CLOSE CT 767 ms (memory stop) |

Stalls > 1000 ms in 4b368663, by cause:
- 15:05:32 (4103 ms, ShelfContainerLoaded 1064 ms) and 15:05:35 (3062 ms): inside the startup window (`authDone`
  15:05:35.6) - excused by D-a.
- 15:05:39 (1808 ms) and 15:05:40 (1138 ms): after startup; ops show only the probe timer (uninstrumented work).
  Harness UIA root lookups ran at about that time - attribution uncertain (DF-03, D-i).
- 15:06:02-15:06:15 (1190, 2808, 1136, 1675, 2240 ms; slowop `RunOnUiAsync` 2316 ms at player-host-ready):
  overlap the harness UIA descendant scans and the DF-02 player open.

Privacy grep (URLs, host:port, user/password/token) over all 6 sessions: 0 hits. WER/CrashDumps since 14:51: 0.

## Unintended stream (rule 3 record)
One provider stream was opened by DF-02 at 15:06:12 and stopped at 15:06:17.1 (Esc). Tvivo was not muted (the open
was not requested). The engine had just reached `engine-open` (15:06:16.2): no `curtain-lowered`, no first progress,
so no picture or audio is expected to have played. No other stream was opened.

## Close times
Every session closed cleanly; last close 15:11:54 (SC_CLOSE, CT 767 ms). No `Tvivo.App` process after the run.
Theme stayed dark; no setting was changed. Search text left by LC-05 was cleared in-app before close.

## Batches 2 and 3
NOT-RUN. Stop conditions at 15:11-15:13: Available RAM 1314 -> 954 MB (< 1.5 GB floor, < 2.5 GB batch floor) with the
Android emulator running again, and Tvivo foreground obtainable only by chance. See STATE.md for the preconditions.
