# Pointer hiding test results, 2026-10-18 (Haiku tester)

| Test | Result | Key evidence (times local, no titles/URLs) |
|---|---|---|
| C1 Cinema idle hide + wake | PASS | `cinema.cursor-set hidden=True` and `cinema.hide` at 20:17:13.28; `cinema.cursor-probe cursorVisible=false flags=0x0` at 20:17:14.08. Pointer moved 30 px: `cinema.show reason=pointer-poll` and `cursor-set hidden=False` at 20:18:37.51. Recorder: visible=False while parked, True after. |
| C2 Cinema with Sleep timer focused | PASS | Keyboard Tab x8 to `SleepTimerButton` (UIA focus confirmed). Pointer still: `cinema.hide` at 20:18:40.52, `cursor-probe flags=0x0` at 20:18:41.32. Recorder visible=False. No flyout was opened, so no Escape was used. |
| M1 Mini player idle hide | NOT VALIDATED (blocked) | `window-mini.enter` at 20:19:34.84, bounds 916,107 364x346. `compact.cursor-hide reason=idle` at 20:19:37.49, but `compact.cursor-probe` at 20:19:38.30 shows `cursorVisible=true class=DirectUIHWND flags=0x1`. Recorder shows the window under the pointer is `CabinetWClass` (explorer.exe, not Tvivo). |
| M2 Window-mini (always-on-top) | NOT SEPARATELY REACHED | The Mini player button opens the topmost window-mini, so M2 is the same surface as M1. |

## Notes

- M1 blocker: `WindowFromPoint` never returned the Tvivo mini window at any of 30 sampled points inside its rectangle (6x5 grid). An Explorer window ("win-x64" folder, not topmost) covers the same screen area. The mini is topmost and shows in front in screenshots, yet OS hit-testing skips it. Cause not determined. The owner's Explorer window was left alone.
- The first C1 attempt (about 20:14 to 20:16) did not hide. My top-band click had paused playback, and focus was on the Exit cinema button. The passing run came after resuming playback and focusing the Pause button. Cause of the first failure not established.
- Unexplained: `cinema.show reason=interaction` at 20:18:52.37 with no pointer movement from the tester; hidden again at 20:18:55.68.
- Audio: Windows mute state could not be read reliably. Tvivo's volume slider was set to 0 for the test and restored to 100 before leaving the player.
- Log rotated during the run. Events for the session are in `tvivo-launch.log.1` and `tvivo-launch.log`.

## Restore

Returned from mini (CompactExpandButton), restored volume to 100, and used Back to catalog. Catalog Movies view is showing. Tvivo stayed foreground throughout. Nothing was launched, closed, moved or rebuilt. The recorder processes were stopped. Recorder outputs: `C:\Users\Dell\.claude\jobs\a70de3dc\tmp\pointer-C1-recorder.txt` and `pointer-C2-recorder.txt`.
