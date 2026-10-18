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
