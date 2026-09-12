# TODOs

Open work, with enough context to pick up cold. Items here were considered and
consciously deferred, or found by testing and not yet fixed — not a backlog of
ideas. The one exception is **Part 2c**, filed 2026-09-10: gaps that were never
work items, ordered by cost against what they unblock.

Everything that was *not* deferred is folded into `docs/ui-scope.md`,
`docs/architecture.md` and `docs/decisions.md`.

**Status as of 2026-09-08:** Phases 0-4 are built and running against the real
panel on the API 34 Android TV emulator, plus an Account screen that was not in
the original plan. **145 unit tests pass.** VOD (48,761 rows), live (6,425
channels) and series (13,264 shows) all sync in full; movie playback works end to
end and the season/episode picker was driven on-emulator.

**Updated 2026-09-10, end of session.** Q-15..Q-27 are all fixed; everything except
Q-24 and Q-27 was driven on the API 34 emulator across Movies, Live and Series.
**149 unit tests pass.**

Landed this session beyond the defects: the three virtual rail folders
(`RECENTLY ADDED` 100, `CONTINUE WATCHING` 50, `FAVOURITES`), the pre-run detail page
that replaced activate-to-play, `get_vod_info` for movie descriptions, and a real
Room migration in place of `fallbackToDestructiveMigration` — which would have dropped
the two tables the new folders are built from.

**Updated 2026-09-10, second session (QA sweep + fixes).** A full report-only sweep
found 14 defects; those plus a 14-item list from the user were fixed in one pass and
re-driven on the emulator. **149 unit tests still pass** and the DB moved to **v5**
(`rating` on `vod_streams` and `series`) over a real migration that preserved the
three resume rows and one favourite on the device.

Closed and verified on-emulator this session: **Q-22** (see its entry — the earlier
"not fixed" call was wrong), **U-1..U-13** below, and **QA-1** (live pre-run page used
the 2:3 poster frame) and **QA-2** (episode-picker `Refresh` was orange text).

Still genuinely open: **Q-4 and Q-5** (both need an open stream, and `max_connections`
is 1 — they land with the physical-TV session), **Q-8** (movie title block eats
vertical space), **Q-14** (sideloaded APK crashes on launch on a phone, still no
logcat), **U-14** (player seek — written but unverifiable without a stream) and
**QA-3..QA-7** (the visual findings below). Q-11's inset bug is fixed and verified.
Phase 5 (hardening) is in progress.

**Credentials were re-entered by hand on 2026-09-08** after the Phase 4 session
cleared app data and destroyed the previous set. Do not clear app data.

**To QA the login screen, use Account → "Sign in to a different account" and
enter a deliberately wrong account.** That path does not wipe anything: it routes
to Login and keeps the current credentials until a new sign-in is *accepted*.
Only the separate `Sign out` button calls `store.wipe()`.

## Suggested order for the next session

1. **T-T2 (instrumented focus tests).** Now the highest-value gap. Five of the
   defects found on this project were focus/layout, all behind green builds, and
   Phase 4 added two more of the same class (initial focus on the detail screen,
   and the season rail's RIGHT target) that only a screenshot caught. Q-9 is the
   sharpest case: the first fix compiled, read correctly, and did nothing.
2. **Get the physical TV in early, ahead of Phase 5.** Live `.ts` playback, the
   `/series/` episode path, `max_connections` behaviour and remote key-repeat
   (T-D2b) are all unverifiable on the emulator, and both live and episode
   playback are shipped-but-never-executed code. This retires more risk than
   further emulator work.
3. **Phase 5 — hardening.** RTL verification, empty/loading/error states,
   `RefreshWorker`, on-device diagnostic log. **Take N-3 and N-4 inside this**,
   not after it — they are error states, and building the state table twice is
   the same mistake D-4/D-5 avoided.
4. **N-1 first among the new items (Part 2c).** It is small, and Q-14 cannot
   move at all until a stack trace exists on a device with no adb attached.
   **N-2 before the physical-TV session**, since that session is the only place
   an exhausted `max_connections` can be provoked on purpose.

**T-T1 is done.** `CatalogDaoTest` (16 tests) covers `replaceCategory`, the
generation flip, account scoping and the per-type table separation;
`CatalogSyncerTest` (11) drives the full-catalog tier over MockWebServer against
in-memory Room, including the zero-row guard for all three content types.

`DESIGN.md` (T-D1) can wait — the shared `tvFocusFrame` and `BrowseItem` /
`CardShape` now enforce most of what it would have said. Multi-account (T-A1) is
speculative until a second panel actually exists.

---

# Part 1 — Open defects

Ten defects have been found on this project. **Every one of them passed a green
build and a green unit suite**, and every one was found by driving the emulator
over `adb` and looking at a screenshot. Budget for that on every UI change.

Seven are fixed and verified (Q-1, Q-2, Q-3, Q-6, Q-7, Q-9, and the two Home
nits found while verifying them). Phase 4 found three more the same way and all
three are fixed: the detail screen opened with focus on nothing, the picker
showed "1 min" for 40-minute episodes because this panel's `duration_secs` is
wrong, and episode titles carried the `HD` token the grid strips. What is left
is below. The durable lessons
from the fixed ones live in `docs/ui-scope.md` and `docs/decisions.md`, not
here — this file is for what is still open.

## Q-4 — Player has no visible way back
**Severity: Medium. Fixed in code (`a407bad`); still unverified on-emulator.**
`ui/player/PlayerActivity.kt`

Verifying the hint means opening a stream, and `max_connections` is 1, so this
stays code-reviewed only until the Phase 5 physical-TV session.

Back *works* (verified: `KEYCODE_BACK` moves `PlayerActivity` → `MainActivity`).
The defect is discoverability — nothing on screen communicated how to leave, so
the exit depended on the user already knowing about the remote's Back button.
A corner hint now rides the controller's visibility.

Distinct from Q-2: the mechanism is fine here, the affordance is missing.

## Q-5 — Player reports English audio on an Arabic film
**Severity: Medium. NOT REPRODUCED.**

Verifying requires an open stream and `max_connections` is 1, so this was not
chased. Two candidates to distinguish first:
- The file's single AAC track carries an `eng` language tag from the encoder,
  which is common on scene releases regardless of the actual audio. Media3 would
  then be reporting honest metadata and the tag itself is wrong — the fix is to
  show "Unknown" rather than trust a lone tag.
- Media3 is falling back to a default label.

Check `Player.getCurrentTracks()` language tags against the file before touching
any code.

## Q-8 — Movie title block consumes excessive vertical space
**Severity: Low. User asked to defer.**

Titles render below the poster on up to two lines, so long Arabic titles push
the grid rhythm around.

---

## Q-14 — Sideloaded APK crashes on launch on a phone
**Severity: Unknown until triaged. Reported 2026-09-08. Reproducible — the user has
tried this several times and it crashes on every launch. Still not investigated, because
no logcat has been captured from the phone.**

The debug APK installs on an Android **phone**, and crashes immediately on
"Open" — every attempt, not intermittently. No logcat captured yet, so there is no root
cause here, only the report. **Getting the trace is the blocker; nothing else about this
is worth guessing at until it exists.**

**First step is a stack trace, not a fix:** `adb logcat -c` before launching,
then `adb logcat -d AndroidRuntime:E *:S` right after the crash. Everything below
is a candidate list to check the trace against, not a diagnosis.

- **Updated 2026-09-12:** `MainActivity` now has both `LEANBACK_LAUNCHER` and
  `LAUNCHER` intent filters, and `android.software.leanback` is optional. A
  sideloaded build therefore has a normal phone-launcher entry; this removes one
  unsupported-entry-path variable but does not explain a crash without its trace.
- `androidx.tv.material3` is a TV surface library; nothing guarantees it behaves
  on a handset form factor.
- Layouts are built to a fixed 1920x1080 10-foot geometry (`docs/ui-scope.md`),
  so a phone is outside every measurement in the design.

**Cross-project lesson (PrayerQiblaApp, confirmed on a Xiaomi Mi 10):** its
apparently similar post-install crash was `Failed to create an instance of
androidx.work.impl.WorkDatabase` inside
`androidx.startup.InitializationProvider`; release R8 shrinking had broken a
reflection-dependent WorkManager/Room path before Flutter started. Tvivo already
sets `isMinifyEnabled = false`, so this is evidence for what to inspect in the
trace, not evidence that Q-14 has the same cause. Tvivo now also sets
`isShrinkResources = false` explicitly and initializes WorkManager only after
the sanitized crash handler is installed, so a comparable initialization
failure can survive into on-device Diagnostics on the next launch.

**Phone is not a target.** `PRODUCT.md` scopes this to Android TV, so the
outcome may legitimately be "the phone is unsupported, and the failure should be
a clear message rather than a crash" — but that is a decision to make *after*
reading the trace, not instead of reading it.

---

## Q-28 — Login screen must support phone layout, touch, and keyboard insets
**Severity: High for handset use. Implemented 2026-09-12; handset verification pending.**
`auth/LoginScreen.kt`

The login screen assumes the large TV/D-pad form factor. On a normal Android
phone, the software keyboard covers the action controls, and dismissing it leaves
focus stuck on the password field. Controls are enabled and clickable, but are
not reachable by expected touch/D-pad navigation.

Implement an adaptive login presentation:

- Use available width rather than a fixed TV form width.
- Use a compact, vertical, scrollable mobile layout while the IME is visible;
  keep all fields and actions visible above the keyboard.
- Keep TV layout and D-pad traversal working on Android TV.
- Preserve ordinary touch interaction for handset users.
- Clear or redirect text-field focus when the keyboard is dismissed; do not rely
  on `focusRestorer()`.
- Add a standard `android.intent.category.LAUNCHER` activity entry and make the
  Android TV feature declaration optional, so the installed app appears in a
  phone launcher/application list as well as on Android TV.
- Keep the full "Tvivo" label in handset launcher/application lists. The resource
  label is already `Tvivo`; `tvivo-name-truncation.png` instead exposed the login
  wordmark being drawn beneath the status bar. Handset login now applies safe-drawing
  padding before its own content padding.
- Capture from 2026-09-12 (`tvivo-login-current.png`) shows the handset in
  portrait, but the rendered Tvivo content still uses the wide TV rail/card
  presentation. Ensure the mobile login route is explicitly vertical and
  handset-sized rather than inheriting TV geometry.
- After successful login on a handset, restore landscape orientation for the
  browse/home experience so rail icons and wide cards render correctly; return
  to portrait for the login route.
- Mi 10 touch QA (2026-09-12): Live TV, Series, and Movies nodes report
  `clickable=true`/`enabled=true`, but taps did not visibly navigate. The
  activity still requests `SCREEN_ORIENTATION_LANDSCAPE` while the handset UI
  reports a portrait 1080x2120 surface, so coordinate/orientation mapping or
  the touch targets must be fixed and verified on-device.
- Follow-up QA: do not treat orientation as the root-cause explanation without
  proving it. In the landscape UI dump, each tile has a concrete clickable bounds
  (`Live TV` [354,462]-[870,904], `Movies` [958,462]-[1473,904], `Series`
  [1561,462]-[2076,904]); an injected tap at the apparent Live TV centre did
  not change route. Instrument the tile callbacks/route state and reproduce
  with Compose UI touch tests plus on-device input to determine whether the
  failure is coordinate translation, `androidx.tv.material3.Card` touch handling
  on phones, or a callback/state bug.
- Add an in-app diagnostic log facility that records navigation/touch events,
  focus transitions, orientation and window metrics, route changes, and caught
  exceptions. Persist a bounded, user-viewable/exportable log so handset issues
  can be inspected after they occur without an attached ADB session.
- Mi 10 screenshots (`tvivo-movies.png`, `tvivo-series.png`) confirm the
  rightmost browse column is clipped at the screen edge: poster artwork and
  titles are truncated. Make handset browse content width/insets responsive so
  the final column remains fully visible (or horizontally scrollable).
- Mi 10 playback QA: the phone can enter screen sleep while the player is
  active. Keep the display awake for the lifetime of `PlayerActivity`/active
  playback (with normal release on pause/stop/exit), and verify this on a
  handset as well as Android TV.
- Refresh behavior: after a catalog refresh, reset any selected left-rail
  category and right/content-panel item to the default safe selection, rather
  than retaining stale focus/selection against replaced data. Verify for Live,
  Movies, and Series, including an in-progress refresh.
- Mi 10 browse layout: the rightmost content column is clipped. Measure the
  usable window excluding system bars, apply insets, and reclaim the gap between
  rail and grid before reducing card sizes; keep horizontal scrolling as fallback.
- Mi 10 player layout: playback is not full-screen within the usable window.
  Detect window bounds minus system bars, support deliberate immersive fullscreen,
  and verify portrait/landscape handset layouts separately from Android TV.
- Series detail UX: replace the ambiguous episode-list play affordance with a
  clearly labelled `Episodes`/`View episodes` action and obvious focus state.
- Player controls: replace "Back to exit" text with a compact upper-left Back
  button; place Next/Previous episode controls in the upper-right. Keep
  play/pause, seek, progress, audio, and subtitles discoverable with touch/D-pad.
- Player resilience: after device sleep/wake, recreate the player surface without
  crashing, preserve position, and pause playback rather than forcing an exit;
  log lifecycle/decoder failures with redacted stack traces.
- In-player navigation: add an Episodes button that opens a dismissible overlay
  episode list, allowing episode selection without leaving the player.
- Consider a Home Settings button for configurable layout/orientation, card
  density, visible rails, and playback preferences, with safe defaults.

**Implemented 2026-09-12; device verification pending:** Browse now measures its
content column from the width remaining after the fixed rail, rather than taking
the whole `Row` width and clipping the final handset column. `PlayerActivity`
sets `FLAG_KEEP_SCREEN_ON` when playback is created and clears it in `release()`.
Manual category refresh resets the rail/category, filter, pending grid focus, and
content panel to the same safe default used on entry before the replacement data
arrives. `./gradlew test assembleDebug` passed; validate the rightmost column,
sleep prevention, and refresh behavior on Live, Movies, and Series before closing.
- Extend click diagnostics through the complete post-click path: log touch
  receipt and tile name, `onSelect` invocation, requested content type/route,
  `MainActivity` route-state mutation, destination composition/loading or error,
  and any rejected transition or exception.
- Complete the diagnostic implementation and validation: define a stable
  `Tvivo` log schema (timestamp, screen, event, payload, severity), add a
  bounded persistent logger and a user-visible/exportable diagnostics view,
  include app/build/device/orientation/window metadata, and capture uncaught
  exceptions with stack traces. Instrument every interactive surface and
  navigation boundary (login fields/actions, home tiles, rail/category items,
  detail/play actions, back/account/refresh/exit), plus ViewModel/API/Room and
  image-loading failures. Log event ordering and elapsed time, redact
  credentials/tokens/provider secrets, verify logs survive process restart, and
  add unit/instrumented tests proving events are emitted and routes complete.
  Rebuild and exercise each control on both Android TV and the Mi 10, export the
  log after a failed interaction, and document the exact reproduction steps.

**Implemented:** compact non-TV devices use an available-width vertical form with
IME-aware scrolling; credentials stack, action labels compact, and closing the
handset keyboard clears field focus. Android TV retains its fixed, D-pad-first
layout and focus behaviour. On handsets `Login` is portrait and authenticated
catalog routes are landscape; `MainActivity` handles the configuration change so
the in-progress login/account-switch state is retained. The manifest exposes a
standard phone launcher entry and makes Leanback optional. Needs real-handset
validation for launch, touch, IME, and rotation; no emulator/deployment was run.

**Follow-up implementation, 2026-09-12:** `DiagnosticLog` now persists its
bounded 200-entry, credential-safe log across restarts and exposes `Export log`
and `Clear log` from Diagnostics. It records route changes, requested orientation,
configuration/window metrics, Home-tile pointer presses, Card click callbacks,
and Home focus transitions. This creates the evidence chain needed to distinguish
a handset coordinate/input problem from a Card callback or route-state failure.
`HomeScreenTouchTest` now exercises a real Compose touch click through the Live
tile callback; it and the real-handset reproduction remain unrun/unverified until
an approved device/emulator session.

**Click-path follow-up, 2026-09-12:** Home diagnostics now also record pointer
release versus gesture cancellation, `onSelect` invocation, the requested Browse
content type, and the route-state assignment. Together with the existing route
composition entry, one exported log establishes whether a no-navigation report
stopped before pointer completion, Card activation, callback dispatch, state
mutation, or Browse composition. This remains diagnostic instrumentation, not a
claim that orientation or TV Material Card touch handling is the root cause;
handset validation is still required.

**Mi 10 validation, 2026-09-12:** The fresh debug APK was installed without
clearing app data and the original Home-tile defect is fixed. Live TV and Movies
were re-driven end to end; Series had already passed in the preceding run. Each
records touch press → release → handset activation → `onSelect` → requested
`Browse` route → route-state mutation → destination composition for its matching
`ContentType`. The handset is a Xiaomi Mi 10 (`umi_eea`, 1080×2340 at 440 dpi).
Diagnostics persisted and emitted the same chain to Logcat. Repeated image-load
`IllegalStateException` warnings were observed without an app crash; investigate
them separately if they affect visible artwork. Login/IME remains unverified on
this device because the retained authenticated account must not be destroyed.

**Durable validation rules:** pointer press/release proves only that input arrived;
it does not prove a `Card` callback or navigation occurred. Diagnose and export the
complete chain — touch press → release → activation → `onSelect` → requested route
→ state mutation → destination composition — and identify the first missing event
before blaming orientation or coordinates. `androidx.tv.material3.Card` remains
the Android TV D-pad activation path, while the handset uses its explicit
`detectTapGestures` path. Keep diagnostics bounded, persistent, exportable, and
centrally redacted (credentials, tokens, URLs, server hosts, and provider data).
Green unit/build checks are necessary but do not validate handset touch, IME,
focus, rotation, or visible navigation; validate those on an approved device
without opening a stream or clearing app data. Use Account → Sign in to a
different account for the remaining Login/IME check.

## Q-15 — The icon pill draws a second, rectangular focus indicator — **FIXED**
**Severity: Medium. Found 2026-09-08 on-emulator. Regression of Q-3.**
**Fixed and verified on-emulator 2026-09-08.** Lifted to `tvClickable` in `ui/common/TvFocusFrame.kt` and applied to every `clickable` call site, since all of them were latent instances of the same thing.
`ui/common/IconPill.kt`

The focused pill shows the accent stadium fill *and* a black rectangle behind it,
squared off at the pill's layout bounds. Two competing focus indicators on one
control is exactly what Q-3 fixed, and `HomeScreen` even carries a comment about
suppressing `tv-material3`'s own outline for the same reason.

**Cause:** `Modifier.clickable` supplies a default indication, which paints to the
node's rectangular bounds and does not follow the `RoundedCornerShape(50%)` the
pill draws its background with. It went unnoticed on every other control in the
app because they are all rectangular, so the indication coincides with their
edges.

**Fix:** `clickable(interactionSource = …, indication = null)`, leaving
`tvFocusFrame` as the only indicator. Worth auditing the other `clickable` call
sites at the same time — they are all latent instances of this, and any of them
gaining a rounded shape reintroduces it.

## Q-16 — The Exit pill's glyph renders as tofu — **FIXED**
**Severity: Medium. Found 2026-09-08 on-emulator.**
**Fixed and verified on-emulator 2026-09-08.** `IconPill` takes an `ImageVector` from `material-icons-core` rather than a character, so font coverage stops being a variable. Still placeholders pending T-D1.
`ui/common/IconPill.kt`

`⏻` (U+23FB POWER SYMBOL) has no glyph in the emulator's font stack and draws as
a missing-character box. `◔` (Account) does render but is small and reads as
nothing in particular at ten feet; `↻` (Refresh) is fine.

These are documented placeholders pending the real icon set (T-D1), but a
placeholder that renders as a box is worse than one that renders as a wrong
picture — the pill's whole premise is that a glyph is legible at rest.

**Interim fix:** restrict placeholders to glyphs that actually exist in Roboto /
Noto on Android TV, and verify each one on-device rather than assuming.

## Q-17 — RIGHT from a pill sometimes lands on a tile instead of the next pill — **FIXED**
**Severity: Medium. Intermittent — reproduced once, not on cold start.**
**Fixed and verified on-emulator 2026-09-08.** The pill row's traversal is declared — `focusGroup()` plus explicit `focusProperties` — rather than left to the 2D focus search.
`ui/home/HomeScreen.kt`

With focus on `Refresh everything`, RIGHT moved focus **down to the Series tile**
rather than across to `Account`, leaving Account and Exit reachable only by
accident. From a cold start the same press moves correctly to `Account`.

**Suspected cause:** the pill row is a plain `Row` with no `focusGroup()` and no
explicit direction overrides, and `animateContentSize` changes each pill's bounds
while the expand animation runs. Compose's 2D focus search scores candidates on
current bounds, so a press landing mid-animation can find a tile a better match
than the neighbouring pill. This is the same failure the rail already needed
`focusProperties { right = … }` to fix, and the same lesson: on this project the
2D search must be overridden, not trusted.

**Fix:** explicit `focusProperties` wiring across the row, and re-test with a
press sent during the animation window rather than after it.

## Q-18 — Overlaid card titles are hard to read over bright artwork — **FIXED**
**Severity: Low. Found 2026-09-08 on-emulator.** `ui/browse/ContentGrid.kt`
**Fixed and verified on-emulator 2026-09-08.** The scrim owns its own height (68 dp on a 165 dp poster) instead of being sized to the text.

D-6 works structurally — titles are on the poster, two lines, and the grid gained
back its row — but the scrim is too weak. Over bright posters the title competes
with the artwork rather than sitting on top of it.

**Cause:** the gradient starts at 45% of the overlay box and tops out at 94%
alpha over a box only as tall as the text plus 6 dp. Both the start point and the
box are too small.

**Fix:** start the gradient higher and give the scrim its own height independent
of the text.

---

## Q-19 — The category and item filter fields trap focus — **FIXED, verified**
**Severity: High. Found 2026-09-08 on-emulator, in the QA pass for D-13/D-14.**
`ui/browse/CategoryRail.kt`, `ui/browse/BrowseScreen.kt`

Once focus entered the rail's `Filter categories` field, **no D-pad press could leave
it.** Verified on-emulator: with the IME dismissed, two DOWN presses and a RIGHT and an
UP all left focus in the field, moving only the caret. The filtered categories the user
had just produced were unreachable, and Back was the only way out — which also discards
the filter. D-13 was therefore unusable as shipped, and D-14 could not even be reached to
test.

**Cause: the fix for this already existed and was in the wrong place.** `LoginScreen` has
carried a private `dpadFieldNavigation` since the very first defect on this project — a
login screen that could be typed into but never left. Being private to one screen, it was
a detail of that screen rather than a rule, so two new fields reproduced the identical
trap.

**Fix:** lifted to `ui/common/DpadField.kt` with the reasoning attached, and applied to
both filter fields plus an `ImeAction.Next` that moves focus into the list. **Any
focusable text field on a TV must carry it.** Left/right are deliberately still not
intercepted — inside a field they move the caret, which is wanted when fixing a typo.

**Verified on-emulator 2026-09-08:** with `horror` typed and the IME dismissed, DOWN moves focus out of the field and onto the first filtered category. D-14 became reachable and was then verified for the first time.

## Q-20 — The splash mark shows its launcher background as a box — **FIXED, verified**
**Severity: Low. Found 2026-09-08 on-emulator.** `ui/common/SplashScreen.kt`

`ic_launcher_mark` carries a gradient background rect, because a launcher icon has to
supply its own surface. Drawn on the splash, which already has one, that rect reads as a
lighter square floating behind the mark.

**Fix:** `ic_mark.xml`, same geometry without the background, for in-app use. The launcher
icon keeps its tile. **Verified on-emulator 2026-09-08.**

---

## Q-21 — The expanded item filter overlaps the category title — **FIXED, unverified on-emulator**
**Severity: Low. Found 2026-09-08 on-emulator, verifying D-14.**
`ui/browse/BrowseScreen.kt`

Opening the grid filter expands a 320 dp field in place, and it is drawn over the category
name: with `ARABIC MOVIES 2026` in the header, the field covers everything from `2026`
rightwards.

**Cause:** the header is a `SpaceBetween` row of `title` and `actions`. Collapsed, the two
pills leave room; expanded, the field takes 320 dp more than the row has to give, and the
title does not shrink because nothing tells it to.

**Fixed 2026-09-10, both halves.** The title `Column` takes `Modifier.weight(1f)`, so
the actions measure first and the title can never be drawn over; and the title itself is
hidden while the filter is open, leaving the count line, which is the part actually
changing under the user. It wraps to two lines rather than truncating — `RAMADAN EGYPT
2026 SD` and `... HD` truncate to the same string.

**Not yet driven on the emulator.** The layout is the kind that has passed a green build
and still been wrong here; it needs a screenshot with a long category name and the filter
open before it is called done.

**Not a functional defect** — the filter and the `N of M` count both work, and the title
returns when the filter closes.

## Q-22 — The diagnostic log has almost no call sites — **FIXED, verified 2026-09-10**
**Severity: Medium. Found 2026-09-08 on-emulator.** `diagnostics/DiagnosticLog.kt`

The Diagnostics screen renders correctly and, after a full session of cold start, catalog
browsing, category filtering and item filtering, reported **"Nothing recorded yet."** That
is honest — but it means the screen would be empty at exactly the moment someone opened it
to find out why something broke.

**Cause:** the log was wired only into `RefreshWorker` and `AccountViewModel` (manual
refresh, sign-out). None of those ran. The events that actually matter — app start, catalog
sync start/finish/zero-row guard, category refresh failure, auth failure, playback error —
are not instrumented.

**Fixed 2026-09-10.** Call sites added at every point named above plus app start:

- `MainActivity` — start marker with version and API level. Without it an empty log is
  ambiguous between "nothing went wrong" and "the process restarted under the problem".
- `CatalogSyncer` — start, TTL skip, complete-with-row-count, HTTP failure, empty body,
  and the `partial`/zero-row guard as a **warning**. That last one is the most valuable
  line on the screen: it is the exact state where `ALL`, `RECENTLY ADDED` and search are
  incomplete while every per-category listing looks perfectly healthy. Its own `Log.i`
  calls were replaced rather than duplicated. Cancellation is logged as `info`, not as a
  failure — leaving a browse screen cancels its sync, and an error per screen exit would
  bury the real ones.
- `BrowseViewModel` — the single `toAppError()` funnel every browse failure passes
  through, plus the missing-credentials path.
- `AuthRepository` — HTTP rejection, panel refusal (`auth: 0`, expired), and transport
  failure.
- `PlayerActivity.fail()` — the one exit both the error listener and the connection-limit
  path already go through.

**Nothing logged carries a URL, a credential or a title.** Exceptions are recorded by
type (`t.javaClass.simpleName`), never by message: a network exception's message echoes
the request URL, and that URL is the whole account.

**Verified on the emulator 2026-09-10**, and the verification is worth recording because it
was first read as a *failure*.

After a ~45 minute session across Movies, Live and Series the screen showed **four lines**:
app start, and `within TTL, keeping cached generation` for vod, live and series. That was
briefly written up as "Q-22 is not fixed". It is not — it is the correct and complete output.
Every one of the 20 call sites is app start, a sync lifecycle event, or a **failure**, and in
that session no sync ran (all three content types were inside their TTL) and nothing failed.
There was nothing else to record.

**The trap to avoid next time: a healthy session produces a nearly empty Diagnostics screen,
and an empty screen is not evidence of missing instrumentation.** Read the call-site list
before concluding otherwise. Whether routine non-failure events should also be recorded is a
separate design question — **T-D4**.

---

# Part 1c — 2026-09-10 QA sweep: fixed and verified

Fourteen items raised by the user plus two from the report-only sweep, all landed in one
pass and re-driven on the API 34 emulator. Kept as one block because they were one change
set; the reasoning for each lives in the code comment at the site.

| ID | Item | Fix | Verified |
|---|---|---|---|
| U-1 | Entering Series focused the header search and opened the IME | **Not reproduced** — see QA-8 | — |
| U-2 | Player has no fast-forward; the time-bar knob is not focusable | `SEEK_INCREMENT_MS` + `onKeyDown` LEFT/RIGHT in `PlayerActivity` | **No** — see U-14 |
| U-3 | No rating anywhere | `rating` parsed → DB v5 → grid badge + detail line | Yes, real values |
| U-4 | Series plot truncated with no way to read the rest | `ui/common/ScrollableText.kt` | Yes |
| U-5 | Rail → grid → rail → grid landed on the first visible item | `focusProperties { enter }` in `ContentGrid` | Yes |
| U-6 | `Refresh this category` — "this" is inferred; pill too big | Label → `Refresh`; `IconPill` 88 → 56 dp | Yes |
| U-7 | Search pill exaggerated; Clear shown with an empty box | Clear only when non-blank, flush; field 320 → 260 dp | Yes |
| U-8 | Count on the right panel duplicates the rail | `N of M` only while filtering | Yes |
| U-9 | Home icon circles far too big for their glyphs | Same `IconPill` change as U-6 | Yes |
| U-10 | Movies → Home → Movies showed a stale list | Same root as U-12 | Yes |
| U-11 | `Refresh everything` → `Refresh all` | Label | Yes |
| U-12 | Back from a channel/movie/show lost the left-panel focus | Rail no longer competes when `pendingFocusItemId` is set; restore now *requests focus*, not just scrolls | Yes |
| U-13 | Continue watching dropped a film played for 1 s | See below — this was **data loss** | Partly |
| QA-1 | Live pre-run page used the 2:3 poster frame for a 16:9 logo | `CHANNEL_WIDTH/HEIGHT` in `ItemDetailScreen` | Yes |
| QA-2 | Episode-picker `Refresh` was orange text (= the focus colour) | `IconPill` | Yes |

## U-13 — the Continue watching condition was hiding a data-loss bug

`PlaybackStateRepository.savePosition` collapsed two unrelated cases into one `remove`:

```kotlin
if (positionMs < MIN_TRACKED_MS || finished) { remove(...) }
```

The 60 s floor is right — it stops accidental opens filling the folder. But crossing it
downward says only that *this visit* was short; it says nothing about the forty minutes
already watched. So opening a part-watched film and backing out within a minute **deleted
the resume point**, which is the most common way to touch something you are part-way
through. Too-short now declines to *write*; only `finished` removes.

**Not fully verified**: proving it needs an open stream. The three resume rows on the device
(3–6 % watched) are exactly the rows the old code would have destroyed on the next short
visit, and they survived the v5 migration intact.

## U-14 — player seek is written but unverified

`max_connections` is 1, so no automated test may open a stream and the QA pass could not
either. The change is in (`SEEK_INCREMENT_MS` = 10 s, symmetric, intercepted in
`onKeyDown` so seeking does not depend on `DefaultTimeBar` taking focus) but **someone has
to play a film and press LEFT/RIGHT**. Lands with Q-4/Q-5 and the physical-TV session.

---

# Part 1d — 2026-09-10 QA sweep: found and still open

Visual/content findings from the report-only pass, none of which were in the fix batch.
Full report with screenshots: `.gstack/qa-reports/` (gitignored — it contains account
details visible in the UI).

## QA-3 — Arabic description paragraphs resolve LTR, so the last line hangs on the wrong edge — **IMPLEMENTED, emulator verification pending**
**Severity: Medium.** Glyph order and bidi-isolation of embedded Latin runs are both correct;
the *paragraph direction* is not, so a short final line aligns left instead of flush right.
Reproduces on the movie pre-run page and the episode-picker header. Same class as
`rtl-title-truncation-needs-display-column`, but in the body text rather than the title.

## QA-4 — The focused grid card scrolls flush against the bottom edge and is clipped — **IMPLEMENTED, emulator verification pending**
**Severity: Medium.** Scrolling down keeps the focused card as the last visible row with its
lower portion cut off, and the row above the viewport renders as a bare strip of titles with
no poster. Worse on an overscanning TV.

## QA-5 — Live channel cards with no logo render as bare empty rectangles — **IMPLEMENTED, emulator verification pending**
**Severity: Low.** 13 of 15 cards on the Live TV landing had no artwork and no fallback — no
channel initial, no generic glyph. May be upstream absence; the empty state is unhandled
either way. The same gap shows on the live pre-run page, now that its frame is the right shape.

## QA-6 — Card titles: contrast over bright artwork, and mid-word breaks — **IMPLEMENTED, emulator verification pending**
**Severity: Low.** Two-line titles grow upward into the poster while one-line titles sit below
it, so the overlay is inconsistent card to card, and the scrim is not strong enough over
saturated art. Titles also break mid-word (`BTS.The.Retur` / `n.2026`).

## QA-7 — The rail's "last-active" tint is very close to invisible — **IMPLEMENTED, emulator verification pending**
**Severity: Low.** The two-state contract (focus frame + last-active) is implemented, but the
tint is only a few percent lighter than the rail background, so when focus is elsewhere it is
hard to tell which category the grid belongs to. Same on the episode picker's season list.

## QA-8 — Entering Series focused the header search and opened the IME — **not reproduced**
**Severity: unknown. Reported by the user, not seen in QA.** Entering Series and Live both
focused the rail cleanly with no IME across repeated attempts. `BrowseScreen` already carries
a comment about "RIGHT out of the rail opened the search field instead of crossing to the
grid", so the *class* of bug is known. Best hypothesis: a race where the rail has no
categories yet, so the first D-pad press runs an origin-less 2D focus search and the header
field wins. **If it recurs, note whether the catalog was mid-sync.**

## QA-9 — Up from the first content row targets the left category filter — **IMPLEMENTED, emulator verification pending**
**Severity: Medium.** From the first row of the Movies grid, pressing UP focuses the left `Filter categories` field and opens the TV IME. It should transfer focus to the right-pane item-search control.

## QA-10 — Whole-number ratings render with a trailing `.0` — **IMPLEMENTED, emulator verification pending**
**Severity: Low.** Card and detail ratings render values such as `7.0` and `5.0`; whole numbers should render as `7` and `5`, while fractional ratings retain their decimal part.

## QA-11 — Playback connection is not released before the next stream — **CONFIRMED on emulator 2026-09-12**
**Severity: High.** After leaving a movie stream and starting Live playback, the panel displayed `Another device may be using this account.` No other device was active. Force-stopping Tvivo released the stale session. This is a user-visible false account-conflict failure and needs an explicit player-teardown check.

## T-D4 — Decide whether Diagnostics should record routine events
**Not a defect.** Every `DiagnosticLog` call site is app start, a sync lifecycle event, or a
failure, so a *healthy* session leaves the screen nearly empty (see Q-22). That is defensible
— it is a diagnostics screen, not an activity log — but it means the screen cannot answer
"what did the app just do?", only "what went wrong?". Adding category selection and the
`get_vod_info` / `get_series_info` fetches would answer both. Costs noise; decide before
Phase 5 closes.

---

# Part 1b — Platform constraints, not defects

Recorded so they are not re-investigated as bugs.

## Q-26 — Virtual folders rendered as "Loading…" forever — **FIXED, verified**
**Severity: High. Found 2026-09-10 on-emulator, building the virtual folders.**
`ui/browse/BrowseViewModel.kt`

`RECENTLY ADDED` showed `100` in the rail and `Loading…` in the grid, indefinitely. The
list was in hand the whole time.

**Cause:** `PagingData.from(list)` — the overload without `sourceLoadStates` — leaves
`refresh` as `LoadState.Loading` forever. The grid's loading branch therefore always won,
and `itemCount` read 0 over a list it was already holding.

**Fix:** pass explicit `LoadStates`, all `NotLoading(endOfPaginationReached = true)`,
because a virtual folder *is* the whole list — there is no next page. **Verified on
emulator:** 100 cards render.

## Q-27 — Back from the episode picker skipped the pre-run page — **FIXED**
**Severity: Low. Found 2026-09-10 during the full sweep.**
`MainActivity.kt`

With the pre-run page inserted before the picker, `Route.SeriesDetail`'s Back still went
straight to the shows grid — skipping a screen the user had walked through, and losing the
heart they may have gone back for. Now returns to `Route.ItemDetail`.

## Q-25 — The browse screen opens with nothing focused — **FIXED, unverified on-emulator**
**Severity: High. Root cause behind the Q-24 report. Found 2026-09-10.**
`ui/browse/BrowseScreen.kt`, `ui/browse/CategoryRail.kt`

Opening Movies leaves **no node focused at all** — confirmed by `uiautomator dump`, which
reports zero `focused="true"` nodes on the browse screen. An Android TV screen with no
focus owner has no D-pad behaviour: the first press runs a 2D search with no origin to
measure from, so it lands wherever the heuristic likes. That is why RIGHT out of the rail
opened the search field instead of crossing to the grid — the press was never *leaving*
the rail, because focus had never been in it.

**Every screen that had this bug had it invisibly.** Nothing looks wrong in a screenshot;
the defect is that the screenshot has no focus frame in it, which reads as "before the
user pressed anything".

**Fix, three parts:**
1. The rail is the resting focus, requested once the first categories arrive — it is
   where a user decides what to look at. Requesting on the LazyColumn's `focusGroup`
   delegates to its first focusable child, which is safe against rows not yet composed.
2. **UP from the top of the list opens the filter** (the decided idiom). Declared on the
   first row only, or UP from row 40 would jump to the filter instead of row 39. The
   field is pinned above the list and is not part of it, so without the override the 2D
   search is free to leave the rail entirely and hand UP to the grid — which it did.
3. The field reads **`Search`** at rest rather than `Filter categories`: the user is
   looking for the verb, and the rail is the only thing on that side of the screen, so
   what it searches is not in question.

**Worth a test, not just a fix.** "Does this screen have a focus owner when it opens" is
one assertion per screen and would have caught this — T-T2.

## Q-24 — Every text field is a horizontal dead end — **FIXED IN CODE, RECHECK PENDING**
**Severity: High. Reported by the user 2026-09-10.**
`ui/common/DpadField.kt`, `ui/browse/CategoryRail.kt`

From Movies, RIGHT out of the rail lands in the `Filter categories` field, and a second
RIGHT does nothing at all. There is no D-pad answer to "how do I get to the grid from
here" other than knowing to press DOWN first, which nothing on screen says.

**Cause: [dpadFieldNavigation] only ever opened a *vertical* escape.** It intercepts
up/down and deliberately leaves left/right to the caret. `RailRow` carries
`focusProperties { right = gridFocusRequester }` and the field above it does not — so
RIGHT crossed to the grid from every row of the rail except the one at the top. The same
gap left the header's `Clear filter` pill unreachable: it sits to the right of the item
filter and nothing could move onto it.

**The caret argument does not survive contact with the device.** The TV IME ships its own
◀ ▶ keys, so caret movement is already served; and while the IME is up it owns every
arrow press and the modifier is never called at all (Q-10). The only time these events
reach the app is once the keyboard is closed — which is exactly when the user has
finished typing and wants to leave.

**Fix:** left/right escape too, consumed **only on a successful move**, so where focus has
nowhere to go (LEFT out of the leftmost pane) the event still falls through to the caret
and nothing is taken away. Plus the explicit `right = gridFocusRequester` on the rail's
field, because the 2D search scores the header's Refresh as a fine candidate to the right
— the same override `RailRow` already needed, and the same lesson as Q-17.

**The rule this settles:** the rail is one pane, and which row of it focus happens to be
on must never change what crossing to the content means.

**QA regression found 2026-09-11:** the expanded item-filter field still trapped
Up, Down, Left, Right, and Back on the field. The generic modifier had no valid
2D destination in that header geometry, and the documented Back-to-close behavior was
not wired. The code fix gives the field explicit Left → rail, Right → Refresh, and
Down → grid requesters, and installs an enabled Back handler that closes and clears the
filter. Debug and release unit suites pass (151 tests); emulator recheck is pending fresh
deployment approval.

## Q-23 — Sign out renders as an empty focus frame — **FIXED, verified**
**Severity: High. Found 2026-09-10 on-emulator, while verifying Q-21/Q-22.**
`ui/settings/SettingsScreen.kt`

Below the D-12 divider the Account screen draws a focusable row with **no text in it at
all** — a focus frame around nothing. It is `Sign out`: the only irreversible action in
the app, reachable on a remote with no indication of what it does. The accessibility dump
is unambiguous — the label node is 10 px tall and the hint node is absent entirely.

**Cause: an unscrollable `Column` does not overflow, it squeezes its last child.** The
screen is 540 dp less 128 dp of vertical padding, so 412 dp are usable, and the content
measures 451 dp (display 40 + username 26 + spacer 32 + three 76 dp action rows + 49 dp of
divider and spacers + a fourth 76 dp row). The 39 dp shortfall comes out of whichever
child is measured last, and D-12 deliberately put `Sign out` there.

**This is the same class as Q-11** — content below the fold on a fixed-height screen — and
it went unnoticed for the same reason every focus defect on this project has: it builds,
it launches, and the row is only wrong once you look at it.

**Fix:** `verticalScroll(rememberScrollState())` on the root `Column`, so every row gets
the height it asked for and TV focus traversal scrolls the row into view. Not a smaller
type scale or tighter spacers — those would fix this instance and leave the next one, and
the screen gains a row whenever an account gains a capability.

**Verified on-emulator 2026-09-10:** `Sign out` renders its label and its
"cannot be recovered" hint, and the `ConfirmDialog` was not reached — no credentials were
touched at any point.

## Q-10 — The TV IME owns the D-pad, so in-form navigation is keyboard-only
**Severity: Low. Not a defect — a platform constraint, recorded so it is not
re-investigated as one.**

While the TV keyboard is up, **every** arrow press goes to the keyboard, not to
the app. `dpadFieldNavigation` never sees them, and no amount of
`focusProperties` changes that. Confirmed on-emulator: pressing RIGHT from the
password field moved the highlight around the on-screen keyboard, not to the
adjacent `Show` button.

Consequences, both acceptable:
- Moving between fields with the IME open is the keyboard's own `Next` / `Done`
  keys, which the form already wires up through `imeAction`.
- `Show`, `Clear` and `Sign in` are reachable once the IME is dismissed with
  Back. This is the standard Android TV model, not something to work around.

**Do not "fix" this by intercepting keys harder.** The earlier login focus trap
came from fighting the same platform behaviour.

## Q-11 — Sign in / Clear sit under the TV keyboard — **FIXED (D-1), verified 2026-09-08**

**Severity: High.** Focusing the password field made the `Sign in` and `Clear`
buttons disappear; they returned only once focus reached the button row.

**Root cause:** `Modifier.imePadding()` in `LoginScreen` was a **no-op**.
`android:windowSoftInputMode="adjustResize"` was set in the manifest, but
`WindowCompat.setDecorFitsSystemWindows(window, false)` was never called, so the
framework consumed the IME inset itself and reported zero to Compose. The button
row therefore stayed where it was, under a keyboard that covers roughly the lower
half of a 1080p panel. It reappeared on Tab only because the row's
`onFocusChanged { keyboard?.hide() }` (the Q-7 workaround) dismissed the IME —
that workaround was masking the real bug rather than fixing it.

**Fix:** `WindowCompat.setDecorFitsSystemWindows(window, false)` in
`MainActivity.onCreate`. One line; `imePadding()` then does what it always read
as doing.

**Distinct from Q-10.** Q-10 is about *reachability* (the IME owns the D-pad, so
arrows cannot move focus out) and remains an accepted platform constraint. This
was about *visibility* — the buttons were off-screen, not merely unfocusable.

**Verified on-emulator 2026-09-08, and the fix is not sufficient.** With the
password field focused and the IME up: the form now scrolls (the title scrolls
off) and the `Show` button is visible beside the field, neither of which happened
before — `imePadding()` is demonstrably working now. But `Clear` and `Sign in`
are **still off-screen**, because a `verticalScroll` Column only brings the
*focused* child into view and the button row sits below it. The remaining ~590 px
above the keyboard cannot hold title + subtitle + three fields + error + buttons.

**Closed by D-1 and verified on-emulator 2026-09-08.** With the IME up, the whole
form — server, username, password and the complete action row — is visible above
the keyboard. The fix was the layout, not the insets: a fixed ~290 dp top-anchored
column with no `verticalScroll` and no `imePadding()`. **Margin is ~0 px**, so any
added padding or a taller header re-breaks it; the reserved one-line error row and
`ErrorCopyTest`'s 48-char budget are what hold it.

**Original analysis, kept because it is why the first two fixes failed:**

**What was left was a layout change, not an inset fix.** The form is a 440 dp
column on a 1920 px screen, so the entire right half is empty. Moving the button
row beside the fields rather than below them takes it out of the IME's path
entirely. That is a design decision, so it is not made here.

**Not blocking sign-in:** the IME's own Done key submits, which is how this was
tested, and dismissing the IME reveals the row.

**Testing note that cost time:** Account → "Sign in to a different account" does
**not** wipe anything — it only routes to Login and keeps `current.credentials`
until a new sign-in is accepted. Only the separate `Sign out` button calls
`store.wipe()`. Entering a deliberately wrong account is therefore a free way to
QA the login screen, and was used for this. Do not conflate the two paths.

## Q-12 — HD/SD stripping made distinct titles look like duplicates — **FIXED**

**Severity: Medium.** Categories appeared full of duplicated posters. They were
not duplicates: the panel publishes the same show once per quality, and
`NameNormalizer` strips the quality token out of `nameDisplay`, so two genuinely
different rows rendered as the same string. Confirmed by the rail itself, which
carries `RAMADAN EGYPT 2026 SD` (25) and `RAMADAN EGYPT 2026 HD` (43).

**The strip must stay** — it is what makes mixed-direction titles truncate
correctly (see the `name_display` reasoning in `docs/architecture.md`). So the
fix restores the distinction alongside it rather than by undoing it:
`NameNormalizer.qualityOf(raw)` re-derives the stripped token, `BrowseItem`
carries it, and `ContentGrid` draws it as a corner badge (top-**start**; this
panel's watermark sits top-end).

**No schema change.** The raw `name` is already stored on all three entities, so
the badge is derived at map time and cannot drift out of sync with `nameDisplay`.

## Q-13 — Opening any listing re-downloaded the whole catalog — **FIXED**

**Severity: High.** Entering Movies/Live/Series re-ran the full-catalog sync
every time, with the progress line reading "Indexing" over data that was already
complete.

**Root cause:** `BrowseViewModel.init` called `syncer.syncVod()/syncLive()/
syncSeries()` unconditionally, and `CatalogSyncer` had **no freshness check of
its own**. The per-category tier had been TTL-gated since it was written
(`CachedFetch.ensureFresh`, `force = false`); the full-catalog tier never was.
The two tiers simply disagreed, and nothing failed loudly enough to show it.

**Measured on-emulator before the fix:** `complete`/13,264 rows at 07:02:42 →
re-entered the listing 11 minutes later → `indexing` and all 13,264 rows
rewritten. Every entry cost a fresh ~15 MB body.

**Fix:** a `force` parameter plus an `isFresh` gate in `CatalogSyncer`, using the
same 24 h window as `CachedFetch`. Only `complete` counts as fresh — `partial`,
`failed` and an interrupted `indexing` must all re-run — and a backwards clock
cannot pin the catalog fresh forever. `Refresh everything` passes `force = true`.

**`refreshEverything` also now syncs series**, which it never did. That was
latent before and load-bearing after: with the gate in place it is the only way
to re-sync inside the 24 h window, so a series catalog would otherwise have had
no manual escape hatch at all.

**Verified on-emulator after the fix:** entering the listing logged
`series catalog sync: within TTL, keeping cached generation`, `updatedAt` stayed
at 07:13:54, and no rows were rewritten.

---
# Part 2 — Missing test coverage

## T-T1 — Room DAO and transaction tests — **DONE**

`CatalogDaoTest` and `CatalogSyncerTest` (Robolectric + in-memory Room, the
latter with MockWebServer). They cover `replaceCategory`'s delete-then-insert,
the generation flip, account scoping, the per-content-type table separation, and
the zero-row guard for VOD, live and series. `SeriesRepositoryTest` adds the
per-show `get_series_info` TTL and its own empty-result guard.

## T-T2 — Instrumented D-pad focus traversal tests — **HARNESS IMPLEMENTED, device run pending**
Compose focus tests on the emulator, per CLAUDE.md. The Android-test harness now compiles with isolated RIGHT and DOWN escape tests for the shared D-pad field contract; execution remains pending fresh emulator approval. Q-1, Q-2, Q-3 and Q-9 are
all focus/layout defects that shipped despite a green build — this is the class
of test that would catch them. Q-9 raises the value further: it was invisible in
code review twice over, since the first fix compiled, read correctly, and did
nothing.

---

# Part 2b — The 2026-09-08 design pass: decided and unbuilt

`/impeccable` pass, four revision rounds with the owner. **Every decision below
is settled** — rationale in `docs/decisions.md`, specification in
`docs/ui-scope.md`, visual reference in **`docs/design/comps.html`** (open it in
a browser before touching UI). Nothing here is built yet.

**Status 2026-09-08: the whole design pass D-1..D-17 is built, deployed and
verified on-emulator.** Phase 5 has also landed its `RefreshWorker` (WorkManager
job confirmed scheduled), an on-device Diagnostics screen, `supportsRtl`, and a
grid loading state. 149 unit tests pass.

The QA pass that verified it found six defects, Q-15..Q-20; all six are fixed,
and Q-15..Q-20 are verified except where noted. Q-21 and Q-22 are open and were
found by the same pass.

Sequencing: **D-4 and D-5 touch nearly every screen.** Land them first so
everything else is built against the real scale and roles rather than twice.

## UI work

| id | Change | Files | Note |
|---|---|---|---|
| **D-1** | Login: centred 820 px column, server full-width, username+password paired, one action row. Everything focusable above the IME ceiling. | `auth/LoginScreen.kt` | Supersedes the partial Q-11 fix **Built.** Content is ~290 dp, top-anchored, no scroll and no `imePadding()` — above the ceiling by layout |
| **D-2** | Login error copy to a hard **one line** | `ui/common/ErrorCopy.kt`, `LoginScreen.kt` | Two lines push buttons into the IME **Built.** `ErrorCopy.forLogin` + `ErrorCopyTest` holds the 48-char budget |
| **D-3** | Rail 360 dp → **280 dp**; labels wrap to 2 lines, never ellipsised; tooltip only past 2 lines | `ui/browse/CategoryRail.kt` | Truncation recreates Q-12 **Built.** 280 dp, `title` role, 2 lines, conditional tooltip on overflow |
| **D-4** | **Type scale as roles** (`display`/`headline`/`title`/`body`/`label`/`caption`), 12 dp floor. Retires every hand-picked `sp`, including the 10 dp quality badge | new `ui/theme/Type.kt` + ~6 UI files | Wide blast radius **Built.** `ui/theme/Type.kt`; all 40 hand-picked `sp` call sites now name a role |
| **D-5** | Map `Palette` onto **Material colour roles** so contrast variants resolve | `ui/theme/Palette.kt`, theme setup | Wide blast radius **Built.** `ui/theme/Theme.kt`; `TvivoTheme` installed in `MainActivity` |
| **D-6** | **Card titles overlaid** on the poster over a bottom scrim, 2 lines then ellipsise. Live TV keeps titles below (card too short) | `ui/browse/ContentGrid.kt` | **Closes Q-8.** Already in `ui-scope.md`, never implemented **Built. Closes Q-8.** Poster titles overlaid on a gradient scrim; live keeps titles below |
| **D-7** | **Icon row on Home**: Refresh / Account / Exit as 88 dp pills, label revealed on focus | `ui/home/HomeScreen.kt`, new `ui/common/IconPill.kt` | **Built.** `ui/common/IconPill.kt`; Exit is last in the row *and* behind a confirm |
| **D-8** | Browse header uses the **same** icon pill component | `ui/browse/BrowseScreen.kt` | Refresh is a bare text link today **Built.** Same `IconPill`; label says `Refresh this category` to name the scope |
| **D-9** | **Home tiles get photographs** (520×300, `docs/design/img/`) under a scrim | `ui/home/HomeScreen.kt`, `res/drawable*` | Screen for brand marks |
| **D-10** | **Splash screen** — mark, wordmark, real progress bar | `res/`, `MainActivity.kt` |  |
| **D-11** | **App mark + 320×180 TV banner** from `docs/design/img/*.svg` | `res/drawable/`, manifest `android:banner` |  |
| **D-12** | `Sign out` below a divider, consequence in the hint, confirm dialog with **default focus on the safe option** | `ui/settings/SettingsScreen.kt` | **Built.** Divider, consequence in the hint, `ui/common/ConfirmDialog.kt` with safe-option default focus |

## Feature work

| id | Change | Files | Note |
|---|---|---|---|
| **D-13** | **Category filter** — persistent bar pinned above the rail, filters category names live | `CategoryRail.kt`, `BrowseViewModel.kt` | |
| **D-14** | **Item filter** — grid-header search icon expanding in place into a pill with a clear button; filters the current category while typing, debounced 300 ms on `Dispatchers.IO`; header reports `N of M` | `ContentGrid.kt`, `BrowseViewModel.kt`, DAO query per content type |  |
| **D-15** | **Subscription** as its own read-only screen behind `Show subscription`; Account keeps only account actions | new `ui/settings/SubscriptionScreen.kt`, `SettingsScreen.kt`, `MainActivity.kt` | new `ui/settings/SubscriptionScreen.kt`, `SettingsScreen.kt`, `MainActivity.kt` — **Built** |
| **D-16** | Subscription copy **reports** `max_connections`, never asserts a limit | `SubscriptionScreen.kt` | **Built.** Reports the number and stops |
| **D-17** | Move Refresh and Exit **off** Account (they become D-7) | `SettingsScreen.kt` | **Built.** Both now live in Home's icon row |

## Open questions — answer before or during the build

- **Exit placement — answered 2026-09-08: confirm dialog.** It is one press from
  the first screen and easy to hit by accident on a household remote, so it
  carries the same treatment as Sign out: a two-option dialog with default focus
  on `Stay in Tvivo`. Built that way, and it also sits last in the row, so the
  reflex press after opening it is harmless and reaching it takes travel.
- **Icon set.** The glyphs in the comps are Unicode placeholders. The mark now
  gives a visual language (one accent stroke, rounded caps) to draw a real set
  against — this is `T-D1`; D-7/D-8 ship with placeholders until it lands.
- **Title overlay coverage.** Overlaying costs the bottom ~15% of the artwork,
  more on two-line titles. Capped at 2 lines + ellipsis, safe here only because
  the quality badge is a separate element.

---

# Part 2c — Filed 2026-09-10: new items, not yet scheduled

Nine gaps that were never work items. Ordered by **cost against what they
unblock**: N-1..N-3 are cheap and something else is waiting on each of them,
N-4..N-5 are cheap and self-contained, N-6..N-9 are real features.

Nothing here is built. None of it is in `docs/ui-scope.md` or `decisions.md`
yet — file the decision there if one of these is taken.

## N-1 — An unhandled-exception hook that writes to `DiagnosticLog`
**Cost: small. Unblocks Q-14. Implemented 2026-09-11; 151 unit tests pass; device verification pending.**

Q-14 (the phone crash) has been open with no root cause for two days for exactly
one reason: no logcat has ever been captured from that device, and the crash
happens before anything can be attached. An `UncaughtExceptionHandler` installed
in `Application.onCreate` that appends the stack trace to the existing on-device
`DiagnosticLog` turns "reproducible but untraceable" into "open Diagnostics and
read it" — on any device, with no adb, including the physical TV later.

Must write **synchronously** on the crashing thread and survive the process
dying; a coroutine launch will not finish. Ties into T-D4 — this is the strongest
argument that Diagnostics needs to record more than failures it happens to catch.

Implemented as `TvivoApplication` + `CrashDiagnostics`: the handler records only
exception types and bounded stack frames (never messages), syncs the file before
delegating to Android's prior handler, then consumes and deletes it on next launch.

## N-2 — Define what the user sees when max_connections is exhausted — **IMPLEMENTED, physical-TV verification pending**
**Cost: small. Gates the physical-TV session.**

`max_connections` is `1` on this account, so a second concurrent stream — another
device in the house, or the app's own previous player not yet torn down — is not
an edge case, it is the **first playback failure a real user will hit**. Nothing
decides what appears today; the panel's refusal surfaces as whatever opaque
Media3 error the `.ts`/`.mkv` open produces.

Needs: the error copy (per the `ErrorCopy` pattern, one line), and a decision on
whether the app *reads* `active_connections` from `server_info` before opening a
stream to pre-empt it. **The copy must report, never assert a limit** — the
constraint in `CLAUDE.md` applies here more than anywhere, since this is the one
screen tempted to say "you can only watch one thing at a time".

Best specified now and verified in the physical-TV session, where it can actually
be provoked.

## N-3 — A defined cached-catalog-but-no-network path — **IMPLEMENTED, emulator verification pending**
**Cost: small-to-medium. Blocks Phase 5's error-state work from being complete.**

The app assumes the panel is reachable. A household router being down, or the TV
waking before the network, is an ordinary state, not a failure — and the catalog
is fully cached locally, so browsing *should* work. Undefined today: whether the
grid renders from cache, what the per-category refresh does when it cannot reach
the panel, and whether a Play attempt says something better than a generic error.

`CachedFetch` already distinguishes fresh from stale; what is missing is a UI
contract over "stale and cannot refresh". Fold into Phase 5's empty/loading/error
state table rather than building it separately.

## N-4 — "This category did not sync" is indistinguishable from "empty" — **IMPLEMENTED, emulator verification pending**
**Cost: small. The data it needs already exists.**

The zero-row guard records `partial` instead of flipping the generation — that
flag is written and never read by the UI. So a category that failed to sync and a
category the panel genuinely ships empty render identically, and a refresh looks
like it did nothing. Surface `partial` as a distinct state with the retry
affordance the empty state does not need.

Depends on N-3's state table; do them together.

## N-5 — Verify a resume position survives process death — **LOCAL COVERAGE IMPLEMENTED, hardware verification pending**
**Cost: verification first, possibly zero code.**

`resume_positions` and `favourites` are the only user data the app holds, and
`CONTINUE WATCHING` is built directly on the former. Backing out of the player is
tested; **the player being killed is not** — a TV box reclaiming memory, or the
user pressing Home mid-film. If the write only happens on a clean teardown, the
row is lost silently.

Check the write path first (`onPause` vs `onStop` vs teardown); it may already be
correct. Related: U-13 was a data-loss bug in this same table, which is reason
enough not to assume.

## N-6 — Episode-level continue watching, and *next* episode
**Cost: medium. Depends on N-5.**

`CONTINUE WATCHING` reads `resume_positions`, but whether a part-watched *episode*
lands there is undefined, and if it does, the useful behaviour is not obvious:
resuming the same episode is right mid-episode, and offering the **next** one is
right after a finished episode. A series row also wants to show the show, not the
episode, as its title.

Do not start before N-5 — if the resume write itself is lossy, this builds on
sand. Sub-60s replay handling already has a precedent in `5148343`.

## N-7 — Global search across all three content types
**Cost: largest here. Fully unblocked, no network work.**

D-14 filters **within the current category only**, so finding a film today
requires already knowing its category — across 48,780 VOD rows, 6,424 channels
and 13,279 shows, that is the difference between a catalog and a haystack. The
full-catalog tables are already synced and local, so this is a DAO query per
content type plus a screen; **no panel call at all**.

Reuses D-14's shape: debounced 300 ms on `Dispatchers.IO`, results as
`BrowseItem` so the existing grid and pre-run page take them unchanged. Open
questions: where it is reached from (Home icon row is the obvious slot, and D-7
already established the component), whether results group by type or interleave,
and whether the TV IME ceiling rule forces the field into the upper half — it
does, and that decides the layout before anything else.

**Declines to reopen voice search** — that is in "Considered and not taken" and
this is the typed path that made it unnecessary.

## N-8 — One-click diagnostic reporting from the TV
**Cost: medium. Depends on N-1 and the existing `DiagnosticLog`.**

A file export is not useful on a TV: the user is unlikely to have a file manager,
email client or practical way to move the artifact elsewhere. Replace it with a
remote-first **Report a problem** action in Diagnostics:

1. Show a short consent screen stating exactly what will be sent, with default
   focus on **Cancel**.
2. On confirmation, send a strictly allowlisted report over HTTPS.
3. Show a short reference such as `TV-4K7M2` when accepted; require no keyboard,
   copying or second device.
4. If offline, retain the pending report and retry through WorkManager, while
   making the queued state visible to the user.

The app should send to a small app-owned reporting endpoint. That service may
email the developer initially and may later create a sanitized GitHub issue.
**Never create issues directly from the APK:** a GitHub token or mail-provider
credential embedded in a public Android package can be extracted. The repository
is public, so detailed diagnostics must never be posted to an issue; at most the
service posts a coarse summary plus the private report reference.

Build the payload from an explicit allowlist rather than collecting broadly and
redacting afterward. Useful fields: app/build version, Android API and TV model,
timestamp, current screen, normalized failure category, recent redacted
diagnostic events, and coarse cached/partial/sync/playback outcomes. Never send
credentials, panel hostname or port, request/playback URLs, account or content
ids, titles, category names, search text, exact resume positions, or raw
exception messages. Use normal certificate validation, payload and retention
limits, server-side rate limiting, and a visible **Delete local diagnostics**
action. Document the data sent before enabling the feature.

## N-9 — Profile and optimize Room retrieval for instant-feeling browse
**Cost: medium. Performance milestone, not speculative index work.**

The local catalog is large enough that database latency is product behavior:
opening a category, moving between virtual folders, filtering, returning from a
detail page, and restoring a far-away card should feel immediate even while a
full-catalog sync is writing in the background. Define budgets before changing
the schema: cached category/header data visible within 100 ms, first grid page
within 200 ms, filter results within 300 ms after the existing debounce, and no
main-thread disk I/O or focus loss while paging invalidates.

Measure representative worst cases on the real database (large category,
48k-row VOD catalog, Recently Added, Continue Watching, Favourites, filtered
query, and BACK at a deep grid position). Capture Room query plans with
`EXPLAIN QUERY PLAN`, query timings, PagingSource invalidations, allocation/GC
pressure, and sync/read contention. Add benchmark fixtures that preserve the
catalog's real order of magnitude without using provider data.

Optimize only from observed plans. Check composite covering indexes against the
actual WHERE/ORDER BY clauses; normalized-name filtering; joins/lookups used by
virtual folders; bounded projections instead of full entities where possible;
transaction size and WAL behavior during generation flips; and whether count
queries duplicate expensive scans. Every added index must justify its sync and
storage cost. Do not disable placeholders or stable keys: deep focus restoration
depends on them. Treat the latency budgets as regression gates, with
Macrobenchmark or instrumented measurements on the emulator and final
confirmation on the physical TV after explicit deployment approval.

## N-10 — Handset touch activation path needs form-factor flexibility
**Cost: small. Deferred until other handset form factors are supported.**

The 2026-09-12 handset Home tile fix extracts `detectTapGestures` specifically
for non-TV devices and wires it through `onSelect` alongside the Android TV `Card`
click path. This works for phones but assumes all non-TV devices behave the same
way. If tablet support is added later, the touch/D-pad/focus contract may differ
again, and a blanket "if not TV, use touch workaround" will need revisiting.

**Rule for future form factors:** do not generalize a handset touch workaround to
other non-TV devices without device evidence. Verify touch input paths, focus
traversal, and card activation on each new form factor independently before
merging the code.

**Current scope:** phone-only right now; defer tablet/other form factors until
they are actively supported.

## N-11 — Diagnostic log verbosity on production
**Cost: small. Production-facing decision, not code.**

2026-09-12 refactored `DiagnosticLog` with a structured schema and added call
sites at every navigation/sync/failure point. The log is now queryable and safe
to export (redaction is enforced in the logger, not at call sites). On the
emulator, a healthy 45-minute session produces only four lines (app start + three
TTL skips). That is correct and expected.

On a real TV, the log is persistent and bounded (200 entries); a longer session
or one with background sync + user navigation may fill it faster. Measure actual
verbosity on the physical TV during Phase 5 validation. If routine events (e.g.,
category selection, image loading, non-error sync progress) are worth recording,
decide that explicitly as part of T-D4 (Decide whether Diagnostics should record
routine events) rather than retroactively adding call sites.

**Current state:** bounded, structured, and safe; validate density on hardware.

## N-12 — Database size profiling and optimization
**Cost: medium. Measurement first, optimization only from observed data.**

The local catalog holds 48,780 VOD rows, 6,425 live channels, and 13,279 series
shows, plus per-show episode lists (some shows have 100+ episodes). Room caches
poster images downsampled to card size (220×330 and 220×124 px). The app does
not yet have a manifest size constraint or a user-visible storage footprint
warning.

Before Phase 5 closes, measure the actual database footprint on the real panel:
total `.db` file size, per-table sizes, image cache size, and whether the TTL
+generation logic produces dead rows or orphaned image blobs. Add a
`diagnostics/DbMetrics` query that reports these to the Diagnostics screen so
the user can see what is consuming space.

Check for: rows retained past the TTL window (generation flips should clean them,
but verify the migration is correct), orphaned image files if the image loader
ever fails, and index bloat from the per-content-type and per-category structures.
If the database exceeds a reasonable threshold on real hardware (e.g., >500 MB),
decide whether to add explicit user-triggered cleanup, reduce the full-catalog
sync window, or implement incremental/differential sync instead of replace-all.

**Current state:** no profiling or optimization done; measurement is prerequisite.

## N-13 — RAM usage profiling and heap pressure
**Cost: medium. Empirical measurement on the physical TV is essential.**

The app holds the full in-memory `PagingData` for each category's grid, plus the
Compose UI tree for the rail, detail pages, and player. With 9,750-row categories
and simultaneous image-loading on a TV with 1–2 GB heap, memory pressure during
scroll/filter operations and background sync is unverified.

Profile on the physical TV during Phase 5:
- Measure heap size at launch, after catalog sync completes, and during active
  browsing (rail changes, grid scroll, category filter, detail page open, player).
- Capture GC frequency and pause times, especially during scroll and while a
  background sync writes to the database.
- Check whether placeholders or stable keys cause memory bloat (they should not,
  but confirm on real hardware).
- Profile image loader concurrency — how many images load simultaneously, and
  whether the downsampling/caching pipeline is a bottleneck or a pressure point.

If GC pauses or OOM behavior are observed, decide whether to: cap the in-memory
PagingData window per category, implement LRU eviction for off-screen image
caches, or reduce background sync concurrency when the heap is above a threshold.

**Current state:** no profiling done; the app is "responsive on the emulator" but
heap constraints are unknown on real hardware.

---

# Part 3 — Remaining build phases
## Phases 0-3 + Account screen — **DONE**

Full detail is in git (`96051c8`, `a75931e`) and the docs. What a new session
needs to know:

- **The browse screen is generic.** The grid renders `BrowseItem` with
  `CardShape` as the only per-type input, and one `BrowseViewModel` picks a
  `CatalogSource`. `StreamListParser` holds the streaming JSON reader;
  `VodStreamParser` / `LiveStreamParser` are field mappings over it.
  **Phase 4 adds a `CatalogSource` factory, not a screen.**
- **Each content type gets its own table.** `stream_id` is unique only *within*
  a type, so a shared `(accountId, streamId)` key would let a channel and a film
  overwrite each other. Series needs the same.
- **A zero-row full-catalog response must never flip the generation.** A panel
  that rejects the no-`category_id` call answers with an object, which the
  parser reports as zero rows — indistinguishable from an empty catalog, and
  flipping on it deletes everything cached. Both paths record `partial` instead.
  This was latent in the VOD path and only found while building live.
- `get_live_streams` **does** answer with no `category_id` (6,425 channels in one
  call). `get_series` is the last unverified endpoint.
- Live playback is shipped but **never executed** — `max_connections` is 1.

## Phase 4 — Series — **DONE**

Built as predicted: one `CatalogSource` factory, a `series` table, and the
season-keyed-object parsing. No second browse screen. What a new session needs:

- **`get_series` answers with no `category_id`** — 13,264 shows in one call.
  That was the last unverified endpoint; the full-catalog tier now covers all
  three content types, and all three run through **one** `syncCatalog` body in
  `CatalogSyncer` rather than three copies of the zero-row guard.
- **A show is not playable.** `series_id` addresses no stream endpoint, so
  activating a show opens `SeriesDetailScreen` (season rail + episode list,
  deliberately the browse screen's layout) instead of the player. `MainActivity`
  routes on content type; the grid stays type-agnostic.
- **Episodes are their own table**, keyed `(accountId, seriesId, episodeId)` with
  `episodeId` a **string** — it is what goes in the playback URL, and nothing is
  gained by round-tripping it through Int. No generation column: episodes are
  fetched one show at a time, so plain delete-then-insert is right.
- `replaceEpisodes` **refuses an empty list**. A rejected `get_series_info`
  parses to zero episodes, and wiping a cached season on that makes an
  already-cached show unplayable offline. Same reasoning as the catalog guard.
- Episode freshness reuses `CachedFetch` under a distinct content type
  (`series_info`) with the show id in the category slot, so show 770 and
  category 770 cannot make each other look fresh.
- **`duration_secs` is not trustworthy on this panel** — a 60-episode drama
  reported 9–190 "seconds" per episode. `duration` (`HH:MM:SS`) is read first.
- Episode titles go through the same `NameNormalizer` display pass as the grid;
  without it the picker reads `HD` on every row.

## Phase 5 — Hardening
RTL verification, empty/loading/error states, `RefreshWorker`, on-device
diagnostic log. Physical-TV validation happens once here — the live `.ts` path
carries the most exposure under that choice.

**Manual refresh is done**, at both scopes: per-category in the browse header,
and `Refresh everything` on the Account screen (every category plus both
catalogs, TTL ignored).
## Account screen — done, and it is how you test login

`ui/settings/` carries signed-in user, server, status, expiry (highlighted
inside 7 days), `active/max` connections, and the actions: refresh everything,
sign in to a different account, sign out, exit.

**To reach the login screen, use Account → "Sign in to a different account".**
It keeps the current account signed in until a new one is accepted, and Back
returns to Home. Clearing app data instead destroys credentials that **cannot be
recovered** — the Tink keyset is not exportable, so a backup of the credential
blob would not decrypt.

## T-A1 — Multiple saved accounts (follow-up)

Today the store holds **one** credential set: switching accounts replaces it, so
coming back means re-typing. The cached catalog is already account-scoped by
`accountId`, so the data layer needs nothing — what is missing is a list of
credential sets in `CredentialsStore` plus a "current" pointer, and a picker.

Worth doing if more than one panel is genuinely in use; not before.

---

# Part 4 — Deferred design decisions

## T-D1 — Finish the design system (DESIGN.md)

**What:** Run `/design-consultation` and produce a `DESIGN.md` covering what is
still missing: motion spec, icon set, and a product wordmark.

**Why:** `/plan-design-review` (2026-09-07) rated design-system alignment 0/10
because there was nothing to align to. Both outside reviewers independently
flagged the same gap.

**Already closed:** colour and contrast — a dark teal surface ramp with a
Claude-orange accent, every pair measured against WCAG. See "Colour" in
`docs/ui-scope.md`.

**Now more urgent than when filed.** Q-1 and Q-3 are both cases of real screens
drifting from the colour and focus system, which is the exact failure mode a
design system exists to prevent. There are now real screens to design against,
which was the stated reason for waiting.

**Suggested timing:** after the Q-1/Q-3 fixes, before Phase 5.

## T-D2 — Decide list behaviour: key-repeat, jump affordance

**T-D2b — Key-repeat and fast-scroll.** Undefined, and the full-catalog sync
makes it matter: holding DOWN in `ALL` traverses 9,750 rows. Needs a decision on
repeat acceleration, and on whether focus movement stays 1:1 with key events or
switches to page-jumps past a threshold.

**T-D2c — Jump affordance in `ALL`.** The accepted cost recorded in
`decisions.md`: with no jump affordance the end of a 9,750-row list is
unreachable in practice. Revisit if `ALL` proves to be a dead end in use.

**Depends on:** both are best judged on hardware, so realistically they land with
the Phase 5 physical-TV session.

## T-D3 — Newest-first sort toggle for category grids

**What:** a second sort option, `added` descending, alongside the alphabetical
default, reachable per category.

**Why:** alphabetical fixes scanability but loses "what's new in this category".
Unlike `ALL`, a single category is small enough that either ordering is usable.

**Context:** `RECENTLY ADDED` already exists as a separate bounded virtual rail
(30 items, catalog-wide). This is per-category, inside a grid.

**Unblocked now** — Phase 2 shipped the grid and the long-press context menu to
hang a sort control off. Still needs a decision on whether the choice persists
per category, globally, or for the session only.

---

# Part 5 — Environment notes (not app defects)

Recorded because each cost real time to diagnose and will recur.

- **Emulator media volume defaults to 3/15.** Playback is inaudible and looks
  like an app bug. Confirmed 2026-09-07: the stream was AAC, decoded cleanly by
  `c2.android.aac.decoder`, no decoder errors — the volume was simply low.
  Raise with 14× `adb shell input keyevent 24`.
- **`hw.keyboard=yes` breaks Esc-as-Back.** The host keyboard becomes a real HID
  device, so Esc arrives as `KEYCODE_ESCAPE` (111) instead of `KEYCODE_BACK` (4)
  and the app ignores it. Fix is environmental: Extended Controls → Settings →
  General → "Send keyboard shortcuts to" = Emulator controls. **Do not add a
  `KEYCODE_ESCAPE` handler to app code** — no real TV remote sends that key.
- **Physical-keyboard typing needs `forwardShortcutsToDevice`, and it is stored
  outside the repo and outside the AVD.** It lives in the Windows registry at
  `HKCU\Software\Android Open Source Project\Emulator\set`, value
  `forwardShortcutsToDevice` (REG_SZ `true`/`false`) — the backing store for
  Extended Controls → Settings → General → "Send keyboard shortcuts to". It is
  absent by default, so a fresh emulator profile silently loses it and the host
  keyboard stops typing into the guest. Restore with:
  `Set-ItemProperty -Path 'HKCU:\Software\Android Open Source Project\Emulator\set' -Name forwardShortcutsToDevice -Value true -Type String`
  Takes effect only on emulator restart.
  **This directly conflicts with the Esc-as-Back note above** — `true` sends keys
  to the device (host keyboard types, Esc arrives as `KEYCODE_ESCAPE` and Back
  breaks); `false` keeps emulator shortcuts (Esc is Back, host keyboard does not
  type). Pick per task; they cannot both be satisfied.
- **Prefer pasting over typing for credentials.** `clipboardSharing` is already
  `true` in that same registry key, and `adb shell input text '<value>'` works
  regardless of either setting. Neither needs the host keyboard.
- **`-gpu swiftshader_indirect` is required.** The host GPU path paints a black
  window while the guest renders correctly.
- **`-memory 1536`, and stop the Gradle daemons first.** 16 GB total does not fit
  the emulator plus the Gradle (1536m) and Kotlin (768m) daemons. The emulator
  gets OOM-killed mid-session otherwise. App launch went 34s → 1.4s after this.
  `tools/emulator.sh` does all of the above.
- **`adb shell` corrupts binary on Windows** (newline translation). Use
  `adb exec-out` to pull databases or screenshots.
- **Android TV AVDs have no touchscreen** (`hw.screen=no-touch`). Mouse clicks
  do nothing by design; `input tap` never works. Drive with `keyevent` or arrow
  keys.

---

# Considered and not taken

Recorded so they are not re-proposed as new ideas:

- **Idle dim / screensaver.** A static rail on an OLED panel risks burn-in after
  ~5 min idle. Small and self-contained; judged not worth tracking yet.
- **Manual refresh control placement.** Now built into the browse header.
- **Voice search.** Most Android TV remotes have a microphone, and search is the
  only place outside login that asks for typing. Declined as a whole integration
  for one field; the rail's category filter already keeps most navigation
  typing-free.
- **`KEYCODE_ESCAPE` handling in the player.** See Part 5 — an emulator config
  artefact, not a product requirement.
