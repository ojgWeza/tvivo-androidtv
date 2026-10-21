# Android TV D-pad walkthrough — 2026-10-21

## Setup and scope

- Device: `tvivo_tv34`, booted with `EMU_HEADLESS=1 EMU_MEMORY=3072 bash tools/emulator.sh tvivo_tv34`.
- The device came online after a cold boot. The observed 1-minute loads were 0.64 and 0.25 on successive checks (both below 5).
- Installed the existing `app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`. App data was not cleared.
- Navigation was by `adb shell input keyevent` only. App launch/return-to-task commands were used to establish starting screens; no coordinate taps were sent.
- Screenshots are in `.qa/dpad-walk/`. They are 1920×1080 captures from the TV emulator.
- Counts below are the number of D-pad `keyevent`s actually sent, including ineffective presses. They are observed route counts, not minimum-path estimates. Some later journeys began from the visible For You landing rather than a process restart; those starts are called out.
- No automated tests or builds were run.

## Journey results

| Journey | D-pad count and screens | Result / pain |
|---|---|---|
| **J1 — launch → Live TV → channel** | **13** from the post-restart app screen: `Up`, `Right×3`, `Left×5`, `Right`, `Center`, `Down×2`. Reached Live TV landing (recently added row, ALL row, Categories control), then highlighted the first channel, **سورة الفاتحة**. | Channel was highlighted without activation. No Play was sent during this journey. The route included reversals while focus was being restored; it is not a clean minimum. Capture: `01-live-channel-focused.png`. |
| **J2a — launch → Movies → large category → Back** | **19** to open **BOX OFFICE MOVIES**: 4 keys to Movies, 8 more through opening Categories, then `Down×6`, `Center` to choose the category. Overlay count: **678**. **1 Back** returned to Movies landing. | First page of movie titles was present immediately. `ui.py has Indexing` returned `NOTFOUND`; no empty-state text appeared. `adb logcat -d` had no `CatalogSyncer`/catalog-sync matches. The time spent on subsequent work exceeded 5 minutes; items had already appeared in the first capture. Not reproduced on this cached device state. Captures: `02-category-overlay.png`, `02-box-office.png`. |
| **J2b — Movies → another category → Back** | From the visible For You landing after the diagnostics journey: **16** keys: `Up×2`, `Right`, `Center` (Movies), `Down`, `Left×3`, `Center` (Categories), `Down×5`, `Center` (ENGLISH MOVIES 2026, count **515**), `Back`. | Back again returned to Movies landing. This was a separate continuation after other journeys, not a fresh process launch. The landing showed Continue watching and Recently added shelves plus Categories; it did not display the full category list. |
| **J3 — Series → category → series detail / episodes** | **22** from the visible For You landing: `Up×2`, `Right×2`, `Center` (Series); then `Down`, ineffective `Up×2`, `Down`, `Left`, `Center` (Categories), `Down×4`, `Center` (EGYPT SERIES NEW, count **42**), `Down`, `Left×4`, `Center`. | Opened series **ابن الشركة**. The detail view also showed its episode list (S01E01 onward) and a `Play S1 · E1` action. No episode/play action was selected. Two ineffective Up presses were included in the observed total. |
| **J4 — Search and enter a short query** | **8** to open Search from the visible app landing; **9** including one `Center` on the initially selected keyboard key (`q`). | The screenshot showed an on-screen Gboard keyboard, with `q` selected. The app hierarchy showed the query and matching saved-catalog results; it did not show “use keyboard on your mobile device.” At capture time `dumpsys window` reported `MainActivity` as current focus. The configured IME was `com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`. `SearchScreen.kt` contains a Compose `BasicTextField`; the quoted mobile-device message is absent from app source. Current emulator behavior points to the Android system IME, though a physical TV may provide different IME copy. Capture: `04-search.png`. |
| **J5 — My Tvivo / favourites / continue watching** | **Not completed.** The For You landing visibly contained Continue watching (four cards) and Favourites (one card). The Movies category picker showed Continue Watching **4** and Favourites **1**. An attempted **13-key** route toward the folder instead activated the focused movie **Showdown at the Grand**. | This was an operator navigation error and violated the “NO Play, no stream” constraint. The PlayerActivity opened and the screenshot showed the movie at **0:34 / 1:31:50**, with the sleep-timer menu open. Two Back keyevents closed the timer menu and exited to MainActivity; a subsequent `dumpsys activity services com.dev.Tvivo` returned `(nothing)`. No further playback actions were taken. This journey must be treated as failed and the accidental playback as a material deviation from the brief. Capture: `05-unknown.png`. |
| **J6 — Settings → Network diagnostics** | **32** from the visible For You landing used for J3, including navigation through Series and back: 22 to the series detail, 2 Back to Series landing, 3 to Settings, and 5 to Network diagnostics. | Diagnostics opened. The requested quick **Run check** passed: `INTERNET: passed · HTTP 204`; `PANEL: passed · Panel returned HTTP 200`. The screen described the check’s network contacts. `Deep stream check` was not opened. No stream was opened as part of diagnostics. |
| **J7 — Back / Home** | Observed: 1 Back from Box Office list → Movies landing; 2 Back from series detail → Series landing; 3 Back from Search (hide/clear/leave); 2 Back from diagnostics → For You. `KEYCODE_HOME` returned to the Android TV launcher. | No tested Back path dead-ended or exited the app unexpectedly. Search required multiple Back presses. Home exited to the launcher as expected. The accidental movie player required 2 Back presses because the first dismissed its timer menu. |
| **J8 — category overlay** | Movies landing → Categories followed by `Center` opened the picker. | The left category panel appeared over a dimmed landing screen. The uncovered right side still showed the underlying `Categories` affordance and movie cards through the scrim. The global top navigation row was not visible in the captured overlay. This reproduces underlying-screen bleed in the uncovered area, but does not show the top tab row drawn over the category panel itself. Capture: `02-category-overlay.png`. |

## Ranked problem list and code findings

### P0 — No additional P0 found

The accidental playback is a walkthrough execution deviation, not a code defect classification. It is recorded above because it directly violated the requested constraint.

### P1 — Category access and selection cost too many presses

From a Movies landing after focus had returned to content, the observed route required moving to the first content card, moving Left to Categories, opening the picker, and stepping through the category list. The initial app-to-Box-Office route took 19 keyevents; the series route to a detail with episodes took 22. The current landing shelves show Continue watching / Recently added, not an always-visible category list. The category picker is the only full list.

Root cause is navigation structure and focus placement: `BrowseScreen.kt` provides a separate `Categories` control and focus requesters for the rail/grid. Landing-mode grid Left is explicitly wired to `categoriesFocusRequester` (`BrowseScreen.kt`, around the `ContentGrid` `onLeadingLeft` callback). That makes category access dependent on first reaching a leading grid position or the separate header control, then opening and traversing the rail. The current flow does not meet the requested “any category in ≤3 presses” target.

### P1 — Global catalog indexing can read as a category-specific operation

The runtime “Indexing” banner was not reproduced during this run. Code explains why it can appear while a category is open:

- `BrowseViewModel` starts `syncLive()`, `syncSeries()`, or `syncVod()` during ViewModel initialization (`BrowseViewModel.kt`, around lines 330–337), independently of selected-category paging.
- `CatalogSyncer.syncCatalog()` indexes a content-type catalog when the stored generation is stale, publishes `STATE_INDEXING`, and accumulates parsed rows as `done` while `total` remains zero (`CatalogSyncer.kt`, around lines 120–156).
- `BrowseScreen.kt` renders `Text("Indexing $syncDone…")` (around line 658). The number is progress rows written so far, not a known final total. Since this is a full content-type sync, the progress can approach the whole library size.
- Category listing is a separate paged query. A cold category can have no visible rows until the full-sync generation is committed. `BrowseScreen` shows Loading while Paging reports `LoadState.Loading`, then uses `EmptyState` when refresh is no longer loading and `itemCount == 0` (`BrowseScreen.kt`, around lines 374–390, 854+). Because the banner and category Paging state are independent, an empty result can briefly coexist with global indexing for a cold/stale generation. This is a code-based explanation, not a runtime reproduction here.

For the observed Box Office category, count 678 and titles appeared immediately, with no `Indexing` or empty state. No sync-related log line was found for that open.

### P1 — Refresh affordance differs by TV surface

`BrowseScreen` omits its header Refresh button when `LocalIsTelevision.current` is true (`BrowseScreen.kt`, near the `HeaderIconButton(Icons.Default.Refresh, …)` block around line 650). The empty-state also renders a separate text `Refresh` action (`BrowseScreen.kt`, `EmptyState`, around lines 854–862). This walkthrough did not reach a real empty state, so the D-pad reachability of that text was not verified. The owner’s “Refresh title” report is consistent with the named TV header control being absent, but the runtime reachability of the empty-state action remains unknown.

### P2 — Search keyboard message is not from app code on this build

The app’s Search field is a `BasicTextField` (`SearchScreen.kt`, around line 142), and this project’s `DpadField.kt` covers directional focus behavior. Neither file nor the app source contains “use keyboard on your mobile device.” On this emulator the Android system IME displayed a full Gboard keyboard and accepted `q`; the system IME component was Google LatinIME. The owner’s message may be device/system-IME-specific and was not reproduced here.

### P2 — Category picker reveals the landing behind it

The screenshot confirms that the surrounding landing remains visible under a dim scrim outside the picker panel, including the Categories label. The panel itself remained readable. This is a visual layering/discoverability issue; the capture does not support the narrower claim that the global tab menu is drawn inside the panel.

## What a good TV flow would need

- Put category choice on a stable, directly reachable control from each content landing; any category should take **≤3 D-pad presses from that landing** (open Categories, choose/confirm category). Preserve immediate return to the same category position.
- Make category switching possible without leaving Movies/Series for a landing that only shows a few shelves. Keep the currently selected category and its count visible on the browsing screen.
- Keep global catalog progress distinct from category state: label the content type and rows processed, retain usable cached rows, and avoid showing “Nothing in this category” while a cold category generation is still expected to populate.
- Keep a visible, focusable Refresh action on TV empty/error states, and provide a clear focus return path to it.
- Use an opaque picker surface or move the overlay/scrim so underlying headings and cards do not compete visually with category names.
- Search should focus the TV field, open the system TV keyboard, and make the active IME’s behavior obvious. The app should not imply that a phone is required when the TV IME can accept input.
- Favourites and Continue watching should be reachable as stable top-level destinations; avoid a navigation route where a content card can be activated while the user is trying to open a folder.

## Evidence files

- `.qa/dpad-walk/00-home.png`
- `.qa/dpad-walk/01-live-landing.png`
- `.qa/dpad-walk/01-live-channel-focused.png`
- `.qa/dpad-walk/02-movies-landing.png`
- `.qa/dpad-walk/02-category-overlay.png`
- `.qa/dpad-walk/02-box-office.png`
- `.qa/dpad-walk/04-search.png`
- `.qa/dpad-walk/05-unknown.png` (accidental playback; retained as evidence)

## Session cleanup

The playback screen was exited to MainActivity; `dumpsys window` showed MainActivity focused and `dumpsys activity services com.dev.Tvivo` showed no active app service. The emulator was stopped with `adb emu kill` after saving this report.
