# Android full pre-release QA plan (2026-10-19)

**App:** `com.dev.Tvivo`, branch `android/finalize-parity`, existing debug APK `app/build/outputs/apk/debug/app-debug.apk` (record size + mtime; never build).
**Tester:** Claude Haiku via adb. Follow steps literally. Do not edit code or app files. Write only evidence and the report.
**Precondition:** the orchestrator has recorded owner approval for emulator launch and `adb install` for this plan. If you were not told this, STOP.
**Evidence root:** `/d/HCode/Tvivo/.qa/full-qa-2026-10-19/<RUN>/`. **Report:** `/d/HCode/Tvivo/.qa/full-qa-2026-10-19/report.md`.

Priority: **P0** = release blocker (crash, data loss, stream on TV, privacy leak, dead end), **P1** = must fix before 1.0 unless the owner waives it, **P2** = polish.
Result values: PASS / FAIL / BLOCKED / OBS. After two failures of the same step, mark it FAIL and move on (no third try).

> **Known from code reading (2026-10-19), expect it to show:** the Android player overlay has only three states, `Reconnecting…`, a bare **Retry** button, and `Playback stopped`. Unlike WinUI (commit dac24d0) it shows no cause text and no retry progress. The PB cases that check cause text will probably FAIL. Run them anyway: crash, recovery and connection behaviour are still being tested. Tag those rows `GAP-INFO`.

---

## 0. Hard rules (any breach: stop the run, report)

1. **Never** run `pm clear`, `adb uninstall`, "Clear storage/cache", or **Sign out**. The Tink keyset cannot be recovered. To reach Login, use only Settings → **Sign in to a different account** (phone/mock only).
2. **Never run Gradle** (no `gradlew`, no build) while any emulator is up. Use the existing APK.
3. **`tvivo_tv34` is a REAL provider account:** D-pad keyevents only. **No Play** of anything (no detail Play button, no Continue, no trailer, no Live channel open). **No Deep stream check** (dialog: Cancel only). **Run check** at most once. **Refresh library** at most once. Search only through the UI.
4. One emulator at a time, booted headless: `cd /d/HCode/Tvivo && EMU_HEADLESS=1 EMU_MEMORY=3072 bash tools/emulator.sh <avd>` (run in background, wait for `==> booted`).
5. Never put credentials, provider host names, real titles from the TV account, or full IPs in the report or evidence file names. On TV screenshots, only describe layout, not content titles.
6. Python is `C:/Python313/python.exe` only.

## 1. Shorthands

- `ADB` = `/d/dev/android-sdk/platform-tools/adb.exe` (Git Bash). `PKG` = `com.dev.Tvivo`. `MAIN` = `com.dev.Tvivo/.MainActivity`.
- `UI` = `C:/Python313/python.exe C:/Users/Dell/AppData/Local/Temp/qa/ui.py` with subcommands `list` (all text/desc + bounds), `has "<text>"` (exit 0 if found), `tap "<text>" [index]` (`EXACT=1` for exact match), `tapxy X Y`.
- `SHOT <name>` = `ADB exec-out screencap -p > /d/HCode/Tvivo/.qa/full-qa-2026-10-19/<RUN>/<name>.png`.
- `KEY n` = `ADB shell input keyevent n`: 19 up, 20 down, 21 left, 22 right, 23 center, 4 back, 3 home, 26 power, 82 menu, 85 play/pause.
- `CTL` = `curl -s http://127.0.0.1:8089/__ctl` (GET state). `CTLSET '<json>'` = `curl -s -X POST http://127.0.0.1:8089/__ctl -H "content-type: application/json" -d '<json>'`.
- **RESET** = `CTLSET '{"maxConnections":1,"rejectSecondStatus":403,"stallAfterBytes":-1,"stallSeconds":0,"eofPercent":100,"catalog429":0,"killSwitch":false}'`.
- `COLD` = `ADB shell am force-stop PKG; ADB shell am start -W -n MAIN`, recording `TotalTime`.
- `MEM <tag>` = `ADB shell dumpsys meminfo PKG > <RUN>/mem-<tag>.txt`. Record `TOTAL PSS`, `Java Heap`, `Native Heap`, `Graphics`.
- `GFX0` = `ADB shell dumpsys gfxinfo PKG reset`. `GFX <tag>` = `ADB shell dumpsys gfxinfo PKG > <RUN>/gfx-<tag>.txt`. Record `Total frames rendered`, `Janky frames` (%), `90th percentile`, `99th percentile`.
- `MS` = `ADB shell dumpsys media_session | grep -E "state=|description="` (state 3 = playing, 2 = paused, 1 = stopped).
- `SWIPEUP` (phone scroll down) = read `ADB shell wm size` once as W×H, then `ADB shell input swipe W/2 0.78H W/2 0.25H 180`.
- Rotation: `ADB shell settings put system accelerometer_rotation 0`, then `ADB shell settings put system user_rotation 0|1` (0 portrait, 1 landscape).
- Network: `ADB shell cmd connectivity airplane-mode enable|disable`; `ADB shell svc wifi disable|enable`; `ADB emu network speed full|edge|gsm`; `ADB emu network delay none|gprs|umts`. If `adb emu` refuses (auth), mark the case BLOCKED.

**Mock catalog (phone):** Movies `Synthetic Film 01`…`24` (category "Generated Movies"; all serve the same long `movie.mp4`); Series `Synthetic Series 01`…`16` ("Generated Series"; each has Season 1 with `Episode 1` and `Episode 2`, 2 min each); Live `Generated Live Loop` plus `Synthetic Live Channel 02`…`16` ("Generated Live"; live loops forever). The mock has **no artwork**, so the artwork-fallback tiles are expected. Image-cache checks run on TV only.

**Emulator "not responding" dialog:** if it names a non-Tvivo process, tap **Wait** and record OBS. If it names Tvivo, `SHOT ANR-<id>`, mark the step FAIL (P0) and STOP the run.

## 2. Per-run setup and teardown

**Setup:**
1. `mkdir -p /d/HCode/Tvivo/.qa/full-qa-2026-10-19/<RUN>`. Confirm `Get-Process java` (PowerShell) shows no Gradle/Kotlin daemon. If one is running, report it and do not boot.
2. Phone runs: check that `tools/mock-provider/media` has `movie.mp4`, `live.ts`, `episode-A1.mp4`, `episode-B1.mp4`. Start `node tools/mock-provider/server.mjs` from the repo root (background). `CTL` must return JSON with `"activeConnections":0`. Then RESET.
3. Boot the AVD (§0.4). Run `ADB install -r <apk>` and expect `Success`.
4. `ADB logcat -c`. `ADB shell settings put system font_scale 1.0`. Set rotation to portrait (phone). Then `ADB shell am start -n MAIN`, wait 10 s, `SHOT 00-launch`. The app must open signed in on **My Tvivo**. If Login appears: STOP. Do not sign in on TV.

**Teardown (every run, also after a STOP):**
1. Restore: font_scale 1.0; `ADB shell cmd locale set-app-locales PKG --user 0 --locales en-US`; in-app Theme **Dark** and Language **English** (or System default); `ADB shell wm size reset; ADB shell wm density reset`; rotation 0 and `accelerometer_rotation 1`; airplane mode off; Wi-Fi on; `emu network speed full`, `delay none`; `ADB shell cmd uimode night no`. Phone: RESET, and stop any background `curl` (`taskkill //IM curl.exe //F`).
2. **Logcat gate:** `ADB logcat -d > <RUN>/logcat.txt` and `ADB logcat -d -b crash > <RUN>/crash.txt`, then `grep -nE "FATAL EXCEPTION|ANR in com\.dev\.Tvivo|Process: com\.dev\.Tvivo" <RUN>/logcat.txt`. PASS only if grep prints nothing and crash.txt has no `com.dev.Tvivo` line. Also run `grep -ciE "StrictMode|Skipped [0-9]{2,} frames"` and record the count as OBS.
3. `ADB emu kill`. `ADB devices` must list no emulator. Phone: stop the mock (PowerShell `Get-NetTCPConnection -LocalPort 8089 -State Listen | % { Stop-Process -Id $_.OwningProcess }`). Then `CTL` must fail.
4. Report any `java` process still alive. Do not kill it, and do not run Gradle.

**STOP the run** (go to teardown, then report) on: a Tvivo crash or ANR; the Login screen appearing unexpectedly; install failure; **any stream starting on TV**; a credential, host or full IP visible on screen or in an export; the emulator frozen for more than 2 min.

## 3. Run schedule (each run 45 min or less; one emulator at a time)

| Run | AVD | Scope | Est. |
|---|---|---|---|
| P1 | phone34 | Adaptive UI: portrait/landscape, light/dark, font scale, Arabic/RTL, small/large screens | 45 |
| P2 | phone34 | Performance and caching | 40 |
| P3 | phone34 | Informative playback and failure handling | 45 |
| P4 | phone34 | PiP/mini player, notification, lock screen, sleep timer, resume | 45 |
| P5 | phone34 | Heavy usage: rapid nav, back/forward, pickers, rotation storm, many favourites/history | 40 |
| P6 | phone34 | Long session soak (30 min live) | 40 |
| P7 | phone34 | Network diagnostics page (passive, quick, deep, timeline, export) | 45 |
| P8 | phone34 | Scenario flows: new user, returning user, flaky Wi-Fi, provider down | 40 |
| T1 | tv34 | TV layout, D-pad reachability/focus/discoverability, appearance, RTL | 45 |
| T2 | tv34 | TV performance, deep scroll, caching/offline/image cache, heavy D-pad, netdiag (passive + one quick check) | 45 |

Order: P1 → P2 → P3 → P4 → P5 → P6 → P7 → P8 → T1 → T2. Full teardown between runs. After a P0 FAIL, report before starting the next run.

---

## Run P1: phone adaptive UI (mock)

| ID | P | Steps | Expected | Pass if | Evidence |
|---|---|---|---|---|---|
| P1-01 | P0 | Portrait. Visit each top destination: My Tvivo, For You, Movies, Series, Live TV, Search, Settings. | Phone layout: touch navigation (not a TV focus layout), no focus frames by default, content starts below the status bar, nothing under the gesture bar. | All 7 open; every label is fully visible; no overlap | P1-01-<dest> |
| P1-02 | P1 | Movies → open "Generated Movies". `UI list`. | Grid of 2–3 columns in portrait; card titles readable; tap targets at least about 48 dp (bounds height ≥ 120 px at xxhdpi). | Columns 2–3; no clipped titles | P1-02-grid-port |
| P1-03 | P1 | Rotate to landscape (user_rotation 1) on Movies grid, Series detail (Synthetic Series 01), Search with query `film`, Settings. | Layout reflows to more columns; scroll position and query are kept; no blank screen. | State kept on all 4; columns increase | P1-03-<screen>-land |
| P1-04 | P1 | Back to portrait on the same 4 screens. | Returns to portrait layout; same item still visible. | Same as P1-03 | P1-04-<screen>-port |
| P1-05 | P1 | Settings → Appearance → Theme → **Light**. Repeat shots of My Tvivo, Movies grid, Series detail, Search, Settings, category picker, item context menu (long-press a film). | Light palette everywhere: no white-on-white, no dark islands, readable focus/pressed states, readable artwork fallback tiles. | Every text legible in every shot | P1-05-light-<screen> |
| P1-06 | P2 | Still Light: `ADB shell cmd uimode night yes`, wait 3 s, then `night no`. | The app theme stays Light (app setting wins); no activity restart loop or flash to dark. | Theme unchanged; no crash | P1-06-uimode |
| P1-07 | P1 | Theme → Dark. Repeat the P1-05 screen set (spot check 3). | Dark restored everywhere. | — | P1-07-dark |
| P1-08 | P0 | `font_scale 1.3`, then **2.0**. At each scale: My Tvivo, Movies grid, Series detail, Settings, Player overlay (Synthetic Film 01, tap once to show controls), Login-free dialogs (Exit dialog via Back from My Tvivo; tap **Stay**). | Text grows; nothing clipped mid-word; buttons not overlapping; rows wrap or scroll; Settings rows fully reachable by scrolling. At 2.0 small truncation with ellipsis is acceptable on card titles only. | No overlap/clipping outside card titles; every action still tappable | P1-08-f13-*, P1-08-f20-* |
| P1-09 | P2 | `font_scale 0.85`. My Tvivo, Settings. | Nothing becomes unreadably small; layout not broken. | — | P1-09-f085 |
| P1-10 | P0 | font_scale 1.0. Settings → **Language** → **العربية**. Do not restart. | The app switches to Arabic (immediately or after its own recreate) and the layout mirrors: nav/headers start on the right, back arrows point right. | Arabic strings + mirrored layout without a manual relaunch | P1-10-ar-home |
| P1-11 | P1 | In Arabic: Movies grid, category picker, Series detail episode list, Search (type `film`), Settings, Player overlay. | Mirrored and usable. Player rewind/play/forward stay in **physical** order (rewind on the left). Latin titles render correctly inside RTL rows. Leftover English is OBS, not FAIL. | No crash; mirrored; player order physical | P1-11-ar-* |
| P1-12 | P1 | Language → **English**. Then `ADB shell cmd locale set-app-locales PKG --user 0 --locales ar`, force-stop, relaunch; then set it back to `en-US`. | The system per-app locale is honoured, and the in-app Language row shows the matching choice. | Both paths switch language | P1-12-syslocale |
| P1-13 | P1 | Small screen: `wm size 720x1280; wm density 320`. Relaunch. My Tvivo, Movies grid, Series detail, Settings, Player overlay. | Compact layout; no horizontal overflow; nav fully visible; player controls not overlapping. | All actions reachable | P1-13-small-* |
| P1-14 | P1 | Large/tablet: `wm size 1600x2560; wm density 320`. Relaunch. Same screens, portrait and landscape. | Tablet layout: more columns, sensible max widths, not just a stretched phone. Still not the TV layout (touch, no forced focus frames). | Distinct from both phone and TV | P1-14-tab-* |
| P1-15 | — | `wm size reset; wm density reset`. Teardown + logcat gate. | — | Logcat gate PASS | logcat.txt |

## Run P2: phone performance and caching (mock)

Thresholds below are emulator baselines (headless, software GPU). Always record the raw numbers even when a case passes.

| ID | P | Steps | Expected | Pass if | Evidence |
|---|---|---|---|---|---|
| P2-01 | P0 | `COLD` 5 times, waiting 10 s between runs. Take `SHOT` 3 s after the last start. | The app reaches My Tvivo with content, not a spinner. | Median TotalTime ≤ 3000 ms; no run > 2× median; content visible at 3 s | P2-01-cold.txt, shot |
| P2-02 | P1 | Warm start: Home (`KEY 3`), then `ADB shell am start -W -n MAIN` 3 times. | Fast resume to the same screen. | Median ≤ 800 ms; same screen | P2-02-warm.txt |
| P2-03 | P1 | Movies grid. `GFX0`; `SWIPEUP` ×15, then 15 reverse swipes; `GFX scroll-movies`. Repeat on My Tvivo shelves (vertical) and on one shelf row horizontally (swipe left/right ×10). | Smooth; no hitch longer than a moment. | Janky ≤ 25%, 90th ≤ 32 ms, 99th ≤ 120 ms. FAIL if janky > 40% | gfx-*.txt |
| P2-04 | P1 | `MEM base` on My Tvivo. Then 3 cycles of: Movies → swipe to the end and back → Series → Live TV → Search `film` → My Tvivo, with `MEM cycle<n>` after each. | Memory settles. | TOTAL PSS cycle3 − cycle1 ≤ 25 MB; no sample > 450 MB | mem-*.txt |
| P2-05 | P1 | Deep scroll: Live TV and Movies grids, fling to the very end with fast swipes (`input swipe ... 60`) ×10, then back to the top. Check the end items `Synthetic Film 24` / `Synthetic Live Channel 16` with `UI has`. | Reaches the last item; no blank tiles after 1 s; no duplicated or missing items. | Last item found; no blanks | P2-05-end-* |
| P2-06 | P0 | Settings → **Refresh library**. Watch the row. | "Refreshing library…", then "Last refreshed <now>". Content still browsable during the refresh. | Completes in ≤ 60 s; timestamp updates | P2-06-refresh |
| P2-07 | P0 | Stale catalog: `CTLSET '{"killSwitch":true}'`. Refresh library (Settings), then the refresh control on the Movies tab. | Cached catalog stays fully visible. A plain non-blocking failure ("<type> library refresh failed" or similar). "Last refreshed" keeps the **old** time. No item disappears. | Item count unchanged; clear failure text; old timestamp | P2-07-stale-* |
| P2-08 | P1 | RESET, then `CTLSET '{"catalog429":3}'`. Refresh library. Read `CTL` afterwards. | The app backs off and retries, or reports failure clearly. It does not hammer: `catalog429Remaining` is 0 and the refresh ends success/fail within 90 s. | No crash; outcome text shown; no endless spinner | P2-08-429 |
| P2-09 | P0 | RESET. Offline launch: airplane on; force-stop; `am start`; wait 10 s. Browse Movies, Series detail, Search `film`. | Opens signed in with the cached catalog; Search works offline; series episodes come from cache or a clear "Episodes are unavailable". No Login, no endless spinner. | All 3 screens usable; no crash | P2-09-offline-* |
| P2-10 | P1 | Still offline: Play Synthetic Film 01. | Clear failure (not a frozen black screen) within 30 s, with Retry/Back. | Message/Retry within 30 s; Back works | P2-10-offline-play |
| P2-11 | P1 | Airplane off. Wait 15 s. Tap Retry (or Play again). | Playback starts within 30 s; catalog refresh does not run in a loop (check that `Last refreshed` changes at most once). | Plays | P2-11-recover |
| P2-12 | P2 | Settings → **Diagnostics** → **Profile catalog**. | "Catalog profile (local reads only)" with timings; values plausible (no negatives). Record them. | Renders | P2-12-profile |
| P2-13 | — | Teardown + logcat gate. | — | PASS | — |

## Run P3: informative playback (mock). RESET before each case.

Each case uses Synthetic Film 01 (VOD) unless stated otherwise. During every case, poll `CTL` every 5 s. `activeConnections` must **never exceed 1**.

| ID | P | Steps | Expected | Pass if | Evidence |
|---|---|---|---|---|---|
| P3-01 | P0 | Play. Within 1 s `SHOT`, then at 3 s, 10 s. Tap to show controls. | Loading indicator is shown until the first frame. Controls show title, `mm:ss / mm:ss` advancing, seek bar with buffered portion, Rewind/Play-Pause/Forward, Audio tracks, Subtitle tracks, Sleep timer. | First frame ≤ 10 s; time advances | P3-01-* |
| P3-02 | P1 | Audio tracks → Arabic, then English. Subtitle tracks → on, then Off. | "Audio: …" / "Subtitles: …" feedback; Arabic track is audibly different (record from MS/logcat if unheard); subtitles appear. | Selections apply | P3-02-* |
| P3-03 | P1 | Seek: tap Forward ×3, Rewind ×1; double-tap right/left thirds; drag the seek bar to 50%. | Feedback such as "+10 s"; position jumps; short buffering indicator; resumes. | Position matches ±3 s | P3-03-seek |
| P3-04 | P0 | Live: Play Generated Live Loop. | "LIVE" badge; no misleading duration; play/pause works. | Plays; LIVE shown | P3-04-live |
| P3-05 | P0 | **Network drop, short:** during VOD at about 0:30, airplane on for 10 s, then off. | "Reconnecting…" appears during the drop; it resumes at the same position ±5 s within 30 s of restore without the user acting. | Auto-recovers | P3-05-* |
| P3-06 | P0 | **Network drop, long:** airplane on for 90 s during VOD. Then off, and tap Retry if shown. | After the retry budget: a Retry state with a plain-language **cause** (network). Retry resumes at the old position ±5 s. | Recovers via Retry; cause text present (else FAIL `GAP-INFO`) | P3-06-* |
| P3-07 | P0 | **Stall:** `CTLSET '{"stallAfterBytes":4194304,"stallSeconds":20}'`, then Play. | Buffering indicator within 2 s of the stall; "Reconnecting…" or buffering; resumes after the stall without leaving the player. | No crash; resumes; ≤ 1 connection | P3-07-stall |
| P3-08 | P1 | **Premature EOF:** `CTLSET '{"eofPercent":30}'`, Play, seek to about 25% and let it run. | At the cut, the app reconnects or shows Retry. It does **not** treat the item as finished: no "Watched", no auto-next, and Continue watching keeps the position. | Not marked watched | P3-08-eof |
| P3-09 | P0 | **Max connections (realistic):** RESET. Start `curl -s -o /dev/null http://127.0.0.1:8089/live/demo/demo/1001.ts` in the background (it holds the only slot; `CTL` shows activeConnections 1). Play Synthetic Film 01. | Clear message that the connection limit was reached / another device may be using the account (ErrorCopy: "Another device may be using this account."), with Retry. Not a silent black screen. | Message within 30 s (bare Retry = FAIL `GAP-INFO`) | P3-09-maxconn |
| P3-10 | P0 | Kill the curl (`taskkill //IM curl.exe //F`), wait 5 s, tap Retry. | Plays within 30 s. | Plays | P3-10-retry-ok |
| P3-11 | P1 | **HTTP 429 on stream:** `CTLSET '{"maxConnections":0,"rejectSecondStatus":429}'`, then Play. | A message that the provider asked the app to slow down / try later. Retry is not hammered: count retries from logcat or the CTL timeline; ≤ 5 attempts in 60 s. | Cause text (else `GAP-INFO`); no rapid loop | P3-11-429 |
| P3-12 | P0 | **Kill switch mid-play:** RESET, Play, at 0:20 `CTLSET '{"killSwitch":true}'`, then Forward ×3 (forces new requests). Wait 60 s. | Clear provider/server error (503) with Retry/Back; no crash. Then RESET and Retry: plays. | Error state + recovery | P3-12-kill |
| P3-13 | P1 | **Kill switch before Play:** killSwitch on, Play Synthetic Film 02. | "This stream isn't available right now." or a provider-down message with Back. | Clear message within 30 s | P3-13-kill-start |
| P3-14 | P1 | **Slow network:** RESET, `emu network speed gsm`, Play. After 60 s set `speed full`. | Long buffering is shown as buffering (not frozen). Starts or retries cleanly; recovers once speed is restored. | No crash; recovers | P3-14-slow |
| P3-15 | P0 | **Fast handoff:** RESET (max 1). Play Film 01, Back, immediately Play Film 02, Back, Play Live Loop. Repeat ×5. | Each starts; there is never a 403 from the old connection lingering. | All 15 start; activeConnections ≤ 1 | P3-15-handoff |
| P3-16 | P1 | Series: Synthetic Series 01 → Episode 1 → Play, with Settings Play next = **Next**. Seek to 1:45. | "Next: Episode 2" prompt with "Cancel next episode"; Episode 2 starts automatically. Then repeat and tap Cancel: it stays at the end with no auto-play. | Both behaviours | P3-16-next |
| P3-17 | — | Teardown + logcat gate. | — | PASS | — |

## Run P4: PiP/mini player, notification, lock screen, sleep timer, resume (mock)

| ID | P | Steps | Expected | Pass if | Evidence |
|---|---|---|---|---|---|
| P4-01 | P0 | Play Synthetic Film 03. At 0:20 press Home (`KEY 3`). `ADB shell dumpsys activity activities \| grep -i pinned`. | Enters PiP (mini player) and keeps playing (MS state=3, position advancing). | PiP present; playing | P4-01-pip |
| P4-02 | P1 | Tap the PiP window, then use its controls: Rewind 10 s, Play/Pause, Forward 10 s (use `UI list` to find them). | Each action works (verify with MS position/state). | 3/3 work | P4-02-pip-ctl |
| P4-03 | P1 | Expand PiP (tap → expand icon). | Returns to the full player at the same position; overlay usable; Back exits normally. | Same position ±3 s | P4-03-expand |
| P4-04 | P0 | Enter PiP again and close it (X). Check `CTL` and MS after 5 s. | Playback stops; activeConnections 0; notification gone (`dumpsys notification \| grep -c com.dev.Tvivo`). | Released within 5 s | P4-04-close |
| P4-05 | P1 | Play Generated Live Loop. Open the shade: `ADB shell cmd statusbar expand-notifications`; `SHOT`; `UI list`. | Media notification with title and actions (Play or pause, Rewind/Forward; Previous/Next only for episodes). Tap pause → MS state 2; tap again → 3. Collapse with `cmd statusbar collapse`. | Controls work | P4-05-notif |
| P4-06 | P0 | While playing, `KEY 26` (screen off). Wait 60 s; check MS and `CTL`. `KEY 26` to wake; `SHOT` the lock screen. | Screen-off playback continues (state 3, 1 connection, position advanced about 60 s). Lock screen shows media controls with the title. Unlock (`KEY 82` or swipe up) returns to the player. | Continues while off; controls on lock screen | P4-06-lock |
| P4-07 | P1 | Sleep timer: in the player tap **Sleep timer**. `UI list`. | Options: 15, 30, 45, 60, 90 minutes, Custom duration, End of current item; "Start timer". | All present | P4-07-menu |
| P4-08 | P0 | Live Loop: Custom duration → set the minimum (5 minutes) → Start timer. Hide controls. Note T0. | Top-end label "Sleep timer: 5 minutes remaining", counting down; notification shows "Sleep timer on" with "Add 15 minutes" / "Turn off timer". | Label + notification | P4-08-timer |
| P4-09 | P0 | At T0+3:00 (±15 s). | Warning dialog "Sleep timer" / "Playback will stop in two minutes." with **Extend 15 minutes** focused and Turn off. Notification "Sleep timer: 2 minutes left". Dismiss (Back) and let it run. | Warning on time | P4-09-warn |
| P4-10 | P0 | At T0+5:00 (±15 s). | Playback stops: ended screen with title and **Watch channel** + Back; activeConnections 0 within 5 s; MS not playing. | Stops on time; released | P4-10-ended |
| P4-11 | P1 | Tap Watch channel. Start a 5-minute timer again; open Sleep timer → Extend 15 minutes; then Turn off. | Label ~20 min after extend; disappears after Turn off; no warning later (watch 30 s). | Both | P4-11-extend-off |
| P4-12 | P1 | Synthetic Series 02 → Episode 1, Play next = Next, timer **End of current item**. | Label "Timer ends with this item". At episode end: stops; Episode 2 does **not** start; ended screen shows **Watch again**. | No auto-next | P4-12-eoi |
| P4-13 | P1 | Timer + PiP: start a 5-minute timer on Live Loop, press Home (PiP), wait until it ends. | Stops in PiP too; PiP closes or shows stopped; connection released. | Released | P4-13-timer-pip |
| P4-14 | P0 | Resume (VOD): Play Synthetic Film 05 to 1:30, Back. My Tvivo. | "Continue watching" shelf, first item Film 05, with a progress indicator. | Present + progress | P4-14-cw |
| P4-15 | P0 | Open Film 05 detail. Choose Continue (or the resume prompt). | Resumes at 1:30 ±10 s. **Start over** from detail starts at 0:00. | Both | P4-15-resume |
| P4-16 | P0 | Kill mid-play: at 2:30 `ADB shell am force-stop PKG`; relaunch; open Film 05. | Resume is about 2:30 (±20 s, saved periodically); never lost back to 0 or 1:30. | ±20 s | P4-16-kill-resume |
| P4-17 | P1 | Series resume: Series 03 Episode 1 to 0:50, Back. Detail page. | Episode shows "In progress"; action "Continue Episode 1". Finish Episode 1 → "Watched", action becomes "Next episode …". | States correct | P4-17-series |
| P4-18 | P2 | Long-press Film 05 in Continue watching → "Remove from continue watching". Episode → "Clear resume". | Removed; detail no longer offers Continue. | Removed | P4-18-remove |
| P4-19 | — | Teardown + logcat gate. | — | PASS | — |

## Run P5: phone heavy usage (mock)

| ID | P | Steps | Expected | Pass if | Evidence |
|---|---|---|---|---|---|
| P5-01 | P0 | `MEM before`. Rapid navigation: tap each of the 7 top destinations in sequence with no wait (use `UI tap`), ×10 loops. | No crash/ANR; the final screen matches the last tap; no stuck loading. | 70 taps OK | P5-01-end |
| P5-02 | P0 | 50+ back/forward: repeat 55× [Movies → Synthetic Film nn detail (nn = loop % 24 + 1) → `KEY 4`]. Then 20× [Series → Series nn → Episode list → `KEY 4` ×2]. | Each Back returns to the previous screen with scroll position kept; never exits the app unexpectedly; no blank screen. After the last Back on My Tvivo: Exit dialog "Exit Tvivo?" (Stay focused by default). | 75 cycles clean | P5-02-* |
| P5-03 | P1 | Category picker open/close ×30 on Movies (tap "Categories", `KEY 4`), then ×10 selecting "Generated Movies", then ×10 on Live TV. | Opens full screen each time; Back closes it without applying; selection applies; no growing lag (time the last 5 vs the first 5). | No crash; last-5 time ≤ 1.5× first-5 | P5-03-picker |
| P5-04 | P1 | Sort/filter control: open/close ×20; cycle every sort option once; type a filter `1` then clear, ×10. | Counts "N of M" correct (filter `1` → 12 of 24 films: 01, 10–19, 21); clear restores 24. | Counts right | P5-04-filter |
| P5-05 | P0 | Rotation storm: alternate user_rotation 0/1 every 1 s ×20 on each of: Movies grid, Series detail, open category picker, Search with query, Settings, item context menu open. | No crash; state kept (open picker stays open or closes cleanly; query kept); no duplicate dialogs. | 120 rotations clean | P5-05-* |
| P5-06 | P1 | Rotation storm during playback: Play Film 01; rotate ×20. | Player stays landscape (fixed); playback uninterrupted (position advances by the elapsed time ±3 s); one connection. | Uninterrupted | P5-06-player |
| P5-07 | P1 | Many favourites: long-press → "Add to favourites" on all 24 films, 16 series, 16 live channels. My Tvivo. | "Favourites" shelf shows them (paged / "See all Favourites"); ordering stable; adding stays responsive. | 56 added; shelf correct | P5-07-favs |
| P5-08 | P1 | Remove 10 favourites (5 films, 5 channels) via "Remove from favourites". Force-stop, relaunch. | Removed items gone; the remaining 46 persist after relaunch. | Counts persist | P5-08-favs-persist |
| P5-09 | P1 | Many history: play 12 different films and 6 channels for 15 s each (Back between). My Tvivo. | "Last watched" / "Continue watching" in most-recent-first order; channels in the live history; no duplicates. | Order correct | P5-09-history |
| P5-10 | P1 | `MEM after`. | TOTAL PSS after − before ≤ 60 MB; ≤ 450 MB. | Within limits | mem-*.txt |
| P5-11 | — | Teardown + logcat gate. | — | PASS | — |

## Run P6: long session soak (mock)

| ID | P | Steps | Expected | Pass if | Evidence |
|---|---|---|---|---|---|
| P6-01 | P0 | RESET. `MEM t0`. Play Generated Live Loop. Every 5 min for 30 min: `MEM t<n>`, MS, `CTL`, `SHOT`. Between samples, toggle the controls twice. | Continuous playback; 1 connection; overlay responsive. | No stop; PSS t30 − t5 ≤ 30 MB (no steady climb) | P6-01-* |
| P6-02 | P1 | At minute 15, PiP for 2 min, then expand. At minute 20, screen off for 2 min, then wake. | Seamless; same stream; no reconnect loop. | Uninterrupted | P6-02-* |
| P6-03 | P1 | After 30 min: Back; browse Movies/Series/Search for 3 min; `GFX0`, 10 swipes, `GFX soak`. | UI as smooth as in P2-03 (janky within +10 points). | Within margin | gfx-soak.txt |
| P6-04 | — | Teardown + logcat gate. | — | PASS | — |

## Run P7: network diagnostics page (mock)

Open Settings → **Network diagnostics**. RESET first. Nothing playing unless stated.

| ID | P | Steps | Expected | Pass if | Evidence |
|---|---|---|---|---|---|
| P7-01 | P0 | Open the page; `CTL` immediately. | Passive page: "Passive details only…" disclaimer, endpoint disclosure, Run check + Deep stream check buttons, connection details (Route, Android validation, captive portal, Wi-Fi signal, band, link, IP assigned yes/no, Router reachability "Not checked"). No SSID, IP, gateway or host anywhere. activeConnections 0 (opening sends nothing). | All true | P7-01-top, -bottom |
| P7-02 | P0 | Timeline: note T0; airplane on 20 s, off; wait 15 s; reopen; scroll to the timeline. | "Network lost" / "Network available" ≈ T0, duration about 20 s (accept 15–40 s); drop count increments; newest first; no SSID/IP/URL. | Correct | P7-02-timeline |
| P7-03 | P0 | Tap **Run check**. | "Cancel check" while running; per-test lines (DNS, INTERNET, GATEWAY, PANEL, CLOCK, IP_FAMILIES, REQUEST_SAMPLE, THROUGHPUT) go running → pass/fail/skipped; done ≤ 40 s; "Strongest finding: …"; PANEL pass; no URL, `10.0.2.2`, `8089` or `demo` shown. | All | P7-03-* |
| P7-04 | P1 | Run check and cancel within 3 s. | Stops ≤ 10 s; remaining tests cancelled; button restored. | — | P7-04 |
| P7-05 | P1 | `killSwitch:true`, Run check, RESET. Then `catalog429:3` + Movies refresh, return to the page, RESET. | Kill: PANEL fails (server error, no URL), a provider finding with steps. 429: "HTTP 429 · N events"; finding "The server asked the app to slow down", with steps like "Retry after a short wait". | Findings match their causes | P7-05-* |
| P7-06 | P0 | Deep stream check → dialog → `KEY 23`. | Dialog "Start deep stream check?"; default focus **Cancel**; it closes; activeConnections 0. | Safe default | P7-06 |
| P7-07 | P0 | Deep check → Start check; poll `CTL` every 3 s. | Phases Stopping → Waiting · N sec → Connecting → Measuring · N bytes → complete; result "HTTP 200 · connect … · first byte …" and bytes/Mbps/stalls; ≤ 105 s; activeConnections ≤ 1, 0 within 5 s after the end. | All | P7-07-* |
| P7-08 | P0 | Within 10 s after P7-07, Play Synthetic Film 01. | Plays ≤ 75 s; no "Network check in progress" toast. | Plays | P7-08 |
| P7-09 | P1 | Deep check, then cancel during Measuring; then a Deep check with Home pressed during Measuring; relaunch and Play. | "cancelled; playback is available"; activeConnections 0; Play works after both. | Both | P7-09-* |
| P7-10 | P1 | Deep check with `stallAfterBytes:262144, stallSeconds:8`; then with `maxConnections:0`. RESET. | Stall: stalls ≥ 1 in the result. 403: HTTP 403 + 403 finding whose limitation says it does **not** prove another device. Page usable. | Both | P7-10-* |
| P7-11 | P0 | Export privacy: tap **Export report**, dismiss the chooser; `ADB exec-out run-as PKG cat cache/tvivo-diagnostics.txt > <RUN>/export.txt`; `grep -inE "10\.0\.2\.2\|8089\|demo\|https?://\|AndroidWifi\|Synthetic\|password\|username\|[0-9]{1,3}(\.[0-9]{1,3}){3}" export.txt`. Then **Copy report**. | Export contains the timeline + check results. grep prints nothing (an IP-like version string must be quoted for review). Toast "Redacted network report copied". | grep empty | P7-11-export.txt |
| P7-12 | P2 | Light theme + Arabic + font 1.3: open the page; open the Deep dialog, Cancel. | Readable, mirrored, not clipped. English test IDs = OBS. | — | P7-12-* |
| P7-13 | — | Teardown + logcat gate. | — | PASS | — |

## Run P8: phone scenario flows (mock)

| ID | P | Steps | Expected | Pass if | Evidence |
|---|---|---|---|---|---|
| P8-01 | P0 | New user path: Settings → **Sign in to a different account**. Then `KEY 4` before typing. | Login opens; Back returns to the still-signed-in account (catalog intact). | No data lost | P8-01-* |
| P8-02 | P0 | Again to Login. Server `http://10.0.2.2`, port `8090`, demo/demo → sign in. | "Can't reach that server." with Retry + Edit server; one line; keyboard does not cover the actions. | Exact copy | P8-02-unreach |
| P8-03 | P0 | Port 8089, password `wrong`. | "That username or password was rejected." + Edit credentials. | Exact copy | P8-03-auth |
| P8-04 | P0 | Password `demo`. | Accepted → sync progress → My Tvivo ("Your library is ready to explore" if first run); catalog 24/16/16; favourites/history of the same account retained or a clear new state (record which). | Signed in; no crash | P8-04-ok |
| P8-05 | P1 | Login with the mock killSwitch on (then RESET). | Clear provider error; the previous account is still active after Back. | Not signed out | P8-05-kill |
| P8-06 | P0 | Returning user: force-stop; `COLD`. | Lands signed in on My Tvivo with Continue watching/Favourites intact in ≤ 3 s; no forced full re-sync blocking the UI. | Content ≤ 3 s | P8-06-return |
| P8-07 | P0 | Flaky Wi-Fi: `delay umts`, `speed edge`. For 5 cycles: browse (Movies → detail → Back) for 20 s, `svc wifi disable` 10 s, `enable`. Then Play Live Loop and repeat 3 cycles. | Browsing works from cache throughout; errors are short and plain; playback shows Reconnecting… and recovers each time (or Retry with cause). No crash, no duplicate streams (≤ 1). | No crash; recovers | P8-07-* |
| P8-08 | P0 | Provider down: `killSwitch:true`; force-stop; relaunch; browse; Refresh library; Play Film 01; open Network diagnostics. | Cached app fully usable; refresh failure non-blocking; Play shows a provider error; netdiag points to a provider/server problem. RESET → Retry plays. | All | P8-08-* |
| P8-09 | P1 | Exit flow: from My Tvivo `KEY 4`. | "Exit Tvivo?" with "Closes the app. Your account and saved titles stay here." "Stay in Tvivo" focused/default; Stay keeps the app; Exit closes it; relaunch works. | Correct | P8-09-exit |
| P8-10 | P2 | Discoverability (phone): without instructions, find favourites (long-press hint?), categories, sort, sleep timer, network diagnostics. Note the taps needed. | Each reachable in ≤ 3 taps from its natural screen; long-press is the only hidden gesture (record whether any visible hint exists). | Record; FAIL only if > 4 taps | P8-10-notes |
| P8-11 | — | Teardown + logcat gate. | — | PASS | — |

---

## Run T1: TV layout, D-pad, appearance (REAL account: D-pad only, NO Play)

Rule check before each `KEY 23`: the focused element must **not** be Play / Continue / Start over / Watch trailer / an episode row / a Live channel tile. If it is, press `KEY 4` instead. On a card, `KEY 23` opens a **detail** page for Movies/Series only. **Never press 23 on Live TV channel tiles** (that would tune).

| ID | P | Steps | Expected | Pass if | Evidence |
|---|---|---|---|---|---|
| T1-01 | P0 | After launch: `SHOT`; `KEY 19/20/21/22` around My Tvivo. | TV layout (distinct from phone): focus frame visible at all times, starts on a sensible element; overscan-safe margins; larger type; shelves scroll to keep focus on screen. | Focus always visible | T1-01-* |
| T1-02 | P0 | Reach every top destination (My Tvivo, For You, Movies, Series, Live TV, Search, Settings) with D-pad only. Record the key path for each. | All reachable; no dead end; Back from each returns toward My Tvivo; from My Tvivo, Back → Exit dialog with **Stay** focused (`KEY 23` on Stay). | 7/7 | T1-02-paths.txt |
| T1-03 | P0 | Movies: open category picker/drawer (`Categories`, 23); Down ×5, Right ×3; `KEY 4`. | Drawer from the start side, about 1/3 width, scrim, content not squeezed; focus trapped inside; Back restores focus on "Categories". | All | T1-03-* |
| T1-04 | P1 | Movies grid: Right to the row end, then one more; Down ×10; Up until the header. | 5-column grid; focus moves cell by cell; row end does not jump randomly; focus stays visible while scrolling. | Predictable | T1-04-* |
| T1-05 | P0 | Focus a movie card → `KEY 82` (menu). | Context menu (Add to favourites / Close). Add to favourites, then Close. Focus returns to the same card. (Favouriting is local; allowed.) | Menu works | T1-05-menu |
| T1-06 | P0 | Focus a movie card → 23 (detail). Move through all detail controls **without pressing 23**; `KEY 4`. | Every control reachable; Back returns to the same card. | Focus restored | T1-06-detail |
| T1-07 | P1 | Series card → 23 (detail) → move into the season/episode list (no 23) → `KEY 4`. | Episode list D-pad navigable; focus restored on Back. | — | T1-07-series |
| T1-08 | P0 | Settings: Down through **every** row to the end and back up. Open + Back each of: Appearance/Theme, Language, Player options, For You Spotlight section, Subscription (Show subscription), Diagnostics, Network diagnostics. **Do not** open Refresh library or Sign out/switch account. | Every row reachable and visible when focused; each sub-page has focus on entry and Back restores focus to its row. Spotlight folder picker Save/Cancel reachable (Cancel only). | All rows | T1-08-* |
| T1-09 | P1 | Search: focus the field, `ADB shell input text the`; Down into results; Right ×4; 23 on a **movie** card → detail → `KEY 4`. | 5-column results, headings in order with "See all"; focus restored to the same card. | — | T1-09-* |
| T1-10 | P1 | Discoverability: from My Tvivo, count presses to reach: favourites shelf, categories, sort, network diagnostics, theme, language. | Each ≤ 8 presses with no hidden gesture except the Menu key (record whether the menu-key affordance is hinted anywhere). | Record; FAIL if any is unreachable | T1-10-notes |
| T1-11 | P1 | Light theme: shots of My Tvivo, Movies grid + drawer, detail, Settings. | Focus frame clearly visible on light backgrounds; text legible. | Legible | T1-11-light-* |
| T1-12 | P1 | Settings → Language → العربية (D-pad). My Tvivo, Movies drawer, Settings, Network diagnostics. | Mirrored: drawer from the **right**; Left/Right movement mirrored sensibly; focus still trapped in the drawer. | Mirrored + usable | T1-12-ar-* |
| T1-13 | P1 | English again. `font_scale 1.3`. My Tvivo, Movies grid, detail, Settings. | No clipped labels; focus frame not cut off. | — | T1-13-f13-* |
| T1-14 | P2 | Small TV: `wm size 1280x720; wm density 213`; relaunch; My Tvivo, Movies grid, Settings. Reset. | Same TV layout scaled; nothing off-screen. | — | T1-14-720p-* |
| T1-15 | — | Teardown + logcat gate. Confirm: no stream started (`dumpsys media_session` never showed state 3 for PKG). | — | PASS | — |

## Run T2: TV performance, deep scroll, caching, heavy D-pad, netdiag (REAL account: NO Play)

| ID | P | Steps | Expected | Pass if | Evidence |
|---|---|---|---|---|---|
| T2-01 | P0 | `COLD` ×5 (10 s apart). | Lands on My Tvivo with content and focus. | Median ≤ 4000 ms; no run > 2× median | T2-01-cold.txt |
| T2-02 | P0 | Deep scroll: Movies → the largest category (or All). `GFX0`; `MEM pre`; `ADB shell 'for i in $(seq 300); do input keyevent 20; done'`; `GFX deep-down`; `MEM deep`; then 300× `KEY 19`. | Focus moves the whole way; artwork loads behind (fallback then poster); no blank rows after 2 s; returns to the top. | Janky ≤ 30%, 99th ≤ 150 ms; PSS deep − pre ≤ 80 MB; ≤ 400 MB | gfx/mem files |
| T2-03 | P1 | Same deep scroll on Live TV (**never 23**) and Series. | As T2-02. | As T2-02 | T2-03-* |
| T2-04 | P1 | Shelf rows on My Tvivo: Right ×40 on one shelf, Down through all shelves; `GFX shelves`. | Smooth; focus never lost. | Janky ≤ 30% | gfx-shelves.txt |
| T2-05 | P0 | Heavy D-pad: 55× [movie card 23 → detail → `KEY 4`] moving one card right each time; 30× [Categories drawer open → `KEY 4`]; 10 loops through all top destinations. `MEM heavy`. | Focus always restored; no crash/ANR; no slowdown (time the last 5 vs the first 5 drawer cycles). | Clean; ≤ 1.5× time; PSS ≤ 400 MB | T2-05-* |
| T2-06 | P0 | Image cache: browse Movies so 2–3 screens of posters load; `SHOT img-online`. Force-stop. Airplane on (if the TV ignores it: `svc wifi disable`; check Route on the netdiag page). Relaunch; same screen; `SHOT img-offline`. | Offline launch signed in; cached catalog browsable; previously seen posters shown from the disk cache (compare); unseen ones show the artwork fallback, not broken icons. | Seen posters ≥ 80% present | T2-06-* |
| T2-07 | P1 | Still offline: Search `the`; open a movie detail (no Play); Settings → Network diagnostics (passive only). | Search works offline; detail from cache or a clear message; netdiag shows the route lost/no internet; no crash. | All | T2-07-* |
| T2-08 | P1 | Airplane off; wait 20 s; netdiag timeline. | Drop event recorded with the right duration; no SSID/IP. | — | T2-08-timeline |
| T2-09 | P1 | Settings → Refresh library (**the only refresh on TV**). | "Refreshing library…" → "Last refreshed <now>", or a clear failure; UI navigable during the refresh. Record the duration. | Completes ≤ 5 min or clear failure | T2-09-refresh |
| T2-10 | P1 | Network diagnostics: Run check (**once**); scroll to the end with D-pad. Deep stream check → dialog → `KEY 23` (Cancel). Export report → `run-as` copy → privacy grep as P7-11 plus the real provider host fragment (orchestrator supplies a non-reported pattern, otherwise grep `https?://` and IP-like only). | Check completes ≤ 40 s; every control reachable; dialog defaults to Cancel and nothing starts; export redacted. | grep empty; no stream | T2-10-* |
| T2-11 | P2 | `MEM end`. | Compare with T2-02 pre. | Growth ≤ 100 MB | mem-end.txt |
| T2-12 | — | Teardown + logcat gate. Confirm no media_session state 3 for PKG during the run. | — | PASS | — |

---

## 4. Report format

`/d/HCode/Tvivo/.qa/full-qa-2026-10-19/report.md`, one section per run, appended as each run ends:

```
## <RUN> — <AVD> — start/end — APK size + mtime
| ID | P | Result | Observed (one line; exact on-screen text where relevant) | Evidence |
Metrics: cold-start TotalTime list + median; gfx janky%/90th/99th per tag; PSS per sample.
Logcat gate: PASS/FAIL (+ StrictMode/skipped-frame counts as OBS)
```

FAIL rows quote what was seen versus Expected. Add tags: `GAP-INFO` (missing cause text in the player), `PERF` (threshold miss), `A11Y` (font/contrast/focus), `PRIV` (privacy leak, always P0).
End the report with: P0 FAIL list, P1 FAIL list, OBS list, "app data cleared: never", "streams started on TV: none".

## Owner-reported regression R1 (2026-10-19) — run first in P4
Play a mock VOD ≥2 min, advance ~60 s, set sleep timer "End of item", system Back gesture to leave player, Settings → theme Dark (activity recreate), return via Continue watching. Expected: resumes at ~60 s (±10 s), playback/timer state sensible. Owner saw restart from 0:00. Also repeat with: timer Off; theme Light; font-scale change; rotation instead of theme; Back→Home→return after 2 min. Capture resume row (adb sqlite or Continue watching card progress), `dumpsys media_session`, logcat of PlaybackService open/save.

## Cross-state interruption matrix (added 2026-10-19 after R1) — run in P4/P5
Every cell: play VOD (and one episode, one live) ~60 s, with each sleep-timer state (Off / custom / End of item / after expiry), then interrupt, then return via (a) Continue watching, (b) detail page Play, (c) notification, (d) PiP tap. Pass: resume within ±10 s, no restart, no duplicate stream, correct timer/state shown.
Interruptions: system Back gesture; Home; recents swipe-away; screen off/on; lock/unlock; rotate; theme change; language change; font scale change; Settings visit and return; refresh library; incoming-call-like audio focus loss (`cmd media_session`/adb); airplane toggle 10 s; process kill (`am kill`) then relaunch; force-stop; app switch for 2 min; low-memory (`am send-trim-memory`); account switch and back; PiP enter/exit; notification Stop; Bluetooth/headset unplug (noisy intent).
Also verify watched/unwatched marks and Continue watching ordering after each.
