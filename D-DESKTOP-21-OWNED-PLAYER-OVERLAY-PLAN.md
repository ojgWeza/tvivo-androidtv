# D-Desktop-21 — Tvivo-owned Player Overlay Plan

**Plan filename convention:** `D-DESKTOP-<number>-<CLEAR-OUTCOME>-PLAN.md`. Use the user-visible outcome, not a vague batch label or an implementation detail, in every new plan filename.

**Status:** Planned. No implementation or build approved.

## Decision

libmpv remains the decoder, A/V synchroniser, and stream engine. Its OSC is disabled.

Tvivo owns every visible playback control in Compose and those controls visually overlay the video: Play/Pause, 10-second back/forward, Back, Next episode when available, Full screen, timeline, volume, and later track selection. This is a product requirement, not a fallback preference.

The current embedded AWT Canvas is a heavyweight native child, so Compose cannot safely overlay it. D-Desktop-21 therefore starts with a libmpv render-API feasibility spike. The player must render through a Compose-compatible graphics surface before the final controller is built.

## Root cause of the existing fullscreen failure

`Main.kt` already creates an undecorated `WindowPlacement.Fullscreen` application window. The previous player button instead changes libmpv's `fullscreen` property. With `wid` embedding, libmpv cannot resize the Compose parent window. It can only signal a property change, which makes the UI hide its header without providing a reliable app-owned fullscreen transition. The existing fullscreen Back control also overlays the heavyweight `SwingPanel`, an arrangement documented as unreliable on Windows.

**Resolution:** remove mpv `fullscreen` as a UI state and make `cinemaMode` a Compose-owned state. The product label remains **Full screen** if preferred; the code uses `cinemaMode` to avoid implying that mpv owns the window. The render API replaces the `wid` Canvas before Compose overlay controls ship.

## Scope

### 1. Render-API feasibility spike (hard gate)

Before changing the player UI, create a narrow local-fixture prototype using libmpv's render API rather than its `wid` option:

- Keep the existing owner-thread lifecycle, loading, watchdog, position, pause, duration, and error event path intact.
- Bind only the render API surface required to display one local `.mp4` fixture through the Compose/Skia rendering path.
- Prove that Compose can render an interactive button above the video, receive its click, and redraw without video corruption, input loss, or a resource leak.
- Exercise resize, entering/exiting `cinemaMode`, player disposal, and repeat playback creation with the same fixture.
- Record frame timing, CPU, working set, and resize behavior against the existing `wid` implementation. The new path may not ship if it visibly regresses playback smoothness or native-resource stability.

**Gate:** Do not remove the existing `wid` player or begin the production controller until the prototype passes all of the above. If it fails, stop and present the evidence and alternatives to the user; do not silently revert to sibling bands as the final design.

### 2. Remove OSC ownership and forwarding

In `desktop/src/main/kotlin/com/dev/tvivo/desktop/MpvPlayer.kt`:

- Initialise mpv with `osc=no`.
- Remove the `fullscreen` property observation/callback and `setFullScreen()`.
- Remove OSC-only OSD scaling, synthetic mouse movement/buttons, wheel forwarding, held-key tracking, OSC visibility messages, and the associated Canvas listeners.
- Do not forward arbitrary keyboard events to mpv. Tvivo owns keyboard behavior. Keep only narrowly required app-controlled mpv commands.
- Retain the one-owner-thread rule for every libmpv client API call.

### 3. Expose a small playback-control API

Add owner-thread methods and callbacks required by the Compose controller:

- `togglePause()` / `setPaused(Boolean)` backed by mpv's `pause` property.
- `seekRelative(seconds)` and `seekAbsolute(positionMs)`, rejecting a seek when no finite duration is available.
- `stop()` and existing load/release behavior.
- Duration callback from the already-observed `duration` property, alongside the existing position and pause callbacks.
- Explicit state for `isSeekable`: false for a missing/zero/non-finite duration, including live streams.

No provider stream is opened by automated tests. Local fixtures are the only playback verification source.

### 4. Build the Compose overlay controller

In `DesktopShell.kt`, replace the current header-only player UI with:

- A compact top overlay: title, concise state, Back, and Full screen.
- A bottom overlay dock: Play/Pause, 10-second back/forward, elapsed time, a seek slider when seekable, duration, Stop, and **Next episode** only when the current route has a verified next episode. Live/non-seekable playback shows no misleading timeline.
- A dark bottom gradient/opaque dock treatment that keeps controls legible over any video while retaining a picture-first feel.
- Semantic labels, tooltips, visible keyboard focus, and disabled controls for unavailable actions.
- Keyboard behavior owned by the app: Space/K play-pause, Left/Right seek 5 seconds, Shift+Left/Right seek 30 seconds, S stop, F toggles cinema mode, and Esc exits cinema mode before any navigation Back action.
- Pointer activity on the Canvas only wakes the app controls. It is never forwarded to mpv.

### 5. Implement reliable cinema mode

`cinemaMode` is held in `DesktopShell`, not in mpv.

- **Windowed:** video fills the player viewport; compact top and bottom Compose layers draw above it.
- **Cinema controls visible:** video fills the entire app window; top and bottom Compose overlay layers draw above it without resizing/reloading the video surface.
- **Cinema controls hidden:** video alone remains visible in the full app window.
- Entering cinema mode reveals controls for 2.5 seconds. Pointer activity, keyboard input, or focus in the dock resets the timer. Hiding fades the Compose overlay from 100% to 0% alpha over 120 ms above the proven render surface; it never resizes, reloads, or restarts video.
- Preserve the same render surface and `MpvPlayer` instance throughout, so a transition never reloads or restarts playback.
- If the local-fixture test shows a flash, lost frame, playback interruption, or lost input during overlay visibility changes, stop at the render-API gate and record the evidence. Do not ship an unreliable approximation.

### 6. Keep lifecycle and resume behavior intact

- Existing watchdog, generation filtering, local-fixture loading, and five-second non-live resume writes remain intact.
- Back still saves non-live position and disposes the player. Entering/exiting cinema mode must not save, stop, reload, or recreate the player.
- The player title remains set through `force-media-title` for diagnostic/player metadata, but it is not presented through mpv OSC.

## Files expected to change

- `desktop/src/main/kotlin/com/dev/tvivo/desktop/MpvPlayer.kt`
- `desktop/src/main/kotlin/com/dev/tvivo/desktop/MpvLibrary.kt` — render-API bindings, scoped only to the verified API surface
- `desktop/src/main/kotlin/com/dev/tvivo/desktop/DesktopShell.kt`
- `desktop/src/test/kotlin/com/dev/tvivo/desktop/` — focused state/API tests
- `docs/design/desktop-player.md` — replace stale LibVLC references and record the final mpv heavyweight-surface behavior
- `TODO.md` and `docs/decisions.md` only when the item is actually closed

## Delivery chunks

Each chunk is independently reviewable. Do not begin the next chunk until its success criteria have observable local-fixture evidence. A build/run requires the user's explicit approval at the point it is needed.

### Chunk 0 — Baseline and render-API contract

**Purpose:** establish a measurable baseline and isolate the minimum native API surface before changing production playback.

**Workload:**

- Record the exact current local fixture(s), window size, playback state, CPU, working set, and resize behavior of the `wid` player.
- Identify the libmpv render API functions, render parameters, OpenGL/Skia interop requirements, callback/thread rules, and destruction order required for Windows x64 only.
- Add no production UI. If bindings are introduced, place them behind a new internal prototype boundary rather than altering `MpvPlayer`'s production path.
- Write the prototype's thread-ownership contract: Compose render thread, mpv owner/event thread, and any graphics-context thread must never call libmpv concurrently unless the render API explicitly permits it.
- Define exact comparison thresholds before implementation: no persistent CPU increase above the baseline by more than 10%, no continually rising working set over five create/play/dispose cycles, and no dropped/black frame after a resize.

**Success criteria:**

- The required native symbols, parameter structs, ownership, and teardown order are documented in the plan or implementation comments from primary libmpv documentation.
- The baseline is recorded for one seekable fixture and one live-style/non-seekable fixture, without opening a provider stream.
- The prototype has a bounded file/API scope and does not replace `wid` yet.

**Stop conditions:** missing render API support in the bundled libmpv, uncertain graphics-context ownership, or no viable Compose/Skia interop route. Stop and report rather than improvising a renderer.

### Chunk 1 — Render-API prototype

**Purpose:** prove that real Compose overlay content can sit over video reliably.

**Workload:**

- Create an internal, fixture-only prototype route or flag that renders one local `.mp4` through libmpv's render API into a Compose-compatible surface.
- Add one Compose test button in the upper-right corner and a temporary state label. The button must change visible Compose state when clicked.
- Keep the existing production `wid` route untouched and selectable until this spike is accepted.
- Implement deterministic creation/disposal of the render context and graphics resources; close in reverse creation order.
- Test normal resize, rapid resize, fixture reload, route exit, and five repeat player lifecycles.

**Success criteria:**

- Video is visible and animated, and the Compose button is visibly above it and receives clicks.
- The prototype has no black/video-corrupted frame after normal or rapid resize.
- Five fixture play/dispose cycles complete without a leaked native player/render context and without sustained working-set growth.
- CPU and working set meet Chunk 0 thresholds, and playback remains smooth by visual inspection.

**Decision gate:** only after all criteria pass may the production player move off `wid`. If any criterion fails, preserve the current player, attach evidence, and ask the user whether to investigate further or accept a different player-engine/UI direction.

### Chunk 2 — Production render-surface migration

**Purpose:** replace the heavyweight Canvas only after the prototype is proven.

**Workload:**

- Extract the proven render surface into a production internal component with a narrow lifecycle API.
- Move existing `MpvPlayer` initialisation from `wid` to render API without changing stream URL construction, watchdog logic, generation filtering, failure copy, resume behavior, or disposal guarantees.
- Delete `wid`/Canvas-only code only after the production path is verified; do not maintain two live player implementations indefinitely.
- Update `MpvLibrary.kt` with only the required bindings and ABI-accurate struct definitions.
- Add focused tests for lifecycle order and generation isolation; retain fixture-only manual validation for actual pixels.

**Success criteria:**

- Movie, series-episode, and live route setup reach the production render surface using local fixtures where applicable.
- A local fixture still starts, pauses, resumes, ends, times out, and disposes with the same user-visible states as before.
- Back saves a non-live resume position once and releases player resources once.
- No AWT `Canvas`, `SwingPanel`, OSC mouse bridge, or `wid` option remains in the production player path.

### Chunk 3 — Remove OSC and define the app command surface

**Purpose:** make one system, Tvivo, own every visible control and input action.

**Workload:**

- Set `osc=no` before libmpv initialisation.
- Remove mpv fullscreen observation, synthetic mouse/wheel/button injection, OSD coordinate scaling, OSC-visibility script messages, held-key cleanup that only served forwarded mpv input, and arbitrary key forwarding.
- Add owner-thread methods: `setPaused`, `togglePause`, `seekRelative`, `seekAbsolute`, `stop`, and any read-only state callbacks needed by Compose.
- Publish position, duration, pause, ended/error, and seekability to a small UI state model. Treat missing, zero, NaN, or infinite duration as non-seekable.
- Preserve the current generation guard so a late callback from an old stream cannot overwrite new player state.

**Success criteria:**

- mpv OSC never appears for `.mp4`, `.mkv`, or `.ts` local fixtures.
- Canvas/surface mouse movement, wheel activity, clicks, and keyboard input no longer activate an mpv control.
- Each app command executes only on the mpv owner thread and a rejected seek cannot change playback state.
- Existing watchdog and resume tests still pass, and new command/state tests cover pause and seek edge cases.

### Chunk 4 — Windowed Tvivo overlay controller

**Purpose:** ship the basic branded controls over the proven video surface before adding cinema-mode behavior.

**Workload:**

- Create `PlayerControls` Compose components with a compact top overlay (title, state, Back, Full screen) and a bottom gradient/dock overlay.
- Add Play/Pause, Stop, 10-second back, 10-second forward, elapsed time, duration, and a seek slider only when seekable.
- Keep disabled controls visible but non-actionable with clear semantics. For live/non-seekable media, omit the false timeline and retain only applicable controls.
- Add keyboard handling at the Compose/window layer: Space/K, Left/Right, Shift+Left/Right, S, F, and Esc.
- Ensure pointer activity over video wakes controls without forwarding the event to mpv.
- Give every control an accessible name, tooltip, visible focus treatment, and a target size appropriate for mouse use.

**Success criteria:**

- Every listed control is visibly above the video, clickable, keyboard reachable, and styled as Tvivo rather than mpv.
- Position/duration displayed by the dock follows observed player state, and dragging/clicking the seek slider yields the requested position within an agreed small tolerance.
- Paused, buffering, ended, error, live, and non-seekable states show truthful controls and copy.
- Back returns to Detail/Episodes and releases playback; it never reveals an mpv OSC.

### Chunk 5 — Cinema mode and fullscreen reliability

**Purpose:** solve the previously failing fullscreen behavior with app-owned state.

**Workload:**

- Replace `playerFullScreen`/mpv fullscreen coupling with `cinemaMode` controlled solely by Compose.
- Let F or the Full screen button enter cinema mode; let Esc, F, and the same control return to windowed player mode.
- In cinema mode, keep video full-window and layer the existing Compose overlays above it. Do not reload the item, recreate the render surface, change route, or ask mpv to manage a window.
- Implement 2.5-second inactivity reveal and 120 ms alpha fade. Pointer motion, key input, or focus within controls restarts the timer.
- Prevent auto-hide while keyboard focus is in a control or while a slider is being dragged.
- Add a fixture-only diagnostic event for enter/exit and surface creation count.

**Success criteria:**

- Repeated F/Esc transitions preserve the same playback generation and position continues advancing.
- Video fills the app window while controls are both visible and hidden.
- Controls reliably reappear after pointer motion or a supported key and remain clickable above video.
- Ten repeated enter/exit cycles produce no black frame, route change, duplicate player, duplicate resume write, or native-resource leak.

### Chunk 6 — Series next-episode behavior

**Purpose:** add Next episode only when it is truthful and predictable.

**Workload:**

- Define and test the episode ordering contract: current season then episode number, followed by the first episode of the next available season.
- Resolve a next episode from the already loaded series episode data; do not issue a surprise provider request from a control click.
- Show Next episode only for a series episode with a resolvable successor. It must not appear for a movie, live channel, or final known episode.
- On activation, save the current episode's position according to existing rules, load the successor as a new generation, reset the control timeline/state, and keep cinema mode as-is.

**Success criteria:**

- Unit tests cover same-season next, cross-season next, final episode, missing/ambiguous episode numbers, and non-series content.
- The button is absent when no successor exists and invokes exactly one new playback generation when it does.
- Manual local-fixture validation confirms title, timeline, and Back destination reflect the new episode.

### Chunk 7 — Regression, evidence, and documentation

**Purpose:** validate the complete user experience and leave an accurate maintenance record.

**Workload:**

- Run unit tests for player state, command ownership, next-episode ordering, watchdog, and resume behavior after explicit build approval.
- Run only local-fixture manual verification. Capture settled evidence once for: windowed playing, paused/seekable, live-style/non-seekable, cinema controls visible, cinema controls hidden, return from cinema, next episode, and Back disposal.
- Measure CPU/working set against Chunk 0 after 60 seconds playing and five enter/exit cycles.
- Update `docs/design/desktop-player.md` to remove stale LibVLC references and document the actual render/overlay boundary.
- Update the relevant TODO/archive and `docs/decisions.md` only after acceptance criteria are met.

**Success criteria:**

- All earlier chunk criteria have evidence, no provider stream was opened, and no unreviewed fallback behavior remains.
- Measured performance meets the predeclared threshold or has an explicit, user-approved exception.
- Documentation names libmpv accurately and explains the final ownership/lifecycle model.

## Acceptance criteria and evidence

1. The render-API prototype proves a Compose button is visibly and interactively above local-fixture video without corruption, native-resource leak, or a sustained performance regression.
2. mpv OSC is absent for a local `.mp4`, `.mkv`, and `.ts` fixture. No mpv native control reacts to video-surface clicks, mouse movement, wheel input, or keyboard forwarding.
3. Tvivo's overlay controls play/pause, stop, relative seek, and absolute seek correctly for a seekable local fixture; the position and duration display agree with observed mpv state.
4. A non-seekable/local-live-style fixture displays no fake timeline and disables seek commands.
5. F enters cinema mode; Esc and F return to windowed controls without stopping/reloading playback. Video fills the app window in cinema mode, including while controls are visible.
6. Overlay controls remain visibly above video and receive pointer/keyboard input in both windowed and cinema modes.
7. Repeated cinema-mode transitions preserve the same playback generation, continue advancing position, and produce no duplicate resume write or leaked player instance.
8. Back from either mode returns to Detail/Episodes and releases native player resources. Next episode appears only for a series item with a known next episode and does not appear for movies/live.
9. Capture one settled screenshot per: windowed playing, cinema controls visible, cinema controls hidden, and returned windowed playback. Do not open a provider stream.

## Implementation order

Execute Chunks 0 through 7 in order. Chunk 1 is a hard stop gate; Chunk 2 must not start until its render prototype passes.

## Explicit non-goals

- No player-engine migration away from libmpv.
- No provider-stream playback in test automation.
- No fallback shipping path that pretends sibling bands are a Compose overlay.
- No audio/subtitle selector in this batch; those require a separate track-discovery/control design.
