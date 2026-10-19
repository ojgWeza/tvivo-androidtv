# Overnight decisions (Opus, acting for the owner)

Each decision is reversible by the owner in the morning; FINAL.md must list every one that changed behaviour.

## D-a  Metric thresholds (2026-10-19, iteration 0)

RULES.md thresholds accepted, with these refinements:

| Metric | Defect | Critical |
|---|---|---|
| UI stall (external WM_NULL ping or `perf.stall`), outside startup (from `app.start` to the last `perf.startup` phase, max 15 s) | any > 1000 ms; or >= 5 stalls of 250-1000 ms in one row (P2 perf) | > 5000 ms; 3 pings in a row > 10 s = hang -> one dump, kill, record, continue |
| Working set growth over one row, after settle (20 s idle, min of last 3 samples) | > 150 MB (playback rows); > 75 MB (rows without playback) | - |
| Cycle rows (EN-*, NV-08) after cycle 10 | WS slope > 2 MB/cycle; handles > +5/cycle; GDI/USER > +2/cycle; threads not back to baseline +-5 | - |
| Handles / GDI+USER over one row after settle | > +300 / > +100 | - |
| Idle CPU (no playback, % of one core) | > 3 % averaged over any 30 s; idle soak avg > 1 %; minimised > 0.5 % | - |
| Close time (command -> process exit) | 3-10 s (refresh/open in flight: 5-10 s) | > 10 s, or window gone but process alive |
| Movie first frame | no frame and no final-failure UI within 25 s per attempt; popcorn > 60 s without status change (cooldown excepted) | popcorn forever |
| Cooldown | total > 65 s x 1.1; bar not monotonic; any 403 retried by ordinary policy | - |
| Retry bar | visible < 1.5 s after an open | - |
| Spotlight idle | any image/metadata request > 10 s after render with no input | - |
| Crash, unhandled exception, WER entry, `0xC0000374` | - | always critical |
| Privacy: URL, host, port, credential, token or title in any log/report | - | always critical (P0) |

Playing-state CPU is recorded only (no threshold) because it depends on the stream.

## D-b  In-window mini X button naming (2026-10-19)

Finding: in the in-window mini, `CompactExpandButton` and `CompactCloseButton` are both named and tooltipped
"Return to player", and the X does the same as Expand. In the window-mini the X is named "Close (stop playback)"
and stops playback. Two identical names confuse Narrator users and seniors, and an X that does not close breaks
the universal meaning of X.

Decision:
- Both minis: the X button (`CompactCloseButton`) is named and tooltipped **"Stop playback"** and stops playback,
  staying on the page the user is on (in-window mini: the current catalog page; window-mini: unchanged, back to
  the previous page). Sleep timer cancels with reason mini-close in both.
- The expand button keeps **"Return to player"** in both minis.
- Esc in the in-window mini keeps its current, gentler effect (return to the full player) - it must be rewired to
  the Expand action, not to the X, so the rename does not make Esc stop playback.
- Fix brief for Sol (P1 defect, one slice): change `CompactCloseButton_Click` non-window-mini branch to the stop +
  stay path, update the name-setting code near `MainWindow.xaml.cs:5957` and the XAML default, rewire Esc, add a
  policy test; update `docs/test-plan-sleep-timer.md` names. Rows PC-04, PC-05, ST-03, OE-04 verify it.

## D-c  Cinema exit control (2026-10-19)

Decision: **no extra always-visible Exit control.** Cinema exists to remove chrome. Exits already available:
the "Exit cinema" button in the transport bar (revealed by any pointer move or key press), Esc, and double-click.
Conditions that keep this decision valid, tested by WS-10/PC-07:
- the transport with "Exit cinema" appears within 500 ms of a pointer move or Tab;
- first Esc leaves cinema and stays in the player; a second Esc goes back to the catalog;
- the sleep-timer warning/ended screens stay reachable while the transport is faded.
Small polish (P2, may ride with another slice): tooltip "Exit cinema (Esc)"; the automation Name stays "Exit
cinema". If any condition fails, the failure is a defect to fix in the reveal logic, not a reason to add chrome.

## D-d  Batch order and budgets (2026-10-19)

Order (P0 first, then cheap P1, then long P2):
1 Lifecycle/close (no stream) -> 2 Playback close paths -> 3 Rapid opens/cooldown/retry -> 4 Window states and
pointer -> 5 Sleep timer A -> 6 Series/autoplay/sleep timer B -> 7 Spotlight -> 8 Settings/Spotlight sources ->
9 Navigation/search/refresh -> 10 Themes/narrow/resize -> 11 Endurance/leaks -> 12 Native engine + log hygiene ->
13 Idle soak -> 14 Playback soak. OWNER-EYES rows go straight to FINAL.md.

Budgets and exceptions:
- Streams: <= 15 opens per batch, one at a time, muted; no same item within 70 s except cooldown rows. Exception:
  Batch 11 (EN-01, ~55 opens, with the cooldown back-off/stop rule in the row).
- Stream length: "seconds" by default. Exceptions: sleep-timer rows (<= 6 min per stream, the minimum timer is
  5 min) and SK-04/05 (30 min) - the owner explicitly asked for a 30+ min soak with playback.
- Batch time <= 25 min, except Batches 13 and 14 (~35 min each, soak length is the point).
- DPI 150/200 % and monitor-DPI changes are OWNER-EYES: changing system scaling overnight alters her whole desktop.
  The Windows animation setting (SP-09) may be toggled because it is restored at once; failure to restore = stop.
- After a fix lands, re-run only the batches whose rows touched the changed code, then the final clean pass.

## D-e  Memory gate holds; do not launch under 1.5 GB (2026-10-19, run 01)

Available RAM stayed 0.81-1.24 GB for 13 min (Android emulator ~4.1 GB + agent processes). Decision: keep the
rule as written - no Tvivo launch below 1.5 GB, batch start needs >= 2.5 GB; Batch 1 recorded BLOCKED, not
lowered thresholds. Reason: a launch at < 1 GB would page and corrupt every timing/stall metric and risks the box.
The emulator belongs to the Android session (rule 1), so freeing memory is the orchestrator's call, not the runner's.

## D-f  Static audit triage (Opus, 2026-10-19, after Sol's STATIC-AUDIT.md)

14 findings verified against the code: 11 kept (SA-01/SA-02 merged into one brief), 3 discarded. Briefs S1-S7 in
FIX-BRIEFS.md "Static audit batch". Design choices made for the owner:
- D-f1 Close teardown (SA-01+02): engine stop/dispose stay on the UI thread (no `ConfigureAwait(false)`), under ONE
  total budget of 5 s from close request for all async stages, plus a pool-thread hard backstop that calls
  `Environment.Exit(0)` at budget + 1.5 s. Reason: native players are apartment-bound, and the backstop removes the
  only reason the pool hop existed (a dead dispatcher can no longer keep the process alive).
- D-f2 Spotlight settings (SA-06): Save bumps a settings revision; cached snapshots built under another revision are
  ignored and dropped. Reason: one counter covers both the cached and the in-flight-refresh case without new plumbing.
- D-f3 Artwork permits (SA-08): a decode permit is held until that load's own task exits, never released by
  `StopArtwork`. Reason: the concurrency cap must be real; a hung `SetSourceAsync` (never seen) is the accepted risk.
- D-f4 Folder search (SA-14): 200 ms debounce only; no virtualization tonight. Reason: removes the per-keystroke
  rebuild with a tiny diff; virtualization is a UI redesign and waits for SS-04 evidence.
- DEFER SA-09 (resume save on UI thread, 1 small transaction / 5 s) and SA-10 (episode-change DB reads): real but
  unmeasured; `perf.stall`/`perf.slowop` and `episode.open.exit ms=` in SK-04/SE-01/SE-02 decide. Defect if a stall
  > 250 ms coincides with `resume.save` or `episode.open.exit ms` > 250.
- DEFER SA-11 (idle timers): the 250 ms dispatch probe and 2 s perf timer ARE tonight's stall/perf instruments;
  stopping them would blind the matrix. The 500 ms playback timer's idle tick returns early. LC-01/SK-01/SK-03 idle
  CPU thresholds (D-a) decide; revisit in a later slice if they fail.
- DISCARD SA-07: cards are reused only when the item ID matches and the lookup is keyed by account + type + item,
  so the applied summary is the right one. DISCARD SA-12: the CTS instances are plain (no timer, no link), so
  not disposing them holds no native resource. DISCARD SA-13: every failure sweeps expired keys and lookups are
  serialized network calls, so the map is bounded by ~30 s of failures.

## D-g  OS window messages are allowed without foreground; input and UIA are not (2026-10-19, run 02)

Windows refused the foreground to Tvivo in 5 of 6 launches (also via the app's own second-instance activation).
Decision: `WM_SYSCOMMAND` SC_CLOSE / SC_MINIMIZE posted to Tvivo's own hwnd are OS checks (the path the taskbar
uses): they inject no input, move no other window and steal no focus, so rows may use them as a recorded variant
(LC-04, LC-07, LC-09). Keyboard, pointer and UIA Invoke run only while Tvivo is foreground (rule 4); otherwise
BLOCKED. No SetForegroundWindow/AttachThreadInput/minimise-others tricks.

## D-h  Startup baseline under external disk load (run 02)

The first launch ran while the Android Gradle build held D: at queue ~65 (cachedRender 80.7 s): invalid as a
baseline, not an app defect. Baseline = median of clean launches (D: queue < 10): cachedRender 7685 ms, authDone
8055 ms (range 4.2-10.3 s). LC-07/LC-11 "within 20 %" is judged against that range until a quiet machine exists;
a launch is a defect only if > 2x the median with D: quiet.

## D-i  Harness UIA traffic counts as load (run 02)

UIA calls are served on Tvivo's UI thread; descendant scans of the catalog tree coincided with 1-2.8 s stalls.
Decision: during metric windows the harness uses a cached root and AutomationId lookups only (no TrueCondition or
Name scans over Descendants); stalls overlapping a harness scan are recorded but not counted as defects. DF-03 stays
"needs confirmation" until re-measured with no UIA traffic.

## D-j  DF-02 is P1 although not reproduced (run 02)

An unrequested provider stream start (unmuted, no Enter/click) breaks the stream rules and a basic user
expectation, and it happened during ordinary typing. Keep it P1; Sol adds activation-source diagnostics first and
fixes only a found cause (F2). Rows LC-05, NV-03..05 repeat it with the pointer resting over Spotlight.

## D-k  Batches 2-3 not started; no lowered thresholds (run 02)

At 15:11-15:13 Available RAM was 1314 -> 954 MB with the Android emulator back (~4.1 GB) and Sol's test run.
Per D-e the floors stand. Batch 2+ also needs a foreground-capable launch: the runner must be started from the
foreground session (or the owner leaves Tvivo foreground), otherwise every playback row is BLOCKED.
Because Sol's static-audit slice (S1 close teardown) is in the tree uncommitted, Batch 1 close rows must be
re-run on that build anyway; run 02 results stand for c6936d0 only.
