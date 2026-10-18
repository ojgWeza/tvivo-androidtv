# Overnight scenario matrix (Opus, 2026-10-19, iteration 0)

Obeys `RULES.md`. Thresholds and behaviour decisions live in `DECISIONS.md`; where a row says "per D-x" the
decision there is the expected result. Grounded in `docs/player-invariants.md`, `docs/test-plan-sleep-timer.md`,
`docs/cooldown-notes.md`, `docs/spotlight-plan.md`, `docs/test-results-pointer-2026-10-18.md`, `perf/REPORT.md`,
`perf/Measure-Tvivo.ps1` and `src/Tvivo.App/MainWindow.xaml(.cs)`.

## 0. Common harness (every batch)

**Launch.** Only when no `Tvivo.App` process exists: `Start-Process src\Tvivo.App\bin\x64\Debug\net9.0-windows10.0.22621.0\win-x64\Tvivo.App.exe`.
Wait for a new `event=app.start` line (note its `session=` id; use only that session's lines) and the window handle.
Confirm foreground belongs to `Tvivo.App` before every input burst (rule 4); if it cannot be, the row is BLOCKED.

**Pre-batch checks.** Free RAM >= 2.5 GB to start a batch (stop launching below 1.5 GB, rule 5). Theme is dark
(header toggle Name = "Switch to light theme"). Before the first playback: Tvivo "Mute" + volume 0; record it.

**Metric bundle "M"** (one long-lived PowerShell sampler, never one process per sample; see `perf/Measure-Tvivo.ps1`):
- every 5 s: CPU % of one core (TotalProcessorTime delta / wall), working set MB, private MB, handles, threads,
  GDI + USER objects (`GetGuiResources`);
- every 1 s: UI ping = `SendMessageTimeout(WM_NULL, SMTO_ABORTIFHUNG, 5000 ms)` round trip (the in-app
  `perf.stall` probe only runs on the catalog page, so the external ping is the authority on the player);
- at row end, from this session's log lines in the row's time window: count + max `delayMs` of `perf.stall`,
  count of `perf.slowop`, `artwork.failure`, `playback.final-failure`, `player.final-failure`,
  `playback.vlc.exception`, `playback.native.exception`, `series.open.exception`, `playback.host-not-ready`,
  `catalog.mode-swap-stalled`, `window-mini.forced-exit`, `window-mini.topmost-reassert-failed`, any line with
  `exception`; process alive; WER/crash check (Application event log 1000/1026 for Tvivo.App, new files in
  `%LOCALAPPDATA%\CrashDumps`).
- **Close time** "CT" = ms from the close command to process exit (`WaitForExit`).
- **Settle** = 20 s without input after the last action; memory deltas use the minimum of the last 3 samples.

**Item notation (never record titles, rule 2).** A, B, C, D, E = movies by position (Movies page, first shelf,
cards 1-5). S = a series with >= 3 episodes (Series page, first shelf, first qualifying card). L1, L2 = Live
channels 1 and 2 of the first Live category. Do not reopen the same movie/episode within 70 s unless the row is a
cooldown row. Stream budget: <= 15 opens per batch except EN-01.

**End of every batch.** Privacy grep of the session lines: `https?://`, `:\d{2,5}/`, `user(name)?=`, `password`,
`token=`, any title noted in screenshots -> any hit is a P0 privacy defect (value redacted). Close Tvivo (Alt+F4,
record CT), confirm no `Tvivo.App` process, restore mute/volume/theme/settings changed in the batch.

Status per row: PASS / FAIL / BLOCKED / NOT-RUN / NOT-TRIGGERED (opportunistic rows whose trigger did not occur).

---

## Batch 1 - Lifecycle and close paths, no playback (P0, ~20 min, 0 streams)

| ID | Pri | Steps | Expected | Metric checks |
|---|---|---|---|---|
| LC-01 | P0 | Launch; wait catalog render (Movies or Home); idle 60 s, no input. | Window responsive; `perf.startup` phases logged; Spotlight shows <= 12 cards; no error UI. | M; idle CPU over s30-60 <= 3 %; no `perf.stall` > 1000 after startup; startup ms recorded as baseline. |
| LC-02 | P0 | UIA Invoke "Close Tvivo" (`CloseWindowButton`). | Clean exit. | CT <= 3 s; no WER; no orphan process. |
| LC-03 | P0 | Relaunch; Alt+F4 on catalog. | Clean exit. | CT <= 3 s; no WER. |
| LC-04 | P0 | Relaunch; send `WM_SYSCOMMAND SC_CLOSE` to the main hwnd (what taskbar "Close window" sends); relaunch; UIA `WindowPattern.Close`. | Clean exit both times. | CT <= 3 s each. |
| LC-05 | P0 | Relaunch; on catalog with search closed press Esc x3; open search (invoke "Search current catalog"), type 2 letters, Esc. | Esc never closes the app or leaves the page; with search open, Esc closes search only. | App alive; no navigation lines. |
| LC-06 | P0 | While running, start the exe a second time. | `app.second-instance activated-existing=true`; existing window restored/foreground. | Exactly 1 `Tvivo.App` process after 5 s. |
| LC-07 | P0 | Alt+F4; relaunch and Alt+F4 1.5 s after `app.start` (before `catalog.cache.load.complete`); relaunch normally. | Exit not blocked by load; next launch loads the catalog normally (non-empty shelves). | CT <= 3 s; no exception; next start ms within 20 % of LC-01. |
| LC-08 | P0 | Invoke "Refresh catalog"; Alt+F4 1 s after `catalog.cache.refresh.start`; relaunch. | Exit not blocked by refresh; catalog intact on relaunch (shelf counts > 0). | CT <= 5 s (network cancel allowance); no exception. |
| LC-09 | P1 | Invoke "Minimize window"; send `SC_CLOSE` to the iconic hwnd. | Clean exit from minimised. | CT <= 3 s. |
| LC-10 | P1 | Relaunch; F11 (fullscreen); Alt+F4; relaunch. | Exit clean; relaunch window on-screen, not stuck borderless; record bounds. | CT <= 3 s; bounds inside a monitor work area. |
| LC-11 | P1 | 10x (launch -> catalog render -> Alt+F4). | All exits clean; startup stable. | CT <= 3 s all 10; startup ms max <= 1.2x median; log rotates to `.log.1` without exception. |

## Batch 2 - Playback start and close paths (P0, ~20 min, ~10 streams)

| ID | Pri | Steps | Expected | Metric checks |
|---|---|---|---|---|
| PC-01 | P0 | Open A (card Enter); mute; wait first frame. | `player.reset ... surfaceCleared=true` then `player.curtain-lowered`; popcorn "Loading" until then; no retry bar if frame < 1.5 s. | Time to first frame <= 25 s (record); M. |
| PC-02 | P0 | A playing in full player, 10 s; invoke "Close Tvivo". Relaunch; look at A's card. | `resume.save kind=Movie ... completed=false` and `playback.stop ... reason=user-cancelled` before exit; A shows in-progress on relaunch. | CT <= 3 s; no WER. |
| PC-03 | P0 | Open B; "Cinema mode"; 10 s; Alt+F4. | As PC-02 for B. | CT <= 3 s. |
| PC-04 | P0 | Open C; "Mini player" path to in-window mini (catalog visible, compact card playing); idle until `compact.cursor-hide`; Alt+F4. | Exit clean; cursor visible after exit (`GetCursorInfo`). | CT <= 3 s; cursor flags = showing. |
| PC-05 | P0 | Open D; cinema -> "Mini player" (`window-mini.enter`); invoke `CompactCloseButton`. | `window-mini.close-teardown`; playback stopped; main window back at previous page; app alive. Name per D-b. | 1 visible Tvivo top-level window; no `forced-exit`. |
| PC-06 | P0 | Open E; enter window-mini; with mini foreground press Alt+F4. | Whole app exits (window-mini is the app window); geometry saved. | CT <= 3 s; no orphan window/process. |
| PC-07 | P0 | Open A (>70 s after PC-02); Esc in full player; reopen B; cinema; Esc; Esc; reopen C; window-mini; Esc. | Full: Esc -> catalog, `playback.stop`. Cinema: 1st Esc leaves cinema, 2nd -> catalog (per D-c). Window-mini: Esc leaves the mini window, playback continues; record landing presentation. | No `exception`; one playback chain at a time. |
| PC-08 | P0 | Open D; Alt+F4 1 s after `player.reset` (engine open in flight). | Exit not blocked by a pending open. | CT <= 3 s (`playback.handoff.slow-stop` allowed); no WER. |
| PC-09 | P1 | Open E; invoke "Minimize window"; 20 s; SW_RESTORE; then window-mini -> `CompactMinimizeButton`; 20 s; restore. | Playback continues while minimised (no `playback.interrupt`); picture live within 1 s of restore; controls respond. | Elapsed time advanced ~20 s each; M. |
| PC-10 | P1 | Open A (>70 s since last); window-mini; `SC_CLOSE` to the hwnd. | Clean exit, as PC-06. | CT <= 3 s. |

## Batch 3 - Rapid opens, double starts, cooldown, retry (P0, ~25 min, ~15 streams)

| ID | Pri | Steps | Expected | Metric checks |
|---|---|---|---|---|
| RO-01 | P0 | A (frame) -> Back -> B within 1 s -> Back -> A, all within 3 s. Repeat 3x with >= 75 s gaps (provider locks A ~1/3 of times). | Either A plays at once, or first 403 -> `playback.cooldown.start`; `RecentlyLeftCooldownProgress` visible, staged status text; probes near 3/10/25/45/58/65 s; on 200/206 normal open + `curtain-lowered`; else final with plain message. No `playback.retry` for 403. | Cooldown total <= 65 s x1.1; probe times within +-10 %; bar value monotonic (UIA poll 1 s); no stall > 1000. |
| RO-02 | P0 | Trigger cooldown as RO-01 (retry until it occurs, max 3 tries, else NOT-TRIGGERED); at ~10 s press Back. | `playback.cooldown.end result=cancelled` within 500 ms; no later `cooldown.attempt`, `curtain-lowered` or audio for A. | Catalog responsive (ping < 250 ms). |
| RO-03 | P0 | During a cooldown open C instead. | Cooldown cancelled with its generation; C plays; no A frame (screenshots 0.5/1/2 s). | One playback chain. |
| RO-04 | P0 | During a cooldown press Alt+F4. | Exit clean, cooldown cancelled. | CT <= 3 s. |
| RO-05 | P0 | Double start: double-click a movie card (2 clicks 80 ms apart); later Enter twice within 100 ms on another. | Exactly one playback reaches `curtain-lowered`; a superseded generation stops cleanly (`playback.handoff-stopped`), no double audio, one `resume.save` on exit. | Count `playback.attempt` generations; no exception. |
| RO-06 | P0 | Four different items opened 500 ms apart (Back + open), ending on D. | Only D is shown; no earlier picture ever (screenshots every 0.5 s); no `final-failure` for superseded generations. | No stall > 1000; M. |
| RO-07 | P0 | Open a movie, Back at 300 ms; another, Back at 1 s; another, Back at 3 s. | `rapid-navigation-away` logged; nothing plays after Back (no `curtain-lowered`/`first_progress` after the Back timestamp); player never reappears. | No orphan engine activity 10 s after. |
| RO-08 | P1 | For every open in this batch poll player loading UI every 250 ms. | Retry/progress bar never visible < 1.5 s after open; if `playback.retry` occurs, delays 0.5/1/2/3 s, bar determinate and monotonic. | Opportunistic: NOT-TRIGGERED if no retry. |
| RO-09 | P1 | Log audit of all movie opens tonight. | No Movie attempt classified timeout before 25 s; no popcorn > 60 s without a status change (cooldown excepted). | Attempt durations table. |
| RO-10 | P1 | L1 -> L2 -> L1 within 3 s. | No cooldown for Live; "tuning" wording; no old frame. | One chain; M. |
| RO-11 | P1 | If any final failure appears tonight: invoke "Try again". | `player.reset reason=Retry`; fresh budget. | Opportunistic. |

## Batch 4 - Window states and pointer hiding during playback (P0, ~25 min, ~5 streams)

| ID | Pri | Steps | Expected | Metric checks |
|---|---|---|---|---|
| WS-01 | P0 | Catalog: F11 x20 at 1.5 s. | Bounds return exactly to windowed; no heap corruption. | No stall > 1000; WS growth < 50 MB; no `0xC0000374`/WER. |
| WS-02 | P0 | Movie playing: full -> cinema -> exit -> F11 -> cinema -> in-window mini -> "Return to player" -> window-mini -> "Return to player", 2 s apart; x5. | Video never black > 1 s after a transition (screenshot +1 s); video never in a separate window; no `playback.interrupt`/`host-not-ready`. | Enumerate process top-level windows each step; no stall > 1000; WS growth < 150 MB. |
| WS-03 | P0 | Enter window-mini; check `WS_EX_TOPMOST`; wait 30 s; `WindowFromPoint` at 5 points inside the mini. | Topmost throughout; no `topmost-reassert-failed`; hit test returns Tvivo hwnd. If another window wins the hit test: FAIL with evidence (repeat of 10-18 M1). | - |
| WS-04 | P0 | Minimise/restore (own button, then SW_MINIMIZE/SW_RESTORE) in each state: catalog, full player, cinema, in-window mini, window-mini. | Restored state equals the pre-minimise state (cinema stays cinema, mini stays mini); playback continues. | No `forced-exit`; M. |
| WS-05 | P0 | Cinema idle (repeat 10-18 C1/C2): focus Pause, idle; then Tab to `SleepTimerButton`, idle; move pointer 30 px. | `cinema.cursor-set hidden=True` and probe `cursorVisible=false` both times; move -> `cinema.show reason=pointer-poll`, cursor visible. | Hide latency recorded. |
| WS-06 | P0 | In-window mini over Tvivo's own window: idle until hide. Window-mini: idle until hide. | `compact.cursor-hide reason=idle` and probe `cursorVisible=false`. If the window-mini is over another app's window and hit test fails: BLOCKED (do not move other windows). | - |
| WS-07 | P1 | Hide cursor in cinema -> Esc; hide in in-window mini -> "Return to player"; hide in cinema -> Alt+F4. | Cursor visible after each (`GetCursorInfo`). | - |
| WS-08 | P1 | Window-mini: drag `CompactResizeEdgeRight` +100 px, move by drag region; exit; re-enter. Drag the mini partly off-screen; exit; re-enter. | Geometry restored; an off-screen mini comes back fully on a monitor. | Bounds before/after. |
| WS-09 | P1 | From fullscreen (F11) enter window-mini, then "Return to player". | Returns to fullscreen, not a borderless-windowed glitch. | Bounds = monitor. |
| WS-10 | P1 | Exit cinema reachability: in cinema with transport hidden, press Tab once, and separately move pointer. | Transport (with "Exit cinema") visible within 500 ms each; Esc exits (per D-c). | Latency recorded. |

## Batch 5 - Sleep timer combinations A (P0, ~23 min, ~5 streams)

Reference names/events: `docs/test-plan-sleep-timer.md` §1-2. Shortest timer is 5 min (custom).

| ID | Pri | Steps | Expected | Metric checks |
|---|---|---|---|---|
| ST-01 | P0 | Smoke: test-plan S1-S5 and S7 in one movie (presets, +15, Change, Off, Ctrl+Shift+T in full and cinema, Esc closes flyout only). | As test plan. | One `armed`/`extended`/`cancelled` line per action. |
| ST-02 | P0 | Arm custom 5 in full player; cinema; window-mini. At 3:00 warning in mini; at 5:00 expiry in mini. | `CompactSleepTimerWarningActions` once (no flashing, 5 s poll for 20 s); `resume.save completed=false` then `sleep-timer.ended`; `CompactSleepTimerEndedActions`; no Retry/popcorn; quiet 30 s (no playback lines). | Ended within 3 s of deadline. |
| ST-03 | P0 | New movie, window-mini, arm 15 from `CompactSleepTimerButton`, invoke `CompactCloseButton`. | `window-mini.close-teardown`, `sleep-timer.cancelled ... minutes=15`. | - |
| ST-04 | P0 | New movie, arm 15, Alt+F4; relaunch; open a movie. | `cancelled` (window-close) before exit; after relaunch timer button Name "Sleep timer" (not armed). | CT <= 3 s. |
| ST-05 | P0 | L1, arm custom 5, "Minimize window"; wait 5:40; restore. | `ended` within 3 s of deadline while iconic; Tvivo never restores itself; no `resume.save` (Live); "Watch this channel" visible, "Watch again" not. | IsIconic every 30 s; idle-ish CPU recorded. |
| ST-06 | P1 | Arm 15 on A; trigger RO-01 cooldown path. | Timer survives item change (no `cancelled`); status line still visible over cooldown poster; cooldown cancelled if timer ends (not reachable in 15 min: record only survival). | - |
| ST-07 | P1 | Arm 15; open the flyout; press F11; reopen it and resize the window via SetWindowPos to 776 DIP wide. | Flyout closes or repositions inside the window; never off-screen; no exception. | Flyout bounds inside window. |

## Batch 6 - Series, autoplay and sleep timer combinations B (P0/P1, ~22 min, ~8 streams)

| ID | Pri | Steps | Expected | Metric checks |
|---|---|---|---|---|
| SE-01 | P0 | S episode 1, then "Next episode". | `resume.save kind=Series` for ep1; `player.reset surfaceCleared=true`; no ep1 frame on ep2 (screenshots 0.5/1 s). | One chain. |
| SE-02 | P0 | Record "Autoplay next"; set ON; seek `ProgressSlider` to Max x 0.985. | `NextEpisodePrompt` appears; at end `player.reset reason=AutoplayNext`; ep+1 first frame; previous `resume.save completed=true`. | Prompt-to-start s recorded. |
| SE-03 | P1 | Autoplay OFF; seek near end. | No autoplay; end state shown; record whether prompt shows. | - |
| SE-04 | P0 | Test-plan X1/X3/X4: arm "At the end of this episode", seek near end. | `ended mode=EndOfCurrentItem`, `completed=true`; no `AutoplayNext`; `NextEpisodePrompt` must not appear (source note says it may: report). | Screenshot every 5 s. |
| SE-05 | P1 | Arm end-of-episode; press "Next episode". | `cancelled mode=EndOfCurrentItem`; notice "Sleep timer turned off after changing video" for ~4 s. | - |
| SE-06 | P1 | Autoplay ON; arm custom 5; at 3:00 warning, seek to end. | Prompt suppressed while warning shows; autoplay proceeds; duration timer survives into next ep and ends at 5:00 there. | Ended within 3 s of deadline. |
| SE-07 | P1 | "Next episode" x3 at 400 ms. | Ends on ep+3 (or clamps at last); no stale frame; superseded generations stopped. | No stall > 1000. |
| SE-08 | P1 | Episode in in-window mini: `CompactNextButton`, `CompactPreviousButton`; seek to end with autoplay ON. | Buttons work; autoplay stays in mini. | - |
| SE-09 | P1 | Movie in cinema, arm custom 5, idle (pointer hidden). At 3:00 Tab. | Warning visible although transport hidden; Tab reaches `SleepTimerWarningExtendButton`; cursor may stay hidden only until pointer moves. Restore Autoplay to recorded state. | - |

## Batch 7 - Spotlight strip (P1, ~20 min, <= 2 streams)

| ID | Pri | Steps | Expected | Metric checks |
|---|---|---|---|---|
| SP-01 | P1 | Movies page: read `SpotlightStripItems` children names. | <= 12 cards (12 on a large catalog); card 1 expanded; all Movie; `spotlight.selection page=Movies count=..`. | - |
| SP-02 | P1 | Series page; My Tvivo/For You page; Live page. | Series only; For You alternates Movie/Series; Live has no `SpotlightPanel`. | - |
| SP-03 | P1 | Hover card 3 for 500 ms; card 4 for 200 ms then leave; sweep pointer across all slivers at ~100 ms per card. | `spotlight.open index=3 via=pointer`; nothing for card 4; zero opens during the sweep. | - |
| SP-04 | P1 | Tab into strip; Right x15; Left x15; Tab; Shift+Tab; Enter on open card (Back at once if it plays). | Stops at 12 and at 1 (no wrap), `SpotlightPositionText` 12 of 12 / 1 of 12; Tab leaves; Enter = normal activation path. | - |
| SP-05 | P1 | After render, 60 s no input. | Zero `net.request`/`hover.metadata`/`perf.artwork` after 10 s settle; no animation CPU. | CPU <= 3 %. |
| SP-06 | P1 | Movies -> Series -> Movies x10; open details from card 2 and Back. | Same `dayHash` and order; card 2 still open after Back. | Artwork loads not repeated (count `perf.artwork`). |
| SP-07 | P1 | Movies: PgDn x40, PgUp x40, End/Home x5. | Spotlight art still present at top (no blank cards). | No stall > 1000; ping p95 < 100 ms; WS growth < 100 MB. |
| SP-08 | P1 | Hover cards 1 <-> 2 every 400 ms x30. | 30 opens; exactly one expanded card at end (UIA names); superseded lookups cancelled. | No stall > 1000. |
| SP-09 | P1 | Record Windows animation setting; set `SPI_SETCLIENTAREAANIMATION` off; switch cards; restore setting; switch again. | Off: final width at once. On: ~220 ms transition. If the setting cannot be set/restored: BLOCKED. | Setting restored (verify). |
| SP-10 | P1 | Resize window to the minimum (request 500 px wide); Right through the strip. | Min width enforced (~776 DIP, record); strip scrolls; open card fully inside viewer; position label visible. | Bounding rects. |
| SP-11 | P2 | Any open card without a summary. | "Summary unavailable", no invented text. | Opportunistic. |

## Batch 8 - Settings and Spotlight sources (P1, ~18 min, 0 streams)

Record every Spotlight setting first (mode per type, ticked count); restore at the end (SS-08).

| ID | Pri | Steps | Expected | Metric checks |
|---|---|---|---|---|
| SS-01 | P1 | Settings -> For You -> Spotlight: Movies = "Specific folders", tick 3, Save; go to Movies. | `spotlight.settings.saved` (modes + counts only); new `dayHash`; <= 12 movies. | - |
| SS-02 | P1 | Series = "Folders I haven't explored"; Cancel; reopen. | Previous value shown; no `saved` line. | - |
| SS-03 | P1 | Save/Cancel churn x30 (change, Save, change, Cancel). | Final reopen equals last Save; no exception. | No stall > 1000; WS growth < 50 MB; handles < +200. |
| SS-04 | P1 | Folder search: type 10 chars at 50 ms; Select all; Clear; Select all; Save. | List filters; Spotlight still <= 12. | Ping p95 < 250 ms while typing. |
| SS-05 | P1 | Specific folders with none ticked; Save (if allowed); open Movies. | Save disabled, or empty Spotlight with `SpotlightEmptySettingsButton` that opens Account -> For You focused on Spotlight. | - |
| SS-06 | P1 | Series = "Folders I haven't explored", Save; Series page. | Only series, never Live. | - |
| SS-07 | P0 | Privacy grep of this batch's lines. | No folder labels or titles in the log. | - |
| SS-08 | P1 | Restore recorded settings; Save. | Settings equal the recorded originals. | - |
| SS-09 | P1 | Change a source (dirty), toggle theme, then Cancel. | Dirty state handled; Cancel still restores; no exception. Restore dark. | - |

## Batch 9 - Navigation stress, search, refresh (P1, ~25 min, ~6 streams)

| ID | Pri | Steps | Expected | Metric checks |
|---|---|---|---|---|
| NV-01 | P1 | Cycle Home/Movies/Series/Live/My Tvivo every 200 ms x50. | Final page correct and populated. | No stall > 1000; `mode-swap-stalled` = 0; WS growth < 100 MB. |
| NV-02 | P1 | x5 different movies: open, "Go home" at 200 ms. | Nothing plays after Home; no player reappears. | As RO-07. |
| NV-03 | P1 | Relaunch; open search before `catalog.cache.load.complete`; type 3 letters. | No crash; results after load or a clear empty state. | No stall > 1000. |
| NV-04 | P1 | Type 20 chars at 50 ms, delete all; x5. | Results match the final text (last query wins). | Ping p95 < 150 ms. |
| NV-05 | P1 | In-window mini playing; search, type, open a details page. | Mini keeps playing; typing never triggers player keys (Space does not pause). | No `playback.toggle-deferred` from typing. |
| NV-06 | P1 | "Refresh catalog", then at once PgDn x10 and switch pages. | `catalog.cache.refresh.complete`; no duplicate shelves; scroll not reset unexpectedly (record). | No stall > 1000. |
| NV-07 | P1 | In-window mini playing; "Refresh catalog". | Playback uninterrupted. | No `playback.interrupt`. |
| NV-08 | P1 | Details open/Back x25 movies + x25 series (no play). | No growth trend. | WS slope < 2 MB/cycle after cycle 10; handles < +5/cycle. |
| NV-09 | P2 | Keyboard only: launch -> play a movie -> back to catalog (Tab/arrows/Enter/Esc). | Possible end to end; focused element always on-screen. (Focus visuals: OWNER-EYES.) | - |

## Batch 10 - Themes, narrow windows, resize storms (P1, ~22 min, ~3 streams)

| ID | Pri | Steps | Expected | Metric checks |
|---|---|---|---|---|
| TH-01 | P1 | Theme toggle x20 on Movies, Series, My Tvivo, Settings. Ends dark. | Toggle Name flips each time. | No stall > 1000; WS growth < 50 MB. |
| TH-02 | P1 | Theme toggle in full player and in window-mini while playing. | No reset, no interrupt. | - |
| TH-03 | P1 | Light theme screenshots: each page, player, mini, sleep-timer flyout/warning, Spotlight; back to dark. | Captured for owner judgement (legibility = OWNER-EYES). | - |
| TH-04 | P1 | Catalog: SetWindowPos width 776 <-> 1600 every 50 ms x100. | Layout settles < 1 s after storm; Spotlight open width = clamp(480, 42 %, 672) +-2 DIP. | No stall > 1000 after storm; WS growth < 100 MB. |
| TH-05 | P1 | Playing: resize storm x50 in full player; window-mini resize edges x20. | Video continues; no `host-not-ready`. | - |
| TH-06 | P1 | Request 500x400 window. | Min size enforced; header buttons inside window. | Bounding rects. |
| TH-07 | P1 | Player at minimum width. | All transport buttons inside window, none overlapping. | Bounding rects. |
| TH-08 | P2 | SW_MAXIMIZE / SW_RESTORE x10 on catalog and player. | Bounds restore exactly. | No stall > 1000. |

## Batch 11 - Endurance and leaks (P1, ~25 min, ~55 streams, budget exception)

| ID | Pri | Steps | Expected | Metric checks |
|---|---|---|---|---|
| EN-01 | P1 | 50 cycles rotating A-E (no item twice within 70 s): open, wait first frame (max 25 s) or 8 s, Back, 2 s idle; sample after each. Two cooldowns in a row -> pause 75 s; three -> stop, BLOCKED-provider. | All cycles finish; engines released (`engine.release`/`dispose` balanced). | After cycle 10: WS slope < 2 MB/cycle, handles < +5/cycle, GDI/USER < +2/cycle, threads back to baseline +-5. |
| EN-02 | P1 | One movie; window-mini enter/exit x30. | No orphan windows. | Handles/GDI flat (< +100 total). |
| EN-03 | P1 | One movie; Ctrl+Shift+T -> "15 minutes" -> flyout -> "Turn off sleep timer" x50. | `armed`/`cancelled` balanced. | Threads/handles flat. |

## Batch 12 - Native engine and log hygiene (P2, ~20 min, ~6 streams)

Switch Account -> Player -> "Playback engine" to Native (record original); relaunch; restore at end and relaunch.

| ID | Pri | Steps | Expected | Metric checks |
|---|---|---|---|---|
| NE-01 | P2 | Repeat PC-01, PC-02, PC-07 (full/cinema), RO-05, ST-01 on Native. | Same as LibVLC rows. | As those rows. |
| NE-02 | P2 | Native: invoke "Mini player". | Mini is LibVLC-only: control disabled/hidden or a clear message; no crash. | - |
| NE-03 | P2 | Native: old-picture check (invariants checklist: movie -> pause -> failing Live -> movie). | No old frame at any time. | Screenshots. |
| MS-01 | P2 | Audit all tonight's logs: rotation at 1 MB. | `.log.1` rotation, no exception, no gap. | - |

## Batch 13 - Idle soak (P2, ~35 min, 0 streams, time exception per D-d)

| ID | Pri | Steps | Expected | Metric checks |
|---|---|---|---|---|
| SK-01 | P2 | My Tvivo with Spotlight visible, foreground, no input, 30 min. | No requests after settle except scheduled `catalog.activity-refresh` (record cadence). | CPU avg <= 1 %, no 30 s window > 3 %; WS growth < 50 MB; ping max < 250 ms. |
| SK-02 | P2 | After soak: switch to Movies; hover a card 500 ms. | Page < 1 s; dwell works. | - |
| SK-03 | P2 | Minimised 5 min. | - | CPU <= 0.5 %. |

## Batch 14 - Playback soak (P2, ~35 min, 1 stream, time exception per D-d)

| ID | Pri | Steps | Expected | Metric checks |
|---|---|---|---|---|
| SK-04 | P2 | A long movie (duration >= 40 min), muted; 20 min full player, 5 min cinema with hidden pointer. | No `interrupt` unless network (then recovered); periodic `resume.save`. | CPU avg recorded; WS growth < 100 MB; handles < +300. |
| SK-05 | P2 | Same stream 5 min in window-mini, then Alt+F4. | Clean exit. | CT <= 3 s. |

## OWNER-EYES (not automatable or not allowed overnight; listed for FINAL.md)

| ID | Pri | What the owner checks |
|---|---|---|
| OE-01 | P1 | Display scale 150 % and 200 % (changing system DPI overnight affects her whole desktop): Spotlight sizes, mini panel, sleep-timer warning not clipped, buttons >= 44 px. |
| OE-02 | P1 | Moving Tvivo between monitors with different DPI (monitor DPI change). |
| OE-03 | P1 | Light theme legibility/contrast of all screens (from TH-03 screenshots plus live look); Windows Contrast themes. |
| OE-04 | P1 | Narrator: sleep-timer announcements once and politely; Spotlight "expanded, n of 12"; mini X button name per D-b. |
| OE-05 | P1 | Spotlight motion: no flash on fold/unfold, calm; do 64-DIP slivers explain themselves to a senior user. |
| OE-06 | P1 | Real taskbar right-click -> Close, and taskbar thumbnail with window-mini. |
| OE-07 | P2 | Audio on: no sound after timer expiry; volume kept for a fresh play. |
| OE-08 | P2 | Touch: Spotlight two-tap rule and hint. |
| OE-09 | P2 | Window-mini over other apps' windows (pointer hit test, 10-18 M1). |
| OE-10 | P2 | Keyboard focus visuals (NV-09) and calm look of ended/warning screens. |

## Counts

| Priority | Rows |
|---|---|
| P0 | 38 (all automated) |
| P1 | 63 (57 automated + 6 OWNER-EYES) |
| P2 | 16 (12 automated + 4 OWNER-EYES) |
| Total | 117 (107 automated + 10 OWNER-EYES) |
