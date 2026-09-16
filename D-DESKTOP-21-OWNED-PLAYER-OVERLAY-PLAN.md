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

**Purpose:** establish a testable, Windows-x64-only render contract before any production player/UI change. Chunk 0 is documentation, inspection, bindings-only work, and fixture preparation; it must not alter `MpvPlayer`'s `wid` path, disable OSC, or add an overlay.

#### 0.1 Decision and non-negotiable integration shape

`Full screen` is **cinema mode**, not an OS window-state transition and not the mpv `fullscreen` property. `Main.kt` already owns one undecorated `WindowPlacement.Fullscreen` window. Chunk 5 changes only the composition inside that existing window: normal player chrome versus picture-first chrome-hiding. It must not call `WindowState.placement`, create/reparent a native window, change display mode, or ask mpv to create/manage a fullscreen window. If a future product requirement needs exclusive/fullscreen window-state behavior, it is a separately planned feature.

The only acceptable render-API route is one OpenGL context that is both current and owned by the actual Skiko layer which Compose Desktop uses to paint this `ComposeWindow`. mpv renders first into an off-screen OpenGL FBO owned by the prototype; Skia then draws that texture as the video background in the same Skiko frame; ordinary Compose draws the controller above it. There is no `SwingPanel`, `Canvas`, child HWND, second GL window, readback to CPU, or attempt to place Compose above a heavyweight component.

Compose Desktop/Skiko does not promise a general stable public API for injecting arbitrary OpenGL into the compositor. Therefore the spike must pin and inspect the exact Compose/Skiko versions resolved by this project and prove one of these equivalent mechanisms **before coding the player migration**:

1. a supported Skiko render-delegate/frame hook on the existing `ComposeWindow` `SkiaLayer` which runs while its GL context is current and before the Compose scene is painted; or
2. a version-pinned, isolated adapter around the exact Skiko API used by the resolved runtime that obtains the current `DirectContext`/GL context and performs the ordered frame below.

The adapter is prototype-only, has a compile-time version guard/documented dependency, and may not use reflection into Skiko internals. A second shared WGL context is rejected: mpv requires the same current GL context for a render context, and sharing textures would add unproven synchronization/lifecycle behavior. If the resolved runtime is not OpenGL-backed, lacks a callable before-Compose hook, or cannot wrap/use the FBO texture in Skia without CPU copying, Chunk 0 stops. The team must report that no safe Compose Desktop/Skiko OpenGL path was proven; it must not substitute software rendering, a heavyweight sibling, or an undocumented private-API hack.

Per frame, the adapter must bind a private RGBA8 FBO sized in **physical pixels** (`round(logicalSize * density)`), call mpv, restore the GL state it changes, wrap/draw the FBO texture with Skia, then let Compose paint chrome. The initial acceptance format is SDR 8-bit sRGB only. The FBO is recreated only on a committed physical-size change, never on every pointer/control recomposition.

#### 0.2 Thread, callback, and lifecycle contract

There are exactly two libmpv lanes. No lane waits synchronously for the other.

```text
Compose/Skiko render thread (the current GL context)       MpvPlayer owner/event thread
-----------------------------------------------------       ----------------------------
create GL FBO + mpv_render_context_create()  <--- async ---- create/init mpv core; commands;
  (only while Skiko GL context is current)                 mpv_wait_event/property callbacks
set update callback; retain callback objects                never call mpv_render_*
        |                                                               |
        | update callback: atomic pendingRender=true;                  |
        | enqueue/request Skiko repaint only; NO mpv/GL call            |
        v                                                               |
next Skiko frame: update(); if FRAME render(FBO);                       |
draw FBO texture with Skia; compose overlay; present                    |
report_swap() after that frame is presented ----------------------------+
```

- The **owner/event thread** exclusively owns `mpv_create`, options, `mpv_initialize`, load/stop/seek/pause/property calls, event draining, and final `mpv_terminate_destroy`. It publishes immutable UI state and never makes a GL context current or invokes `mpv_render_*`.
- The **Skiko render thread** exclusively owns `mpv_render_context_create`, `mpv_render_context_update`, `mpv_render_context_render`, `mpv_render_context_report_swap`, callback installation/removal, and `mpv_render_context_free`, always with that same Skiko OpenGL context current. The creation thread must also be recorded; the bundled DLL must report an API new enough for cross-thread rendering only if that is ever contemplated. This design deliberately uses one render thread throughout.
- `MPV_RENDER_PARAM_ADVANCED_CONTROL=1` is required. The render thread must never wait for the mpv core; owner-thread commands that might otherwise block must use the existing asynchronous command path. The update callback must be installed immediately after create and each callback is acknowledged by one later `update()`; multiple callbacks may coalesce into one frame.
- The native update callback may arrive on an arbitrary mpv thread. It performs only `pendingRender.compareAndSet(false, true)` plus a thread-safe Skiko repaint request. It does not call `update`, `render`, any other mpv API, GL, Compose state, or throw through JNA. Callback references and their context pointer remain strongly retained until unregistration and render-context free complete.
- Frame scheduling is edge-coalesced: callback -> at most one queued repaint; render thread clears/loops the atomic around `update()` so a callback racing `update()` schedules another frame. Render only when `update() & MPV_RENDER_UPDATE_FRAME != 0`, except a resize/restore invalidation explicitly requests redraw. Call `report_swap()` after the Skiko frame containing the mpv frame is presented, with `mpv_get_time_us()` sampled on the owner thread or a documented equivalent; failure to establish a trustworthy presentation point means omit `report_swap` and record it, rather than inventing timestamps.

Teardown is a barrier, not best effort:

```text
UI disposal -> reject new commands/repaint requests -> owner stops/load generation invalidated
 -> render thread: make Skiko GL current -> unregister callback (null) -> drain/inhibit queued repaint
 -> mpv_render_context_free(renderCtx) -> delete FBO/Skia texture while GL is current
 -> acknowledge render teardown -> owner thread mpv_terminate_destroy(handle) -> join/clear references
```

`mpv_render_context_free` must happen before destruction of its mpv core; destroying the GL context/FBO first is forbidden. UI disposal waits only for the bounded render-teardown acknowledgement; timeout is a failed lifecycle diagnostic, not permission to destroy mpv anyway. Context loss follows the same order: stop rendering, free the old render context while the old context is current if possible; otherwise fail the prototype safely and destroy neither core nor resources out of order. No render call is permitted after free or during a rapid load/close generation change.

#### 0.3 Required `MpvLibrary` ABI surface and DLL validation

Add only a prototype-scoped `MpvRenderLibrary` portion of `MpvLibrary.kt`. It must bind these exported functions with JNA's default Windows x64 C calling convention:

- `mpv_render_context_create(PointerByReference, Pointer mpv, MpvRenderParam[] params): Int`
- `mpv_render_context_render(Pointer, MpvRenderParam[] params): Int`
- `mpv_render_context_update(Pointer): Long` (unsigned 64-bit bitset represented as `Long`)
- `mpv_render_context_report_swap(Pointer)` and `mpv_render_context_free(Pointer)`
- `mpv_render_context_set_update_callback(Pointer, MpvRenderUpdateCallback?, Pointer?)`
- `mpv_get_time_us(Pointer): Long`, only if presentation timing is implemented.

Required callback types are retained Kotlin/JNA objects: `MpvRenderUpdateCallback : Callback` (`void invoke(Pointer)`), and `MpvOpenGlGetProcAddressCallback : Callback` (`Pointer invoke(Pointer ctx, String name)`). The GL callback is passed through `MpvOpenGlInitParams`; it resolves every requested symbol from the **current Skiko/WGL context**, using `wglGetProcAddress` with documented `GetProcAddress(opengl32.dll)` fallback for WGL sentinel returns. It must not return a pointer from a temporary library handle.

ABI structures use `@Structure.FieldOrder` and are allocated/read/written before native calls:

| C declaration | JNA layout on Windows x64 |
| --- | --- |
| `mpv_render_param { int type; void *data; }` | `Int type`, `Pointer data` (16 bytes including x64 padding) |
| `mpv_opengl_init_params { get_proc_address; void *get_proc_address_ctx; }` | callback pointer, `Pointer` (16 bytes) |
| `mpv_opengl_fbo { int fbo; int w; int h; int internal_format; }` | four `Int`s (16 bytes) |
| parameter arrays | native contiguous array, terminated by `{ MPV_RENDER_PARAM_INVALID, null }` |

Use `MPV_RENDER_API_TYPE_OPENGL` (`"opengl"`), `MPV_RENDER_PARAM_API_TYPE=1`, `OPENGL_INIT_PARAMS=2`, `OPENGL_FBO=3`, `FLIP_Y=4`, `DEPTH=5`, `ADVANCED_CONTROL=10`, and `MPV_RENDER_UPDATE_FRAME=1`. The create parameter array contains API type, GL init params, advanced-control `int(1)`, terminator. Render parameter array contains current FBO, `flip_y=1` for the GL FBO convention, depth `8`, terminator. Keep all `Memory`, structure, callback, string, and parameter-array objects strongly reachable for the native call/lifetime they serve; specifically GL-init callback/context live through render-context free.

Before the prototype is allowed to use a pixel:

1. Load the bundled `libmpv-2.dll` only; call `mpv_client_api_version`, decode major/minor, and record it plus DLL SHA-256 and package source. Fail if it is below the documented render-API baseline or lacks the required exports (`NativeLibrary.getInstance(...).getFunction(...)`), rather than relying on a development DLL.
2. Compare field offsets/sizes (`Structure.size()`) with a tiny header-derived Windows-x64 C ABI probe built against the **bundled archive's headers**, or a checked-in generated expected-layout table whose header version/SHA is recorded. Do not infer layout from Kotlin/JNA behavior.
3. Create/free one render context against a current throwaway Skiko GL frame with no media, then run the fixture matrix. Log every negative mpv error with `mpv_error_string` and preserve the version/layout result as Chunk-0 evidence.

#### 0.4 Fixture and baseline contract

No provider URL, credential, or live account is used. Baseline `wid` and prototype observations use the same machine, build, display, power mode, 1920x1080 logical window (and one 150%-DPI monitor pass), audio setting, and a 60-second steady-play period after a 10-second settle. Record private bytes, working set, process CPU averaged over 60 seconds, first frame time, frame/black-frame observations, and five create/play/dispose cycles; keep raw measurement command/output as an artifact and summarize only values in the plan/evidence.

| Fixture | Required purpose | Source/status |
| --- | --- | --- |
| `sintel-trailer-12s-subtitles.mkv` (751,857 bytes; SHA-256 `5b9d9af10aaaf19ddfbb18fdbf7ae07f52a91112ed20dc0b630e58df0681494a`; 12.031000 s) | pause, resume, absolute/relative seek, subtitle compositing | H.264 High, 854×480, AAC-LC stereo, embedded SubRip/English fixture caption. Derived from the 480p Sintel trailer at `http://download.blender.org/durian/trailer/sintel_trailer-480p.mp4`. Durian Open Movie Project, CC BY 3.0; attribution `© copyright Blender Foundation | durian.blender.org`. See `desktop/src/test/resources/fixtures/README.md`. |
| `sintel-trailer-12s.ts` (893,940 bytes; SHA-256 `c4cac9daf488e9d4a754cde6eb9a0547ae82247c06c43576be944ca40cb9f2f9`; 12.010667 s) | direct-file TS demux/decode, seeking where supported | H.264 High, 854×480, AAC-LC stereo. Same Sintel source/license/attribution and derivation README. This TS fixture is seekable and is not evidence of live/non-seekable behavior. |
| Live-style, non-seekable input | absent/indefinite duration, rejected seek, no false timeline, stop/error behavior | Fixture-only `:desktop:fixtureLiveRelay` serves `sintel-trailer-12s.ts` on loopback at `http://127.0.0.1:8765/fixture`, omits `Content-Length`, rejects `Range`, and repeats bytes slowly. Start manually after approval with `./gradlew :desktop:fixtureLiveRelay`; stop with Ctrl+C. No provider data. |

The plan must name each exact file, checksum, codecs, duration, license/source, and how the relay is started. If an MKV/TS fixture is unavailable, obtain a licensed sample before Chunk 1; if the non-seekable relay cannot make mpv expose unavailable/non-finite duration, document the observed behavior and add a deterministic fixture that can. Do not paper over it by hard-coding `CatalogType.LIVE` as non-seekable.

Baseline/prototype pass thresholds are: no sustained CPU increase greater than 10% of the `wid` baseline; after each of five fully disposed cycles, working set must return to within `max(10%, 100 MiB)` of its post-settle baseline and show no monotonic increase; no persistent black/corrupt frame after normal/rapid resize, monitor-DPI move, minimize/restore, or cinema chrome change; and no duplicate native render context/player. These are gates, not aspirational targets.

#### 0.5 Playback/control behavior contract for later chunks

- **Stop/resume:** `stop` invalidates the active generation, sends exactly one owner-thread stop command, clears position/duration/buffering state, and remains on the player route with the terminal `Stopped` state. Back/route disposal is separate: it records a non-live position only if the existing five-second rule permits it, then tears down once. Stop itself creates no resume write. New load, Next episode, Back, and error each invalidate stale callbacks before publishing their own state.
- **Seek and drag:** only finite duration `> 0` is seekable. Button seeks are clamped to `[0, duration]`. Slider press snapshots the displayed position; while dragging, UI displays the drag target and sends no continuous native seek. Release/cancel sends at most one absolute seek (none when cancelled/no material delta); keyboard repeats are coalesced to one owner-thread command per 100 ms, with a final command on key-up. A new load/stop/dispose cancels pending seek work by generation.
- **Volume:** `PlayerUiState` holds `volumePercent: Int` clamped 0..100 and `muted: Boolean`, initialized/observed from mpv `volume` (`MPV_FORMAT_DOUBLE`) and `mute` (`MPV_FORMAT_FLAG`). Owner-thread APIs are `setVolume(percent)`, `adjustVolume(delta)`, and `setMuted(Boolean)`/`toggleMuted()`, using mpv properties; mute preserves the selected nonzero volume. The dock has a labelled mute toggle and labelled 0–100% slider, keyboard-accessible with visible value and focus; `M` toggles mute and Up/Down adjust 5% only when no control owns the key. Accessible names announce e.g. “Mute, on” and “Volume, 45 percent”; unavailable audio disables controls truthfully rather than pretending volume changed.
- **Focus-aware keys:** a window-level dispatcher acts only when the player route is active and no text input, menu/dropdown, dialog, tooltip/actionable error, button, or slider owns the event. In that neutral video focus, Space/K toggle pause; Left/Right seek 5 seconds; Shift+Left/Right seek 30 seconds; S stops; F toggles cinema; Esc exits cinema first, otherwise delegates to normal Back. When a Compose button/slider has focus, Enter/Space and arrows retain that component's standard behavior; global transport never steals them. Text input receives all printable/navigation/editing keys. Dialog/menu handlers get Escape first. Tooltips never take focus; an actionable error does, suppressing transport keys until dismissed.
- **Auto-hide:** cinema controls begin visible for 2.5 seconds and fade over 120 ms only while playback is active, pointer is not over a control/menu/tooltip, no dropdown/dialog/actionable error is open, no control has keyboard focus, no slider is dragging, and no pointer press is active. Pointer movement over video, a supported neutral-video key, or entry into a control reveals/resets the timer. Dragging, menus, tooltips associated with a hovered control, focus traversal, paused/buffering/ended/actionable-error states keep chrome visible. Leaving a control may restart the timer only after all those holds clear. Hiding changes alpha/hit testing only; it never resizes/reloads the render surface.

#### 0.6 Episode cache and deterministic successor contract

Hoist episode loading from `EpisodeScreen` into a route-owned `SeriesEpisodeStore` in `DesktopShell` (or equivalent presenter) keyed by `seriesId`. It exposes `Loading/Ready(immutable ordered list)/Error`, deduplicates an in-flight fetch, retains a successful list while the player route is open, and is passed to both episode picker and `PlayerScreen`. `Next episode` reads only that `Ready` snapshot—no request is initiated by visibility or click. A direct resume-route episode that has no ready snapshot may load the list once before enabling Next; failure leaves Next absent and playback unaffected.

Canonical ordering is total and deterministic: season key first, episode key second, then normalized nonblank title, then lexical `id`. A numeric key is a trimmed string matching `^[+]?[0-9]+$`, parsed as arbitrary-precision non-negative integer; numeric values sort before non-numeric values; non-numeric/blank values sort by `trim().lowercase(Locale.ROOT)` with blank last. Duplicate season/episode numbers are **not** silently collapsed: their title/id tie-breakers provide stable order, and the successor is the next element after the current `episode.id` in the full ordered list. Missing current ID, duplicate ID, or no following element means no Next button. This explicitly handles blank, duplicate, formatted (`01`), and non-numeric season/`episode_num` values without provider-dependent iteration order.

#### 0.7 Expanded render gate and evidence

Chunk 1 may start only when the following matrix has named procedure and evidence rows: normal/rapid resize; rapid load/seek/stop/dispose; 100% and 150% DPI plus moving across monitors; minimize/restore; simulated/reported GL context loss; MKV subtitles on/off; SDR color/levels; HDR input behavior; and hardware decoding (`auto`, then documented software fallback only for diagnosis). HDR is not accepted merely because it displays: the initial product contract is SDR output with no crash/washed-out uncontrolled conversion; native HDR/color-management delivery requires a separate approved design. `hwdec` is accepted only if the bundled DLL/Skiko GL backend supports it without corruption or leaks; otherwise the diagnostic result is recorded and the user decides whether an explicit supported fallback is acceptable.

**Success criteria:** the exact current fixtures/baseline are recorded; the pinned Skiko integration path, ABI validation result, callback retention, and teardown barrier are documented; every behavior above has a testable owner; and the prototype boundary is limited to render bindings/adapter/fixture diagnostics. No production route changes in Chunk 0.

**Stop conditions:** missing required DLL export or ABI proof; unsupported/currently inaccessible Skiko OpenGL context; no safe before-Compose frame hook; inability to create/free in the stated order; non-seekable fixture absent; any unresolved context-loss/DPI/hwdec/subtitle/color outcome; or a need for private/reflection-based Skiko access. Stop and report evidence rather than improvising a renderer.

#### 0.8 Current-state divergence to resolve before Chunk 1

- `MpvPlayer.close()` currently enqueues a termination marker and returns without joining (`MpvPlayer.kt:389-405`). The Chunk 0 teardown barrier requires a bounded, acknowledged render-teardown-then-mpv-terminate sequence, so `close()` must become bounded blocking (or awaitable) teardown before render-context work can rely on it.
- The `handle` assignment is a pre-existing ownership/visibility correctness question: the code assigns it during initialisation (`MpvPlayer.kt:92`) despite the owner-thread-only mutation comment (`MpvPlayer.kt:46`). Resolve this, or explicitly accept it as safe and document why, before the render thread depends on the handle's visibility guarantees.

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
