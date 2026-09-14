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
