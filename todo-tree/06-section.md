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

