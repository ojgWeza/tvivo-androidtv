## Desktop follow-up — playback and episode-picker regressions (filed 2026-09-14)

### D-Desktop-1 — Movie playback must become visible or fail visibly

**Observed:** Selecting a movie opens no playback and presents no useful error.

**Action:** Trace the desktop player from movie selection through URL construction,
LibVLC media creation, and native events. Keep the bundled runtime as the default.
Render an explicit preparing, playing, or actionable error state; never leave a
blank/unchanged player surface. Validate using synthetic media first. A real
provider stream may only be opened in an owner-approved manual session.

### D-Desktop-2 — Episode rows need concise, non-duplicated labels

**Observed:** Every row repeats the series name and season, e.g. `Season 1 ·
Series name · Episode 1`.

**Action:** Render the selected season in the picker heading and label each row
only with its episode number/title, e.g. `Episode 1`. Preserve the provider's
episode order and use the exact string episode ID only for playback and resume
state, never as the visible label.

### D-Desktop-3 — Replace the all-season episode dump with a season picker

**Observed:** All seasons render at once, making episode navigation long and
obscuring the Back action.

**Action:** Add a visible season drop-down/filter at the top of the series
screen. It defaults to the first available season, filters the list to that
season only, and retains the selected season when returning from the player.

### D-Desktop-4 — Episode activation must not leave a partial selection

**Observed:** Clicking an episode leaves it in a partial selected state while
the route changes or playback fails.

**Action:** Use one clear focus/pressed treatment. Clear transient selection when
navigation fails or returns, and show the player state/error in the player
screen rather than presenting a permanently half-selected episode row.

### D-Desktop-5 — Back must stay visible in the episode picker

**Observed:** The Back button is appended after every episode, requiring users to
scroll to the end of a long series before they can leave.

**Action:** Put Back in a persistent header beside the season control. It must
remain visible while the episode list scrolls and return to the series browse
screen without changing watch state.

### D-Desktop-6 — Suppress zero-value ratings

**Observed:** Catalog cards display `Rating 0` for provider records with no real
rating.

**Action:** Treat blank, malformed, `0`, `0.0`, and equivalent numeric-zero
ratings as absent. Render a rating only when its numeric value is greater than
zero; otherwise show the content-type metadata without a rating label.

### D-Desktop-7 — Rebuild episode selection as a deliberate flow

**Severity: High. Reported 2026-09-14, manual desktop run.**

The current episode-picker click behavior is confusing and can leave the user
with a partial-looking selection while route changes or playback fails.

**Action:** Rework the picker as one coherent interaction: selected season is
clear, each episode has one unambiguous primary action, activation transitions
to a visible player preparation state, and Back returns without any stale
episode selection. Keep the episode ID as an exact string for URL/resume only.

### D-Desktop-8 — Keep player title in a horizontal, bounded header

**Severity: High. Reported 2026-09-14, manual desktop run.**

The player title can be squeezed into a narrow column and wrap one character
per line. The defect is more apparent in windowed mode than in the current
app-wide maximized mode.

**Action:** Give the player header a fixed horizontal layout: title is a
single-line, ellipsized label with a sensible minimum width; state is a
separate bounded label. It must never force vertical-character wrapping.

### D-Desktop-9 — Implement true video-player full screen

**Severity: High. Reported 2026-09-14, manual desktop run.**

The existing Full screen action only maximizes the entire application window.
It leaves browse chrome and creates visual noise instead of a playback mode.

**Action:** Implement the player-specific full-screen state described in
`docs/design/desktop-player.md`: the native video surface owns the window;
controls are an opaque sibling dock outside the AWT surface; Play/Pause, Stop,
seek, Exit full screen, and Back stay operable. Do not overlay Compose controls
on the heavyweight AWT video surface.

### D-Desktop-10 — Restore actual playback and make failure actionable

**Severity: Critical. Regression reported 2026-09-14, manual desktop run.**

Movies still do not play after the bundled LibVLC change. A screen transition
or an "Opening stream" label is not playback evidence.

**Action:** Trace the full chain: selected item/episode, URL construction,
native surface readiness, bundled LibVLC plugin discovery, media creation,
play result, and native error events. Keep all credentials and full provider
URLs out of logs and UI. First validate the LibVLC surface with a local
synthetic fixture; a real provider stream may be opened only in an
owner-approved manual validation session.

**Status as of 2026-09-14, end of session -- uncommitted, not yet re-verified live:**
Root cause of the original "no video" symptom was an HWND-timing race in the
hand-rolled JNA `LibVlcPlayer`; fixed once, then during live-stream testing a
second, more serious bug surfaced: `EmbeddedMediaPlayer`/native `stop()` calls
could block indefinitely on a stalled provider socket, freezing the whole UI
thread (not just the player) since they ran on the same dispatcher as
everything else. Rather than patch the hand-rolled JNA bindings further, the
player was migrated to `vlcj` (mature LibVLC binding) end to end, plus:
- A 20s playback watchdog (`DesktopShell.kt`) that stops and shows an
  actionable timeout message if a stream never reaches Playing.
- All native control/lifecycle calls (`play`, `playUrl`, `pause`, `resume`,
  `stop`, `close`, the resume-seek in the `playing` callback) serialized
  through a single background executor in `LibVlcPlayer.kt`, so `close()`
  (Back navigation) can never race a blocked `stop()` or an in-flight `play()`.
- A `terminal` flag in `DesktopShell.kt` so a stale async "Stopped"/"Ready"
  event can't overwrite the watchdog's timeout message on screen.

Went through 4 rounds of Codex review (`bible-detail/claude-07-agent-division.md`
loop) -- each round found a real race, now fixed; Codex's context ran out
before it could run the actual test (fixture or live). **The fixture-playback
fix from before the vlcj migration was visually confirmed working twice**
(local `sintel-trailer.mp4`, moving video + working controls), but **the vlcj
migration + all the serialization/watchdog fixes above have only been
compile-checked, not run live yet.** Next session: build+run with
`-Dtvivo.debug.fixture=desktop-fixtures\sintel-trailer.mp4` first to confirm
the vlcj migration didn't regress the fixture path, then retest the same real
provider stream that previously hung, and confirm the watchdog fires with the
UI staying responsive (own screenshot evidence, not just "no exception").
Do not close this item until that live pass is done.

**Closed 2026-09-15 -- concurrency/freeze bug fixed and verified; playback
itself moved to D-Desktop-14, not closed here.** A fresh Codex review of
`LibVlcPlayer.kt`/`DesktopShell.kt` (the review this item's Status note above
asked for) found 4 more real races beyond the 4 already fixed, all corrected
and live-verified this session:
- Resume-seek callback could fire `setTime()` on an already-released player if
  Back/close landed between the native "playing" event and the queued seek
  task running (`LibVlcPlayer.kt`, now guarded on `closed.get()`).
- `positionMs()`/`durationMs()` called native status APIs directly outside the
  serial executor, so the 5s polling loop could race a stop/release (now
  wrapped in `runCatching`, returns 0 on failure instead of crashing).
- `close()` unconditionally queued a second native `stop()` even when the
  watchdog/manual Stop had already queued one, serializing two blocking calls
  back-to-back instead of one (now a `stopRequested` flag skips the redundant
  call).
- The playback-launch coroutine could still call `player.playUrl()` after
  Stop/watchdog had already set `terminal = true` while the URL/resume-position
  lookup was in flight (now re-checks `terminal` before calling play).
- Also dropped the fixture path's optimistic "Playing ... fixture" state (set
  before the native `playing` event), which was a watchdog blind spot: if
  fixture playback stalled after `media().play()` returned, the watchdog's
  state-string check wouldn't recognize it as stuck.

**Live verification (2026-09-15):** fixture playback (`sintel-trailer.mp4`)
confirmed working after the vlcj migration. Real provider stream tested 3x:
each time the sequence was `opening -> playing -> buffering (climbing to
100%) -> stuck -> watchdog fires at 20s -> clean stop, actionable message, UI
stays responsive` -- **no freeze, no crash, no double-block**, which is what
this item's fix was actually for. That regression is closed.

**What's still broken (moved to D-Desktop-14, not blocking this item's
close):** the stream never reaches sustained playback -- it stalls after the
initial buffer regardless of tuning (`network-caching` raised 3000ms ->
10000ms, `--clock-jitter=0`/`--clock-synchro=0` tried) -- all reverted after
testing, no benefit. User confirmed the same content plays fine in other apps
on the same connection, ruling out provider/network cause; this points at the
bundled libvlc 3.0.23 itself mishandling this stream's demux/clock behavior.
Combined with a separate, unrelated pushback on maintaining hand-built Compose
transport controls, the decision this session was to replace vlcj with mpv
entirely rather than keep chasing libvlc-version-specific tuning -- see
D-Desktop-14 below.

### D-Desktop-14 — Replace vlcj/libvlc with mpv (libmpv) for desktop playback

**Severity: High. Decided 2026-09-15 after live-stream testing surfaced a
libvlc-specific playback stall that tuning couldn't fix, plus user rejection
of the hand-built control-row approach. Codex edge-case review done
2026-09-15 (folded into this entry below); not yet implemented.**

**Problem this replaces:** `LibVlcPlayer.kt`'s bundled libvlc 3.0.23 reliably
starts a real provider stream, then falls into a `Buffering` state that
climbs to 100% cache and never returns to `Playing` -- reproduced 5x (3x
stock, 2x after raising `network-caching` to 10000ms and disabling
`--clock-jitter`/`--clock-synchro`, both reverted after testing, no benefit).
Confirmed not provider/network-side: the same content plays in other apps on
the same connection. Separately, `DesktopShell.kt`'s `DesktopPlayerScreen`
hand-builds every transport control (Play/Pause, Stop, seek `Slider`, Full
screen, Back) around a raw AWT `Canvas` (`SwingPanel`) video surface -- user
rejected this as ongoing maintenance burden (makes `D-Desktop-13`'s
unfinished seek-bar/skip-controls work moot).

**Action:**
- Replace vlcj/vlcj-natives/JNA LibVLC usage with libmpv via a hand-rolled
  JNA `Library` interface against `mpv/client.h` -- no mature Kotlin/JVM mpv
  wrapper exists, and this repo's only two current JNA consumers
  (`LibVlcPlayer.kt`'s raw `NativeLibrary.addSearchPath`, and
  `auth/WindowsCredentialsStore.kt`'s `jna-platform` `Crypt32Util`) both use
  pre-built wrappers, not hand-rolled `Native.load()` interfaces -- this will
  be the first of that kind in the codebase.
- Bundle a Windows `libmpv-2.dll` runtime the same way `BundledLibVlc`
  extracts `vlc-3.0.23-win64.zip` today (checked-in zip resource under
  `desktop/src/main/resources/libvlc/`, extracted once to
  `%LOCALAPPDATA%\Tvivo\<name>\<version>\` on first run) -- reuse that
  pattern, don't redesign it. Stay Windows-only, matching this module's
  existing scope (`vlc-3.0.23-win64.zip` is win64-only, credentials storage
  is Windows DPAPI-only, no cross-platform Gradle machinery exists here).
- Embed mpv via `--wid` HWND embedding into the existing AWT `Canvas`
  (confirmed technically compatible -- mpv creates a child window under the
  given HWND on Windows). **Timing hazard carried over from the original
  HWND-race bug this session fixed once already:** obtain/configure `wid`
  only after the `Canvas` is actually displayable (Compose's `SwingPanel`
  attaching it is not synchronous with `remember`/`DisposableEffect` running)
  and set it before `mpv_initialize()` -- do not assume today's
  `DisposableEffect(player) { player.initialise(surface) }` ordering is safe
  to port as-is.
- Turn on mpv's built-in OSC so Play/Pause/seek/Full screen are rendered and
  handled by mpv itself; remove `DesktopPlayerScreen`'s hand-built control
  `Row` (Play/Pause `Button`, Stop `OutlinedButton`, seek `Slider`). Keep
  title header, Back button, and resume-position/watchdog logic app-side.
  **Input-ownership conflict (Codex review):** once the embedded mpv child
  has focus, Compose's `onPreviewKeyEvent` (currently handling Escape/F for
  full screen) will not reliably receive those keys -- mpv's OSC already
  binds `f`/Escape to its own fullscreen toggle. Pick one owner: let mpv own
  keys inside the video surface, remove Compose's F/Escape handler, and keep
  Back as a Compose button outside the `Canvas`. Verify mouse clicks on the
  OSC don't swallow clicks meant for Back.
  **Fullscreen-mapping risk (Codex review):** mpv's own OSC fullscreen cycles
  mpv's fullscreen state, which is not guaranteed to promote/demote cleanly
  relative to the surrounding Compose window when mpv is a `--wid` child --
  this is a mandatory fixture test, not an assumption; if unreliable, disable
  OSC's fullscreen control rather than ship a broken button, and drive full
  screen from the app side (ties into `D-Desktop-9`, still open).
- Resume-position tracking is preserved but the mechanism changes: libmpv
  exposes `time-pos`/`duration` properties. A thin JNA binding can either
  poll them every 5s (matching today's `positionMs()` polling model) or use
  `mpv_observe_property()` + a dedicated `mpv_wait_event()` loop (Codex
  recommends this is fine either way). **Keep app-owned Room resume writes as
  the single authority -- do not also enable mpv's own watch-later
  persistence**, which would create a second, conflicting resume source.
- Design the new state machine around mpv's actual events rather than
  porting vlcj's callback shape 1:1 (Codex review): treat
  `MPV_EVENT_FILE_LOADED`, `START_FILE`, `END_FILE`, `SHUTDOWN`, and observed
  `pause`/`time-pos` as the events replacing vlcj's
  opening/buffering/playing/paused/stopped/finished/error adapter. The
  startup/stall watchdog should arm at `loadfile` and only cancel on an
  actual playback-*progress* signal (e.g. `time-pos` advancing), not merely
  `file-loaded` -- and keep a `terminal`/generation-style guard so a stale
  async event can't overwrite a timeout/error message already on screen,
  same contract as today's `terminal` flag in `DesktopShell.kt`.
- **Concurrency (Codex review, carries over this session's hard-won
  lessons):** keep all libmpv API calls, including destruction, on one owner
  thread -- design this fresh rather than porting `LibVlcPlayer`'s
  single-thread executor 1:1, since mpv's event-draining
  (`mpv_wait_event()`)/property-observation model differs from vlcj's
  callback-adapter style. Explicitly design shutdown ordering: never let
  `Canvas` disposal (Back navigation) destroy the mpv instance concurrently
  with an in-flight `mpv_wait_event()` call or property observation --
  this is the same class of bug (`close()` racing an in-flight native call)
  that took 8 rounds of fixes across `D-Desktop-10` to close out for vlcj;
  don't reintroduce it by assuming mpv's API is safe to call from multiple
  threads without the same discipline.
- Update `D-Desktop-13` (seek bar/skip controls) to note it's
  superseded/moot once mpv's OSC supplies seeking natively -- don't build
  both.
- Re-verify `D-Desktop-9` (true full-screen player mode) and
  `docs/design/desktop-player.md` against mpv's embedding model before
  reusing either verbatim: that design doc's "heavyweight-native-surface
  boundary" / "Compose chrome as sibling dock" model was written assuming
  hand-built Compose controls remain, and becomes largely moot for playback
  controls once mpv's OSC draws directly onto the video surface. The doc
  also predates the vlcj migration and needs a pass regardless.
- First validation step for the next session, mirroring this session's
  approach: get `desktop-fixtures/sintel-trailer.mp4` playing through
  mpv/JNA embedded in the Compose window (with OSC visible/functional)
  before attempting the real provider stream that stalled vlcj 5x.

**Files most relevant (read fully before starting):**
`desktop/src/main/kotlin/com/dev/tvivo/desktop/LibVlcPlayer.kt` (player
wrapper being replaced -- keep as the resume/watchdog/terminal-flag contract
reference), `desktop/src/main/kotlin/com/dev/tvivo/desktop/DesktopShell.kt`
(`DesktopPlayerScreen` -- surface, watchdog, resume-poller, control `Row` to
remove), `desktop/build.gradle.kts` (vlcj/JNA dependency declarations +
`vlc-3.0.23-win64.zip` resource packaging pattern to mirror for mpv),
`docs/design/desktop-player.md` (full-screen design spec, needs
re-validation).

**What NOT to do:** don't keep vlcj installed as a fallback/feature flag --
this is a full replacement; don't hand-build Compose controls again on top of
mpv "to match the app's look" unless explicitly asked later; don't
scope-creep into implementing `D-Desktop-9`/`D-Desktop-13` this pass beyond
the re-validation notes above.

**Verification (next session):** (1) fixture playback through mpv with OSC
visible/functional; (2) real provider stream retest -- confirm sustained
playback, not just a brief `Playing` before falling back to buffering; (3)
repeat this session's close-during-in-flight-operation checks against mpv's
actual async/blocking call surface; (4) Codex adversarial review of the new
player wrapper's threading model before calling it done, per
`bible-detail/claude-07-agent-division.md`'s loop -- same discipline that
caught 4 real races in the vlcj version (and would have caught the HWND/OSC
timing hazards above before they became bugs) should apply to the mpv
rewrite too.

### D-Desktop-11 — Sign-in button re-enables mid-check

**Severity: Medium. Reported 2026-09-14, manual desktop run.**

**Observed:** After clicking Sign in, the UI shows an account-checking state,
but the Sign in button becomes clickable again before that check finishes.
This reads as ready-for-input while a request is still in flight and invites
a duplicate submit or a confused re-click/re-entry.

**Action:** Disable the Sign in button (and ideally show a spinner/label
change) for the full duration of the account-check request; only re-enable
it on failure, and route to Home directly on success without a re-enabled
intermediate state.

### D-Desktop-12 — Desktop UI needs a real design pass

**Severity: Medium. Noted 2026-09-14, manual desktop run.**

**Observed:** The desktop app (sign-in and beyond) is still functional-only
layout with no visual design system applied yet -- default spacing/typography,
no polish.

**Action:** Run a design pass (`/design-consultation` or equivalent) against
the desktop journey once functional regressions (D-Desktop-7..11) are closed;
track as its own follow-up rather than folding into bug fixes.

### D-Desktop-13 — Player transport controls are incomplete and the seek bar doesn't track playback

**Severity: High. Reported 2026-09-14, manual desktop run (live D-Desktop-10 verification).**

**Observed:** In `DesktopShell.kt`'s `DesktopPlayerScreen`, the seek `Slider`
is only ever written by the user's own drag (`var seek by remember { ... }`)
-- nothing reads `player.positionMs()`/`durationMs()` back into it, so it
never reflects actual playback progress. The control row also only has
Pause/Stop/seek/Full screen/Back; there's no native-style transport (skip
back 10s, skip forward 10s) that users expect from a video player.

**Action:** Poll `player.positionMs()`/`durationMs()` on a timer while
`playerReady` and not user-dragging, and drive the slider from it (the
existing 5s resume-save `LaunchedEffect` polling loop is a reasonable model
to extend or pair with). Add explicit skip -10s/+10s buttons wired to
`player.seek()`/`positionMs()`, alongside the existing Play/Pause/Stop/Full
screen/Back.
