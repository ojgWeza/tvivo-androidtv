# Test plan: sleep timer v1 (Haiku tester)

Written 2026-10-15 (Opus) from source on `codex/winui-catalog-design-plan`, slices 1-4 (head 8bb3418).
v1 stops directly at zero. There is NO audio fade; do not test or report one.
Sources: `docs/sleep-timer-plan.md` §1-3 and §6, `docs/player-invariants.md`, `MainWindow.xaml(.cs)`
(search `SleepTimer`), `src/Tvivo.Core/SleepTimerPolicy.cs`. Format follows `docs/test-plan-player-round.md`.

## 0. Ground rules (read twice)

- The OWNER launches Tvivo (Debug build). You never launch, close, kill or rebuild it, and you never edit
  source, settings files or logs. Never delete anything.
- No improvising. If a step cannot be done exactly as written: record SKIPPED + why, go on. Anything you notice
  outside the plan goes under "Other observations" as a plain description; do not invent a check for it.
- Software input only (UI Automation first; SendInput only where a step says "press"). Before EVERY input burst,
  confirm the foreground window belongs to `Tvivo.App`. If not: stop input, record it, wait for the orchestrator.
- Never touch, close or type into any other window. The owner's terminal may be minimised with a plain minimise
  only (no close, no move, no typing).
- DARK theme for the whole round. The header theme toggle is named for what it switches TO. If its Name is
  "Switch to dark theme", invoke it once, record that, and switch back at the end. If it is "Switch to light
  theme", you are already dark: leave it.
- Audio MUTED: before the first playback, record the Windows mute state, then mute. If you cannot read it, do not
  toggle blindly; use Tvivo's own "Mute" button at every playback and record that. Restore at the end.
- Real provider streams are allowed, one at a time. Timer runs need ~5-6 min of one stream; everything else is
  as short as the step needs. Never print or save URLs, hosts, usernames or item titles (write `<TITLE>`).

## 1. Paths, names, facts

- Log: `%LOCALAPPDATA%\Tvivo\tvivo-launch.log` (+ `.log.1` after 1 MB). Use only lines with the `session=` id of
  the current `event=app.start` line. Copy the timestamp of every line you cite.
- Screenshots: `D:\Scratch\sleep-timer-<yyyyMMdd-HHmm>\<step>-<n>.png` (new folder each round).
- Shortest timer is 5 minutes (custom field, 5-240 in steps of 5). There is NO debug shortcut or faster clock;
  do not look for one. So: warning appears at 3:00 after arming (2:00 left); expiry at 5:00.
- Find controls by UIA Name; where a Name repeats, use AutomationId (WinUI uses the x:Name).

| What | Name / AutomationId |
|---|---|
| Timer button, full player | Name "Sleep timer" when off, "Sleep timer, N minutes left" / "..., N seconds left" when armed; Id `SleepTimerButton` |
| Flyout, choices | buttons "Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "90 minutes", "At the end of this movie" / "...episode", "Choose minutes…" |
| Flyout, armed | `SleepTimerFlyoutStatusText`; "Add 15 minutes to sleep timer"; "Change timer"; "Turn off sleep timer" |
| Custom entry | NumberBox "Minutes, from 5 to 240 in steps of 5"; buttons "Start timer", "Cancel" |
| Status line | `SleepTimerStatusText` in `SleepTimerStatusPanel`, e.g. "Sleep timer · 4 min left", "... · 45 sec left" |
| Warning (full) | `SleepTimerWarningPanel`, text "Playback will stop in 2 minutes", Ids `SleepTimerWarningExtendButton`, `SleepTimerWarningCancelButton` |
| Ended screen (full) | `SleepTimerEndedOverlay`: "Sleep timer ended", "Playback has stopped.", `SleepTimerWatchAgainButton` ("Watch again"), `SleepTimerWatchChannelButton` ("Watch this channel"), `SleepTimerBackButton` ("Back to catalog") |
| Mini player | `CompactSleepTimerButton` (Name "Sleep timer..."), `CompactSleepTimerStatusText` ("Timer: 4 min", "Timer: End of item"), `CompactSleepTimerWarningActions` ("Playback stops in 2 minutes"), `CompactSleepTimerEndedActions` |
| Other | "Pause", "Cinema mode", "Mini player", "Return to player", "Minimize window", "Next episode", "Autoplay next", Retry = "Try again" (`RetryPlaybackButton`), popcorn art = "Loading" (`PlayerLoadingArt`), `NextEpisodePrompt` |

Mini player works on LibVLC only. Ctrl+Shift+T works only in the full/cinema player, by design not in mini.

## 2. Log events

| `event=` | Proves |
|---|---|
| `sleep-timer.armed mode=Duration|EndOfCurrentItem minutes=M` | timer set (end-of-item logs minutes=0) |
| `sleep-timer.extended mode=Duration minutes=M` | +15 applied; M = total chosen minutes so far |
| `sleep-timer.cancelled mode=.. minutes=M` | turned off (user, Back, Home, mini close, item change, final failure) |
| `sleep-timer.ended mode=.. minutes=M` | timer stopped playback |
| `resume.save kind=Movie|Series positionMs=P completed=false|true` | position saved (episodes log kind=Series; Live writes none) |
| `player.reset reason=.. surfaceCleared=true` / `player.curtain-lowered` / `playback.start.ref` | a new playback began / showed a frame |
| `playback.interrupt`, `playback.resume*`, `playback.retry`, `playback.final-failure` | recovery or failure; must be ABSENT around timer stops |
| `window-mini.enter` / `.exit` / `.close-teardown`, `cinema.show reason=sleep-timer-shortcut` | view transitions |

Rule: every PASS and every FAIL cites at least one exact log line or UIA reading, plus a screenshot where named.
Lines must contain no titles, URLs or hosts; if one does, report it as a privacy FAIL with the value redacted.

## 3. Pre-checks (stop the round if any fails)

P1 Tvivo is open (owner-launched), `app.start` line found; record config, engine, theme. P2 Free RAM >= 2500 MB.
P3 Mute state recorded and muted (or Tvivo-mute fallback recorded). P4 Last log line recorded before step S1.
P5 Engine is LibVLC for sections S-W (Account -> Player -> "Playback engine"). If it is Native, ask the
orchestrator; the owner must switch and relaunch. Section N runs on Native after an owner relaunch.

## S. Choices and presets (no waiting; any movie; ~2 min of stream)

S1 Open a movie, wait for `player.curtain-lowered`. Invoke "Sleep timer". Expected: flyout shows "Off", the five
presets, "At the end of this movie", "Choose minutes…". Screenshot S1-1.
S2 Invoke "15 minutes". Expected: `sleep-timer.armed mode=Duration minutes=15`; status "Sleep timer · 15 min left"
(or 14); button Name "Sleep timer, 15 minutes left" (or 14). Screenshot S2-1.
S3 Invoke the timer button again. Expected: armed panel (status text, "Add 15 minutes to sleep timer", "Change
timer", "Turn off sleep timer"), presets hidden. Invoke "Add 15 minutes to sleep timer". Expected:
`sleep-timer.extended mode=Duration minutes=30`; status 29-30 min left.
S4 Open flyout -> "Change timer" -> "45 minutes". Expected: `armed ... minutes=45`. Repeat Change timer for 60, 90,
30. Expected one `armed` line each with the right minutes.
S5 Open flyout -> "Change timer" -> "Off". Expected: `sleep-timer.cancelled mode=Duration minutes=30`; status panel
gone; button Name "Sleep timer".
S6 Open flyout -> "Choose minutes…". Expected: NumberBox value 30 with its full Name. Invoke "Cancel". Expected: no
new `armed` line. Screenshot S6-1 before Cancel.
S7 Ctrl+Shift+T: give focus to the player (invoke nothing; click the video once only if focus is elsewhere, and
record any pause toggle). Press Ctrl+Shift+T. Expected: flyout opens. Press Escape. Expected: flyout closes,
still on the player (no catalog). Click "Cinema mode", press Ctrl+Shift+T. Expected: flyout opens and
`cinema.show reason=sleep-timer-shortcut`. Escape, exit cinema.
S8 Duration survives a manual change: arm "15 minutes". In the player list pick a different movie. Expected:
`player.reset reason=NewItem`, NO `sleep-timer.cancelled`, status still shows ~15 min left. Then flyout ->
"Turn off sleep timer": `cancelled mode=Duration minutes=15`.

## T. Run A: 5-minute timer, warning, Add 15, Turn off (LibVLC, one movie, ~4 min)

Write down the clock time of every action. All times below are "since Start timer".
T1 Flyout -> "Choose minutes…" -> set NumberBox to 5 (UIA RangeValue or Value; else select-all and type 5) ->
"Start timer". Expected: `armed mode=Duration minutes=5`; status "· 5 min left"; button Name
"Sleep timer, 5 minutes left". Screenshot T1-1.
T2 (0:15) Invoke "Pause". Wait 70 s. Read status at 0:15 and at 1:25. Expected: the minutes value drops by at
least 1 while paused (timer keeps counting). Zero `playback.interrupt` lines. Invoke "Pause" again (play).
T3 (~1:30) Click "Cinema mode". Expected: status line still visible over the video (screenshot T3-1). Exit cinema.
T4 (~1:50) Invoke "Mini player". Expected: `CompactSleepTimerStatusText` = "Timer: N min", `CompactSleepTimerButton`
Name "Sleep timer, N minutes left". Press Ctrl+Shift+T: expected nothing opens (by design). Invoke "Return to
player". Expected: full player, status still shown, NO `sleep-timer.cancelled` since T1. Screenshot T4-1 in mini.
T5 Stay in the full player. At ~3:00 (status "2 min left"): expected `SleepTimerWarningPanel` visible with
"Playback will stop in 2 minutes" and both buttons (screenshot T5-1). No next-item prompt visible. Poll every 5 s
for 20 s: the panel stays as one panel, it is not re-shown/flashed.
T6 Invoke `SleepTimerWarningExtendButton`. Expected: `extended mode=Duration minutes=20`; warning gone; status
about "17 min left" (16-17). This proves +15 adds to the deadline, not restarts at 15.
T7 Flyout -> "Turn off sleep timer". Expected: `cancelled mode=Duration minutes=20`; status gone; button Name
"Sleep timer". PASS T: all of T1-T7 true. Go Back to catalog.

## U. Run B: expiry in the full player (LibVLC, movie M1, then movie M2, ~7 min)

U1 Open movie M1 (record its card position, not its title). After `player.curtain-lowered` wait 20 s. Screenshot
U1-ref (the "old picture" reference). Arm custom 5 (as T1). Close the flyout. Do not touch anything else.
U2 At ~3:00 confirm the warning appears (do not click). At ~4:20 read status: expected "· N sec left" format.
U3 At 5:00 (+3 s). Expected, in this order: `resume.save kind=Movie positionMs=P completed=false` (P > 0), then
`sleep-timer.ended mode=Duration minutes=5`. Copy both.
U4 Ended screen (UIA + screenshot U4-1 and U4-2 at +10 s): `SleepTimerEndedOverlay` visible with "Sleep timer
ended", "Playback has stopped.", "Watch again" visible, "Watch this channel" NOT visible, "Back to catalog"
visible. NOT present/visible: "Try again", "Loading" (popcorn art), `NextEpisodePrompt`, `SleepTimerWarningPanel`.
"Pause" disabled. Screenshot shows no part of the U1-ref frame.
U5 Quiet check: wait 60 s, then press Space once. Expected: no new `player.reset`, `playback.start.ref`,
`playback.retry`, `playback.interrupt`, `playback.resume*`, `playback.final-failure`, `content-selected` lines
after `sleep-timer.ended`; still the same ended screen (screenshot U5-1).
U6 Invoke "Watch again". Expected: `player.reset reason=.. surfaceCleared=true`, then `player.curtain-lowered`;
overlay gone; status panel empty and button Name "Sleep timer" (timer did not re-arm). Read `ElapsedText`:
record whether it resumed near P (within 30 s) or showed a resume prompt or started at 0. Record; the expected
answer is "resumed near P or offered resume". Any `resume.save` line between the click and `player.reset` must
not show a lower positionMs than P and must say completed=false.
U7 Invoke "Back to catalog" (player header). Open a DIFFERENT movie M2. Screenshots at 1, 2, 3 s after the click
and once after `player.curtain-lowered`. Expected: `player.reset reason=NewItem surfaceCleared=true`; no
pre-frame screenshot shows any part of the U1-ref frame (dark curtain/popcorn only). Back to catalog.
U8 In-progress check: find M1's card. Expected: an in-progress bar (not "watched"); its row/card
ProgressAutomationName, if readable, says in progress. Screenshot U8-1.

## V. Run C: mini window, warning and expiry there (LibVLC, one movie, ~6 min)

V1 Open a movie, after curtain-lowered click "Cinema mode", then "Mini player". Expected: `window-mini.enter`.
V2 Invoke `CompactSleepTimerButton`. Expected: flyout opens from the mini button. Choose minutes -> 5 -> Start.
Expected: `armed ... minutes=5`; `CompactSleepTimerStatusText` "Timer: 5 min" (or 4). Screenshot V2-1.
V3 At ~3:00: `CompactSleepTimerWarningActions` visible with "Playback stops in 2 minutes" and buttons Named
"Add 15 minutes to sleep timer", "Turn off sleep timer". Screenshot V3-1. Do not click.
V4 At 5:00: `resume.save kind=Movie ... completed=false`, `sleep-timer.ended`; `CompactSleepTimerEndedActions`
visible ("Sleep timer ended", "Back to catalog"); no Retry, no popcorn. Screenshot V4-1. Quiet 30 s as U5.
V5 Invoke that "Back to catalog". Expected: `window-mini.exit` and/or `window-mini.close-teardown`, catalog shown,
no small window left (enumerate Tvivo top-level windows).
V6 Mini close cancels: open a movie, cinema -> Mini player, arm "15 minutes" from the mini button, then invoke the
mini window's close (X, `CompactCloseButton`). Expected: `window-mini.close-teardown` and
`sleep-timer.cancelled mode=Duration minutes=15`.

## W. Run D: Live, Back cancels, minimised expiry (LibVLC, one Live channel, ~7 min)

W1 Open any Live channel. Open the flyout. Expected: NO "At the end of this ..." button in the UIA tree; presets
and "Choose minutes…" present. Screenshot W1-1. Invoke "15 minutes", then "Back to catalog". Expected:
`sleep-timer.cancelled mode=Duration minutes=15`.
W2 Reopen the same Live channel, arm custom 5. Invoke "Minimize window" (Tvivo's own title-bar button).
W3 Do not touch Tvivo for 5 min 40 s. Every 30 s check Tvivo is still minimised (IsIconic) and is not the
foreground window. Expected: `sleep-timer.ended mode=Duration minutes=5` within 3 s of the 5:00 mark while
minimised; no `resume.save` (Live saves none); no recovery/retry lines; Tvivo never restored itself.
W4 Restore Tvivo (ShowWindow SW_RESTORE on its own window only), confirm foreground. Expected: ended screen with
"Watch this channel" visible and "Watch again" NOT visible. Screenshot W4-1.
W5 Press Escape. Expected: catalog (Escape follows Back). If it stays, record.

## X. End of movie/episode (LibVLC, one series with >= 3 episodes, ~3 min)

X0 Open a series, start episode 1. Record the "Autoplay next" toggle state; turn it ON for this section.
X1 Flyout: expected button "At the end of this episode". Invoke it. Expected: `armed mode=EndOfCurrentItem
minutes=0`; status "Sleep timer · Stops when this episode ends"; no countdown.
X2 Manual change: invoke "Next episode". Expected within 4 s: `sleep-timer.cancelled mode=EndOfCurrentItem
minutes=0` and status text "Sleep timer turned off after changing video" (read UIA within 2 s; it clears after
~4 s). Screenshot X2-1.
X3 On the now-playing episode, arm "At the end of this episode" again. Seek near the end: read `ProgressSlider`
RangeValue Maximum, set Value to Maximum*0.985 (if the slider cannot be set by UIA: SKIPPED, owner item).
X4 Wait for the end (max 90 s). Expected: `sleep-timer.ended mode=EndOfCurrentItem minutes=0` and
`resume.save kind=Series ... completed=true`; ended screen with "Watch again"; NO `player.reset
reason=AutoplayNext`, no `content-selected`/`playback.start.ref` after `ended`. Screenshot every 5 s from the
seek: per spec the "Next episode: ..." prompt (`NextEpisodePrompt`) must NOT appear before the stop.
(Source note: the prompt is only suppressed for a duration warning, so this may FAIL; report what you see.)
X5 Back to catalog. Restore "Autoplay next" to its X0 state.

## N. Native engine repeat (needs owner relaunch on Native)

N1 Repeat S2, S7, T1+T5+T6+T7 (Run A without T4) and U1-U7 (Run B) on Native. Skip V (mini is LibVLC only).
If the owner cannot relaunch: SKIPPED.

## O. Owner-eyes-only (tester records SKIPPED: owner)

O1 Contrast/legibility of status line, warning, ended screen and flyout in Dark; Windows Contrast theme.
O2 Display scale 150% and 200%: no clipped labels, buttons still >= 44 px, warning not off-screen, mini panel fits.
O3 Narrator speech: arming ("Sleep timer set for N minutes"), two-minute warning and "Sleep timer ended" are
announced once, politely, and countdown updates are NOT read out.
O4 Keyboard alone: Tab order through flyout, warning and ended screen; visible focus; no trapped focus.
O5 Pointer alone in cinema: warning and ended screen stay visible when the transport fades.
O6 Audio on (unmuted, owner present): no sound after expiry on both engines; a fresh play after expiry has the
viewer's previous volume.
O7 Calm look: no flashing, pulsing or repeated pop-ups; the ended screen reads as a calm, static stop, not an error.
O8 Natural end of a full movie with "At the end of this movie" (if X3 was SKIPPED or for the movie wording).

## 4. Wrap-up

Leave Tvivo open on the catalog (owner closes it). Restore theme if changed, "Autoplay next" (X5) and mute state.
No helpers left running. Fill the template.

## 5. Results template

```
Round: <date time>  Build: Debug  Engine(s): <..>  Tester: Haiku  Session id(s): <..>
Pre-checks: P1 __ P2 freeMB=__ P3 mute-before=__ method=__ P4 __ P5 __
Results folder: D:\Scratch\sleep-timer-________

| Step | Engine | PASS/FAIL/SKIPPED | Evidence (exact line or UIA value, titles -> <TITLE>) | Screenshot(s) |
|---|---|---|---|---|
| S1-S8 choices/presets/shortcut | | | | S* |
| T1 arm 5 / T2 pause counts / T3 cinema / T4 mini | | | status at 0:15=__ 1:25=__ | T* |
| T5 warning once / T6 +15 (=17 min) / T7 off | | | | T5-1 |
| U3 save + ended order / U4 screen / U5 quiet | | | P=__ | U4-*, U5-1 |
| U6 Watch again (resumed at __) / U7 old picture / U8 in progress | | | | U* |
| V1-V6 window mini | | | | V* |
| W1 Live no end-of-item + Back cancels | | | | W1-1 |
| W2-W5 minimised expiry | | | ended at __ vs deadline __ | W4-1 |
| X1-X2 end-of-episode + manual change notice | | | | X2-1 |
| X3-X4 genuine end, no next episode, prompt seen? | | | | X* |
| N Native repeat | | | | |
| O1-O8 | | SKIPPED: owner | | |

Other observations (outside the plan, described only): ______
Wrap-up: theme __ autoplay __ mute __ no helpers __
```
