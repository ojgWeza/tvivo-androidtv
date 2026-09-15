## P-1..P-6 — Player enhancement plan — **APPROVED FOR PLANNING 2026-09-13**

Global search is declined. The next product work is a player-only batch, using
the interaction model observed in an external IPTV-player screenshot as
inspiration only; do not copy its branding, artwork, icons, or layout verbatim.

**Scope boundary:** Android TV is the primary target. Keep Media3 `PlayerView`,
direct progressive playback, the one-account-slot constraint, redacted
Diagnostics, and the existing Room resume contract. No automated test may open
a stream. Do not begin an episode-level Continue Watching feature before P-1
has proved resume persistence on hardware.

### P-1 — Establish a real-device player baseline
**Cost: validation. Blocks P-2 through P-6.**

On an approved physical-TV session, manually validate and record:

- movie, live `.ts`, and `/series/` episode startup;
- visible exit/back, 10-second D-pad seek for seekable content, and no seek
  controls for live;
- exit from a movie followed immediately by a live launch, proving the old
  player releases its connection;
- buffering, first-frame, unavailable-stream, timeout, and probable
  connection-limit error actions;
- `Player.getCurrentTracks()` language/label metadata for Q-5 before changing
  audio labels; and
- resume persistence after Back, Home/process stop, sleep/wake, and completion.

**Done when:** the report identifies any real reliability defect before UI work
depends on the current player, and confirms that no diagnostic/UI output exposes
a playback URL or credentials.

### P-2 — Replace the player hint with a remote-first control surface
**Cost: medium. Depends on P-1.**

In `ui/player/PlayerActivity.kt`, replace the temporary `Back to exit` hint
with a controller overlay that is shown by OK, auto-hides during playback, and
contains:

- top-left Back action and current title; series titles include season/episode;
- a Start over action only when a resume point exists;
- play/pause, elapsed time, progress, and total duration for seekable content;
- seek feedback (`+10 seconds` / `-10 seconds`) on D-pad seek; and
- a compact action row: Episodes (series only), Audio/Subtitles (only when
  tracks exist), Speed (seekable VOD/episodes only), Fit, and Next episode
  (only when a valid next episode exists).

**Focus contract:** on opening controls, focus Play/Pause; Back closes an open
sub-overlay before returning to browse; all controls have a deterministic
D-pad order and a visible `tvFocusFrame`; live excludes seek, Start over, speed,
Episodes, and next/previous.

**Done when:** instrumented focus tests cover opening/closing the overlay and
Back behavior, and manual TV QA confirms the controls are discoverable without
obscuring normal viewing.

### P-3 — Add series in-player navigation
**Cost: medium. Depends on P-1 and P-2.**

Pass a minimal, non-secret episode context to the player: show id, season,
ordered episode ids, current index, and display metadata. Add:

- Previous/Next controls, disabled at a boundary unless cross-season traversal
  is separately approved;
- an Episodes action that opens a dismissible overlay from cached episodes;
- return of D-pad focus to Episodes when that overlay closes; and
- a safe episode switch: save the current position, stop and release the old
  player, then build the next episode URL and player.

**Done when:** unit tests cover boundaries and ordered-episode resolution;
instrumented tests cover overlay focus restoration; manual TV QA proves no
second active connection is left behind after switching episodes.

### P-4 — Add track, presentation, and completion behavior deliberately
**Cost: medium. Depends on P-1 and P-2.**

- Build Audio and Subtitles menus from Media3's exposed tracks; show `Unknown`
  when metadata is absent or unreliable, never invent a language label.
- Add Speed values `0.75x`, `1x`, `1.25x`, `1.5x`, and `2x`; reset to `1x` for
  each new item unless a later explicit preference decision says otherwise.
- Add Fit / Fill / Original presentation modes, defaulting to Fit so captions
  and Arabic text are not cropped.
- At completion, clear the resume row and show a short, cancelable Play-next
  countdown only for series. Do not auto-play the next episode without an
  explicit owner decision.

**Done when:** tests cover track-menu visibility, mode defaults, completion
cleanup, and next-episode eligibility; hardware QA confirms the selected track
and presentation mode visibly take effect.

### P-5 — Harden lifecycle and failure recovery
**Cost: medium. Depends on P-1.**

Handle screen sleep/wake, surface recreation, backgrounding, and decoder
failure without a crash, duplicate player, or leaked connection. Preserve the
last valid position, pause rather than force-exit when the surface is lost, and
record redacted lifecycle/decoder/track-selection events in `DiagnosticLog`.

**Done when:** lifecycle tests prove release is idempotent, resume writes are
preserved, and an approved physical-TV run covers sleep/wake and immediate
re-entry to playback.

### P-6 — Handset player presentation
**Cost: medium. Deferred until P-1 through P-5 are stable.**

Use usable window bounds and insets for true fullscreen, define portrait and
landscape behavior separately, and make every transport control touch-accessible
without changing the TV D-pad contract. Verify rotation, sleep prevention,
Back, track menus, and Episodes overlay on a handset.

**Done when:** handset QA shows a full usable video surface and no control is
clipped, unreachable, or left focused after a rotation/IME transition.
