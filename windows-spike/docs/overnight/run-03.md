# Run 03 - Batch 1 re-run on 368a25d (F1 + S1-S7) - iteration 2

- Date: 2026-10-19, 21:20-21:40 local. Runner: Opus.
- Build tested: clean detached worktree `D:\Scratch\overnight-wt` at HEAD 368a25d (committed F1 + static-audit S1-S7),
  Debug x64, 0 errors / 6 warnings, exe 21:22:18. Not built from the main tree (Sol has uncommitted `src\` work there).
  Harness: `D:\Scratch\overnight-tools` (ov-lib/ov-row + new `ov-wt.ps1` pointing at the scratch exe, `r3-cycles.ps1`).
- Tvivo sessions: 12 (one at a time; 11 full sessions + 1 second-instance probe). Streams: 0.

## Machine conditions

| Time | Available RAM | Note |
|---|---|---|
| 21:22 | 2130-2383 MB | Android Gradle daemons (java ~1.9 GB), not touched. Batch 1 started under D-l. |
| 21:25 | 1320 MB | Below the 1.5 GB floor -> Tvivo closed at once (rule 5). |
| 21:26:47 | 3986 MB | Gradle daemons gone; runs resumed. |
| 21:30:32 | - | Android emulator started (qemu ~3.4 GB WS, not touched). |
| 21:32:40 | 1276 MB | Cycle loop stopped itself before cycle 10 (rule 5). |
| 21:34 | 408-575 MB | No further launches. |

Foreground: Tvivo was foreground in **0 of 12** launches (Chrome held the foreground; second-instance activation
refused again). Per rule 4 / D-g no keyboard, pointer or UIA input was sent; only OS window messages
(`WM_SYSCOMMAND` SC_CLOSE / SC_MINIMIZE) and log/OS metrics were used.

## F1 verification (DF-01, DF-04) - PASS

- `event=auth.saved outcome=success reason=none code=none idMatch=True` on 10 of 10 starts that reached auth
  (0 failures; the early-close start closed before auth by design).
- Session fa80957c (first start, last refresh stamp was a failure): `refresh.skipped-waiting-for-auth` while auth
  was pending, then after `authDone` `catalog.cache.refresh.start` -> 6 provider fetches -> 
  `catalog.cache.refresh.complete outcome=success durationMs=16312`, then a rebuilt render (Spotlight count=12).
- DF-04: first Spotlight lookup `hover.metadata source=provider` at 21:24:48.807, after `authDone` (48.773);
  `hover.metadata fetched type=Movie plot=present`.
- Across all 12 sessions: 0 `provider.not-authenticated`, 0 `refresh failed`, 0 `hover.metadata failed`.
- Later starts did not auto-refresh (stamp now fresh, policy not due): expected, not a defect.

## S1/S2 verification (close teardown) - PASS on every close observed

11 closes, all `window.close-requested` -> `close-stage` visits-flush / resume-save / engines-stop / dispose ->
`window.close-complete`; `totalMs` 35-336 (budget 5000); 0 `window.close-backstop`; 0 `skipped=budget`;
0 exception lines between `MainWindow closed` and `close-complete` (including the close during cache load);
0 WER events, 0 crash dumps; 0 `Tvivo.App` processes after each close.
S3-S7 have no Batch 1 trigger (sleep timer, cooldown, Spotlight settings, artwork churn, folder search): NOT-RUN here.

## Results

| ID | Status | Evidence (statuses/metrics only) |
|---|---|---|
| LC-01 | PASS | Session 7496e469: cachedRender 5840 ms, authDone 6227 ms. Idle s30-60 after render: CPU 1.5-2.8 % (avg ~2.2 %), WS 267 MB flat, handles 1455-1472, threads 33-42, GDI 19 / USER 41-45, ping max 1 ms; 0 `perf.stall` after 21:27:08 (4 stalls 103-244 ms inside startup), 0 slowop, 0 net/hover lines in the idle window; Spotlight count=12. Idle CPU is higher than run 02 (0-1.2 %) but under 3 %. First session fa80957c started under memory pressure (cachedRender 40364 ms, stall 6722 ms before authDone): invalid as baseline (D-h). |
| LC-02 | BLOCKED (focus) | UIA Invoke needs Tvivo foreground (0 of 12 launches). |
| LC-03 | BLOCKED (focus) | Alt+F4 needs keyboard. |
| LC-04 | PASS (SC_CLOSE half); WindowPattern half BLOCKED (focus) | 11 SC_CLOSE closes: CT 203-917 ms on a quiet machine (median ~335 ms). One close at Available 1320 MB (session fa80957c) took CT 3310 ms while the app's own `totalMs=336` - the extra ~3 s is outside the teardown (paging); watch item, not counted. |
| LC-05 | BLOCKED (focus) | Needs keyboard/UIA. DF-02 cannot be re-checked; F2 diagnostics are in the build but no selection happened (0 `selection.source`/`content-selected`/`playback.attempt` lines all run). |
| LC-06 | PARTIAL - guard PASS, activation BLOCKED (OS foreground lock) | `app.second-instance activated-existing=false`; exactly 1 `Tvivo.App` after 5 s. |
| LC-07 | PASS (SC_CLOSE variant) | SC_CLOSE 1.5 s after `app.start`, before `catalog.cache.load.complete` (0 load-complete lines): CT 330 ms, `totalMs=96`, 0 exceptions, no WER. Next launch: 2 load-complete lines, cachedRender 6074 ms, auth success. |
| LC-08 | BLOCKED (focus) | Needs "Refresh catalog" invoke; the startup auto-refresh is no longer due after the F1 fix, so it cannot stand in. |
| LC-09 | PASS (SC_MINIMIZE variant) | SC_MINIMIZE -> `IsIconic=True`; SC_CLOSE to the iconic hwnd: CT 203 ms, `totalMs=35`, no WER. |
| LC-10 | BLOCKED (focus) | F11 needs keyboard. |
| LC-11 | PARTIAL - 9 of 10 cycles PASS, cycle 10 BLOCKED (memory) | Cycles 2-9 (+ early cycle 1): all exited, CT 253-917 ms, `totalMs` 50-116, 0 exceptions, 0 backstop, 0 WER, auth success/idMatch=True each. cachedRender 5468-8482 ms (median 6815; max 1.24x) - rising with falling Available RAM (3675 -> 1782 MB as the emulator started), so the 1.2x rule is inconclusive per D-h. Log rotation not exercised (log 940 KB < 1 MB). |

Counts: PASS 4 (LC-01, 04 SC_CLOSE, 07, 09) + F1 and S1/S2 checks PASS; PARTIAL 2 (LC-06, LC-11); FAIL 0;
BLOCKED 5 (LC-02, 03, 05, 08, 10).

## Stalls > 1000 ms

One per start in cycles 2-9 (1046-1826 ms), always 0.1-0.3 s after `cachedRender` and before `authDone`
(ops ShelfContainerLoaded / ContainerContentChanging / ArtworkDataContextChanged). Inside the startup window, so
excused by D-a; recorded as watch W-03 (it is the first moment the user can act on the rendered catalog).
No stall > 1000 ms outside the startup window in any session.

## Privacy and close

Privacy grep over all run-03 lines (12 sessions): 0 hits. WER/CrashDumps since 21:23: 0. Last close 21:32:40;
0 `Tvivo.App` processes after the run. No setting, theme or account was changed.

## Batches 2-5

NOT-RUN. Every row needs keyboard/UIA input or playback started from the UI (focus: 0 of 12 launches foreground),
and Available RAM fell to 408-575 MB after the Android emulator started at 21:30:32 (rule 5, D-e). See D-m.
