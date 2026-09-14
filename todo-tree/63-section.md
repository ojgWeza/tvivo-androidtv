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
