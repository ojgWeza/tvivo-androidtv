# Emulator test plan — Search grid, Categories picker, Network diagnostics (2026-10-17)

**Tester:** Claude Haiku. Follow every step literally, in order. Do not improvise, do not "fix" anything, do not edit code or files other than evidence and the report.
**Sources:** `.codex-android-ux-search-cats-report.md`, `.codex-android-ux-cats-tv-report.md`, `.codex-android-netdiag-s13/s45/s6-report.md`, `docs/android-network-diagnostics-plan-2026-10-17.md` + addendum.
**Precondition:** the orchestrator has recorded owner approval for emulator launch + `adb install`. If you were not told this, STOP before §1.

## 0. Hard rules (any breach = stop the run and report)

1. **Never** run `pm clear`, `adb uninstall`, "Clear data"/"Clear storage", or sign out. The Tink keyset is unrecoverable. On `tvivo_tv34` (a REAL provider account) this is doubly forbidden.
2. **Never build** (no `gradlew assemble/test`). Use the existing APK only.
3. `tvivo_tv34`: **do not press Play on anything**, do not start a Deep stream check, Run check at most **once**. The mock provider is used only on `tvivo_phone34`.
4. One emulator at a time. No Gradle/Kotlin daemon while an emulator runs.
5. Never paste or report credentials, provider host names, or full IPs in your report.

Every `adb` below means `/d/dev/android-sdk/platform-tools/adb.exe` (Git Bash). Repo root = `/d/HCode/Tvivo`.
Screenshot: `adb exec-out screencap -p > /d/HCode/Tvivo/.qa/netdiag-search-cats-2026-10-17/<run>/<name>.png`.
Mock control: `curl -s http://127.0.0.1:8089/__ctl` (GET = state) and
`curl -s -X POST http://127.0.0.1:8089/__ctl -H "content-type: application/json" -d '<json>'`.
**RESET json** = `{"maxConnections":1,"rejectSecondStatus":403,"stallAfterBytes":-1,"stallSeconds":0,"eofPercent":100,"catalog429":0,"killSwitch":false}`.

## 1. Setup (repeat at the start of each run)

1. `mkdir -p /d/HCode/Tvivo/.qa/netdiag-search-cats-2026-10-17/{runA,runB,runC}`
2. Phone runs only: confirm `ls /d/HCode/Tvivo/tools/mock-provider/media` lists `movie.mp4`, `live.ts`, `episode-A1.mp4`. Missing → STOP (BLOCKED). Start mock in background from repo root: `node tools/mock-provider/server.mjs`. Check GET `/__ctl` returns JSON with `"activeConnections":0`. POST RESET.
3. Boot (background task, waits for `==> booted`): `cd /d/HCode/Tvivo && EMU_MEMORY=3072 bash tools/emulator.sh <avd>`.
4. `adb install -r /d/HCode/Tvivo/app/build/outputs/apk/debug/app-debug.apk` → must print `Success`. Anything else → STOP.
5. `adb logcat -c`, then `adb shell am start -n com.dev.Tvivo/.MainActivity`. Wait 10 s. Screenshot `00-launch`.
6. Phone: the app must open signed in (My Tvivo/home), not Login. TV: same. Login screen → STOP (do not sign in, do not clear anything).
7. Restore defaults at the end of each run: `adb shell settings put system font_scale 1.0`, `adb shell cmd locale set-app-locales com.dev.Tvivo --user 0 --locales en-US`, Theme back to Dark, POST RESET, `adb emu network speed full`, `adb emu network delay none`, airplane mode off, Wi-Fi on.

**Emulator "not responding" dialog:** if it names System UI / Launcher / any non-Tvivo process, tap **Wait**, note it as OBS, continue. If it names **Tvivo / com.dev.Tvivo**, screenshot `ANR-<step>`, mark that step FAIL, STOP the run.

## 2. Run A — phone `tvivo_phone34` (mock): Search + Categories + appearance (~25 min)

Mock catalog has exactly: Movies "Synthetic Film" (category "Generated Movies"), Series "Synthetic Series" ("Generated Series"), Live "Generated Live Loop" ("Generated Live").

| ID | Steps | Expected (all must hold) | Shot |
|---|---|---|---|
| A1 | Open Search tab. Type `e`. Wait 2 s. | Headings in this order: **Movies**, **Series**, **Live TV**, each with a "See all" link. One card under each (Synthetic Film / Synthetic Series / Generated Live Loop). Grid is 2 columns wide (card width ≈ half the screen). No horizontal-scroll shelves. | A1-search-all |
| A2 | Tap the "Movies" filter chip, then "Live", then "All". | Movies: only Movies heading. Live: only Live TV heading. All: all three again. | A2-filter-live |
| A3 | Type more so query is `zzzq`. | Text "No saved titles match". No headings. | A3-empty |
| A4 | Clear query (tap Clear ✕), type `synthetic`, tap Synthetic Film card, then press Back (`adb shell input keyevent 4`). | Detail page opens; Back returns to Search with query `synthetic` still present and results visible. Nothing plays. | A4-back |
| A5 | Tap "See all" next to Series. | Opens the Series catalog. Back returns to Search. | — |
| A6 | Open Movies tab. Screenshot. Tap the header button **"Categories"**. | Button is small, in the header row (not a tall side tab). Picker covers the **full screen**; it lists "Generated Movies" and keeps filter/sort controls. | A6-movies-header, A6-picker-full |
| A7 | Press Back. | Picker closes; Movies screen is unchanged; no category was applied. | A7-after-back |
| A8 | Open picker again, tap "Generated Movies". | Picker closes; the opened category shows "Generated Movies" and Synthetic Film. | A8-selected |
| A9 | Repeat A6–A8 on **Series** and **Live TV** tabs (Generated Series / Generated Live). | Same behaviour on each. | A9-series-picker, A9-live-picker |
| A10 | With a category open (A8 state), tap "Categories" again, then Back. | Picker opens full-screen from the opened category too; Back returns to the same opened category. | A10-opened |
| A11 | Phone D-pad: on Movies landing, press `adb shell input keyevent 19/20/21/22` until "Categories" has the focus frame, then `23` (center), then `4` (Back). | After Back, focus frame is back on "Categories". | A11-focus-restore |
| A12 | Settings → Appearance → Theme → **Light**. Repeat A1 and A6 screenshots. | All text readable; no white-on-white or invisible headings; picker uses light colours. | A12-light-search, A12-light-picker |
| A13 | Theme back to Dark. `adb shell cmd locale set-app-locales com.dev.Tvivo --user 0 --locales ar`. `adb shell am force-stop com.dev.Tvivo`, relaunch. Repeat A1, A6. | Layout mirrored (nav/headers start on the right). Picker opens and is usable. If the app is still fully English after relaunch → mark A13 BLOCKED (not FAIL). Hard-coded English on Search/"Categories" is a **known gap**: record OBS, not FAIL. | A13-ar-search, A13-ar-picker |
| A14 | Locale back to en-US, force-stop, relaunch. `adb shell settings put system font_scale 1.3`. Repeat A1, A6. | No text clipped mid-word or overlapping; headings and "See all" fully visible; "Categories" still tappable. | A14-font-search, A14-font-picker |
| A15 | font_scale 1.0. Logcat check (§6). | — | — |

## 3. Run B — phone `tvivo_phone34` (mock): Network diagnostics (~35 min)

Open: Settings tab → row **"Network diagnostics"** (hint "Connection details and recent network problems").

| ID | Steps | Expected | Shot |
|---|---|---|---|
| B1 | Open the page. Before tapping anything: GET `/__ctl`. | Title "Network diagnostics"; text "Passive details only. Opening this page sends no network requests…"; disclosure naming connectivitycheck.gstatic.com and cloudflare-dns.com / speed.cloudflare.com; **Run check** and **Deep stream check** buttons. Connection details list Route (Wi-Fi or Cellular), Android internet validation, Wi-Fi signal (value or "Wi-Fi details unavailable on this device"), Router reachability "Not checked", Static IP "Not available from Android here", Clock difference "Not measured (no active check)". Privacy note present. `activeConnections` 0. No SSID ("AndroidWifi"), no IP, no gateway shown anywhere. | B1-passive-top, B1-passive-bottom (scroll) |
| B2 | Back, then reopen. | Back lands on Settings with "Network diagnostics" row focused/visible. | B2-back |
| B3 | Note clock time T0. `adb shell cmd connectivity airplane-mode enable`; wait **20 s**; `... disable`; wait 15 s. Reopen page, scroll to "Connection timeline". | New lines "Network lost" then "Network available" with today's date/time ≈ T0 (±1 min); the "available" line shows a duration ≈ 20 sec (accept 15–40 sec). Recent problems shows "N drops in the last 24 hours. Longest observed drop: …". Lines contain only route/validated/band/IP-assigned yes/no — **no SSID, IP or URL**. | B3-timeline |
| B4 | `adb shell svc wifi disable`; wait 15 s; `adb shell svc wifi enable`; wait 20 s. Repeat B3 once more with airplane mode for 40 s. | New events appear (Wi-Fi off may show "Network changed" to Cellular instead of "Network lost" — both fine). Drop count increments; longest drop ≈ 40 sec; average gap shown. Newest first. | B4-timeline2 |
| B5 | Tap **Run check**. Watch. | Button text becomes "Cancel check"; lines appear per test: DNS, INTERNET, GATEWAY, PANEL, CLOCK, IP_FAMILIES, REQUEST_SAMPLE, THROUGHPUT with running→pass/fail/skipped. Finishes within **40 s**. "Check results" list shown; "Strongest finding: …" line present. PANEL = pass. No result shows a URL, `10.0.2.2`, `8089`, `demo`. | B5-running, B5-done |
| B6 | Tap Run check, then tap "Cancel check" within 3 s. | Check stops within 10 s; remaining tests read cancelled; button returns to "Run check". | B6-cancel |
| B7 | POST `{"killSwitch":true}`. Run check. Then Movies tab → pull/refresh catalog (refresh icon), return to page. POST RESET. | PANEL fails (detail mentions 503/server error, no URL). Recent problems has "Server error · N events · <time>". A finding about provider/server errors with numbered steps. | B7-killswitch |
| B8 | POST `{"catalog429":3}`. Movies tab → refresh. Return to page. POST RESET. | Recent problems "HTTP 429 · N events". Finding "provider_429"-type card with steps (wait before refreshing…). | B8-429 |
| B9 | POST `{"maxConnections":0}`. Open Synthetic Film → Play; wait 30 s; Back out of player. Return to page. POST RESET. | Player shows an error/retry state (not a crash). Recent problems contains "HTTP 403" (or "HTTP 403 during recent handoff"). A 403 finding whose text does not claim proof of a second connection. | B9-403 |
| B10 | `adb emu network speed edge` and `adb emu network delay gprs`. Run check. Then `speed full`, `delay none`. | Check still ends within 40 s; THROUGHPUT shows a low rate or skipped/fail; a delay/"sample requests" or throughput finding appears. Summary must **not** say the connection looks OK. If `adb emu` errors (auth) → B10 BLOCKED. | B10-slow |
| B11 | For **every** finding card on screen in B5–B10: read its numbered steps. | No step reads "Unknown"; every finding has a title (not a generic placeholder repeated for every card), evidence line, ≥1 step, and a limitation sentence. Steps make sense for the finding (e.g. 429 → wait/avoid refresh, not "move TV closer"). | B11-findings |
| B12 | Tap **Export report**, dismiss the chooser with Back. Run `adb exec-out run-as com.dev.Tvivo cat cache/tvivo-diagnostics.txt > .../runB/B12-export.txt`. Grep it: `grep -inE "10\.0\.2\.2|8089|demo|http|://|AndroidWifi|Synthetic|password|username|[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}" B12-export.txt`. | Toast/chooser appears. File contains "Recent timeline:" and quick-check results. **grep prints nothing** (an IP-like match inside a version string must be quoted in the report for review; any host/credential/title/URL = FAIL). Tap **Copy report** → toast "Redacted network report copied". | B12-export |

### Deep stream check (phone + mock only). POST RESET first. Nothing playing.

| ID | Steps | Expected | Shot |
|---|---|---|---|
| D1 | Tap **Deep stream check**. Screenshot. Immediately `adb shell input keyevent 23`. | Dialog "Start deep stream check?" with "You won't be able to watch anything until this check finishes (about 30 seconds)…" (90 if something is playing; larger if within 60 s of a stop). Buttons "Start check" and "Cancel". Keyevent 23 activates **Cancel** (default focus): dialog closes, no "Stopping playback…" text, GET `/__ctl` activeConnections 0. | D1-dialog, D1-after-cancel |
| D2 | Open dialog, tap "Start check". Every 3 s poll GET `/__ctl` until finished (max 120 s). | Progress text goes through (some of) "Stopping playback…", "Waiting for the previous connection to clear · N sec" (counts down), "Connecting to one catalog item…", "Measuring stream · N bytes received", then "Deep stream check complete". activeConnections is never > 1; is 1 while Measuring; is 0 within 5 s after complete. Result lines: "HTTP 200 · connect N ms · first byte N ms" and "N bytes · X Mbps · … · N stalls · 0 retries". Whole run ≤ 105 s. | D2-measuring, D2-complete |
| D3 | Within 10 s of D2 complete: Movies → Synthetic Film → Play. | Playback starts (picture/time moving) within 75 s. **No** "Network check in progress" toast. Back out (stops). | D3-plays |
| D4 | Start deep check; when progress shows Waiting/Connecting/Measuring, tap the button now labelled "Cancel check". | "Deep stream check cancelled; playback is available" within 10 s; activeConnections 0. Then Play Synthetic Film → plays within 75 s, no toast. | D4-cancelled, D4-plays |
| D5 | Start deep check; during Measuring press Home (`keyevent 3`). Wait 5 s; GET `/__ctl`. Relaunch app (`am start`), Play Synthetic Film. | activeConnections 0 after Home; film plays within 75 s, no "Network check in progress" toast. | D5-plays |
| D6 | POST `{"stallAfterBytes":262144,"stallSeconds":8}`. Run deep check to completion. POST RESET. | Completes (or "could not complete") within 105 s; stalls ≥ 1 in the result line. activeConnections 0 afterwards. | D6-stall |
| D7 | POST `{"maxConnections":0}`. Run deep check. POST RESET. | Result shows HTTP 403 (or "could not complete"); a 403 finding appears; page stays usable; Play afterwards (after RESET) works. | D7-403 |
| D8 | Blocked-during: the page has no play entry and leaving it cancels the check, so it cannot be tried by UI. | PASS if activeConnections never exceeded 1 in D2–D7 and no stream started while a check was running; otherwise FAIL. | — |
| D9 | Light theme, then Arabic (as A13), then font 1.3: open the page each time; open the Deep dialog and Cancel it. Restore defaults. | Readable, mirrored in Arabic (Arabic strings for title/buttons/dialog), nothing clipped at 1.3. Raw test IDs (DNS, PANEL…) staying English = OBS. | D9-light, D9-ar, D9-ar-dialog, D9-font |
| D10 | Logcat check (§6). | — | — |

## 4. Run C — TV `tvivo_tv34` (REAL account, D-pad only, no Play) (~25 min)

Use only `adb shell input keyevent 19 (up) 20 (down) 21 (left) 22 (right) 23 (center) 4 (back)`. Screenshot after each focus move that the table names. Type text with `adb shell input text the`.

| ID | Steps | Expected | Shot |
|---|---|---|---|
| C1 | Go to Search. Type `the` (if Live TV section missing, also try `news`; record both). Move Down into results. | Results grid is **5 columns** wide; headings Movies/Series/Live TV in that order (only those with matches); each heading has "See all". First Down from the field/chips lands on a heading's "See all" or the first card (record which). | C1-search-5col |
| C2 | Press Right 4×, then Right once more; then Down 3×; then Up until focus leaves the grid. | Focus moves card by card along a row with a visible frame; the 6th Right does not jump to a random place; Down moves to the card below (or next section); scrolled focus stays visible; Up eventually returns to the chips/field. | C2-row-end, C2-down |
| C3 | Focus a card, press 23 (opens detail — **do not press Play**), then 4. | Back returns to Search with focus on the **same card**. | C3-focus-restore |
| C4 | Go to Movies. Screenshot. Focus "Categories" in the header, press 23. Screenshot. | Drawer opens from the start side, ~1/3 of the width, scrim over the content. Content behind is **not squeezed**: card positions/sizes identical to the before shot (compare). Focus is inside the drawer. | C4-before, C4-drawer |
| C5 | In the drawer press Down 5× and Right 3×. | Focus stays inside the drawer (never lands on dimmed content). | C5-trapped |
| C6 | Press 4. | Drawer closes; focus frame on "Categories". | C6-restore |
| C7 | Open drawer, move to a category other than the current one, press 23. | Drawer closes; that category is shown in the header/content. | C7-applied |
| C8 | Repeat C4, C6, C7 on Series and Live TV. | Same. | C8-series, C8-live |
| C9 | Settings → move to "Network diagnostics" → 23. | Focus starts on **Run check**. Passive page as B1 (route will be the TV's real route). | C9-page |
| C10 | Press 23 once (Run check, the only run on TV). Wait for completion. Then Down through the page to the end. | As B5 (PANEL may pass or fail; record). Every button reachable by D-pad, focused item always on screen. No host/IP/URL text. | C10-done, C10-bottom |
| C11 | Focus **Deep stream check**, press 23, screenshot, press 23 again. | Dialog as D1 with focus on **Cancel**; second 23 closes it; no "Stopping playback…". **Never** choose Start check on TV. | C11-dialog |
| C12 | Press 4. | Back to Settings with focus on "Network diagnostics". | C12-restore |
| C13 | Light theme: C1, C4 drawer shots. Arabic (as A13): C4 drawer + C9. | Light readable. Arabic: drawer opens from the **right** edge, D-pad Left/Right mirrored sensibly, focus still trapped. | C13-light-search, C13-light-drawer, C13-ar-drawer, C13-ar-netdiag |
| C14 | Restore defaults. Logcat check (§6). | — | — |

## 5. Stop conditions

STOP the current run immediately (screenshot, collect logcat, go to teardown) if: Tvivo crashes or ANRs; the app shows Login; install fails; a stream starts on `tvivo_tv34`; any credential, provider host or full IP is visible on screen or in the export; the emulator freezes for >2 min; the same step fails twice (do not try a third time). Do not continue to the next run after a crash — report first.

## 6. Logcat check (end of each run)

`adb logcat -d > .../<run>/logcat.txt`, then
`grep -nE "FATAL EXCEPTION|ANR in com\.dev\.Tvivo|Process: com\.dev\.Tvivo" .../<run>/logcat.txt` and `adb logcat -d -b crash > .../<run>/crash.txt`.
PASS only if grep prints nothing and `crash.txt` has no `com.dev.Tvivo` line.

## 7. Teardown (after each run)

1. Restore defaults (§1.7). 2. `adb emu kill`; confirm `adb devices` lists no emulator.
3. Phone runs: stop the mock — PowerShell `Get-NetTCPConnection -LocalPort 8089 -State Listen | % { Stop-Process -Id $_.OwningProcess }`; confirm `curl -s http://127.0.0.1:8089/__ctl` fails.
4. `cd /d/HCode/Tvivo && ./gradlew --stop`. Report any `java` process still running (PowerShell `Get-Process java`), do not kill it.

## 8. Report (one file per run, append to the same report)

Write `/d/HCode/Tvivo/.qa/netdiag-search-cats-2026-10-17/report.md`:

```
Run A|B|C — AVD — start/end time — APK size + mtime
| ID | Result (PASS/FAIL/BLOCKED/OBS) | Observed (one line, exact on-screen text where relevant) | Evidence |
```
One row per ID above (A1…A15, B1…B12, D1…D10, C1…C14). FAIL rows must quote what you saw versus the Expected column. List OBS items separately (known English-only text, "not responding" dialogs for other processes). End with: logcat result per run, and "app data cleared: never".
