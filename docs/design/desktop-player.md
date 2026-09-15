# Desktop player design proposal

Status: proposed 2026-09-13. This document defines the POC-D1 player shell only. It does not approve provider sign-in, catalog, artwork, persistence, real-stream playback, or desktop feature parity.

## Intent

The desktop player should feel like a quiet projection booth: the picture is the primary object and controls appear only while they help operate it. This keeps the native LibVLC surface from visually competing with Compose, and carries Tvivo's existing identity forward without copying its ten-foot TV layout.

The system retains the established palette so the product remains recognisable:

| Token | Value | Desktop use |
|---|---:|---|
| Background | `#0A1619` | application matte and empty viewport |
| Surface | `#0F2126` | top information band |
| Elevated | `#163036` | control dock and file chooser affordance |
| Line | `#1E4149` | quiet separators and inactive control outlines |
| Ink | `#E8F1F2` | primary labels and timecode |
| Dim | `#93ACB1` | secondary file and state information |
| Accent | `#D97757` | keyboard focus, active transport, seek progress |
| Accent text | `#E08466` | non-fill status text |
| On accent | `#1A0A05` | text on an accent fill |

Orange remains operational rather than decorative. It marks the focused control, active play state, and the played portion of the timeline; errors use copy and an icon, not an unapproved red system.

## Windowed player

The initial window is 1200 x 780 dp and is resizable. It has three regions:

```
┌─────────────────────────────────────────────────────────────┐
│ Tvivo desktop                         Fixture · Ready         │  52 dp
├─────────────────────────────────────────────────────────────┤
│                                                             │
│                     Native video viewport                   │  flexible
│                16:9 fit, centred, matte only               │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│ [Open fixture]  [Play/Pause] [Stop]  00:00 ━━━━━ 00:00 [⛶] │  76 dp
└─────────────────────────────────────────────────────────────┘
```

- The top band carries only app identity, a concise media state, and the selected local fixture name. It is persistent so a playback failure has an unambiguous place to appear.
- The video viewport is the centre region and owns all available remaining height. It is not placed behind Compose controls.
- The control dock is persistent in windowed mode. This is a local-fixture POC, where discoverability is more useful than cinema-style autohide.
- The file name truncates in the middle with its extension retained. The full local path is never shown in the primary UI or copied into diagnostics.
- Before a fixture is selected, the viewport stays matte with a single centred `Open a local fixture` action. It must not present as a playback failure.

### Aspect and resize policy

- The video surface uses **fit** behavior: preserve the source aspect ratio and centre it in the viewport. Cropping is never the default.
- Matte is visible only where the viewport ratio and source ratio differ. It uses `Background`, not pure black, so it reads as part of the application rather than an accidental empty frame.
- The viewport does not impose a 16:9 box on the window. Instead, the native surface fills the available centre region and LibVLC fits the video inside it. This avoids a second, oversized matte frame around the content.
- Minimum usable window size is 760 x 560 dp. Below it, the footer wraps only the secondary actions; the timeline keeps a minimum 180 dp track and primary transport never disappears.
- At widths below 920 dp, the file name leaves the top band and appears as a one-line label above the timeline. At widths below 760 dp, fullscreen remains available but the window is considered unsupported rather than silently clipping controls.

## Full-screen player

Full screen is picture-first. The native surface fills the window. Compose chrome is a sibling layer, not an overlay that attempts to sit above a heavyweight AWT component.

1. Entering full screen starts with controls visible for 2.5 seconds.
2. Pointer movement, a key press, or remote focus makes controls visible again and resets that timer.
3. Controls fade from 100% to 0% alpha over 120 ms after 2.5 seconds of inactivity. There is no scale or spring motion.
4. The visible chrome is a bottom control dock, plus a small top-left title/state strip. Both have an opaque `Surface` background. Do not rely on a translucent Compose scrim above the native surface.
5. While keyboard focus is inside the control dock, it stays visible. It only hides after focus returns to the viewport and the inactivity timer expires.
6. `Esc`, `F`, and the full-screen control return to windowed playback. They do not stop playback. Window close still stops and disposes the player.

Full-screen matte follows the same fit policy. A title or controls must never force a smaller video rectangle while hidden.

## Controls and input

| Action | Pointer | Keyboard / remote |
|---|---|---|
| Choose fixture | `Open fixture` | `Ctrl+O` / Enter when focused |
| Play or pause | primary transport button | Space or K / Enter |
| Stop | stop button | S |
| Seek | timeline click or drag | Left / Right: 5 seconds; Shift+Left / Right: 30 seconds |
| Full screen | full-screen button | F |
| Leave full screen | button | Esc or F |
| Close | window close | Alt+F4 |

- A seek cannot be shown as meaningful until LibVLC has reported that the fixture is seekable and has a duration. Until then, use a disabled track and `Seeking is unavailable for this fixture`; never display a fake 0:00 to 0:00 slider.
- The primary transport is one button whose label and accessible name change between `Play` and `Pause`. Separate Pause and Resume buttons are removed.
- Tab follows reading order: Open, Play/Pause, Stop, timeline, Full screen. Arrow keys adjust only the focused timeline, except the documented global seeking shortcuts.
- Visible keyboard focus is a 2 dp Accent outline with a 2 dp Background separation. Hover is a quiet Elevated fill; it does not use Accent.
- All controls have labels, tooltips, and accessible names. Status changes are announced through a polite live region, while playback errors are assertive.

## Heavyweight-native-surface boundary

`SwingPanel` / AWT video is heavyweight. Compose content must therefore be laid out **outside** the video surface in windowed mode. Avoid putting buttons, sliders, menus, tooltips, or a translucent overlay over it: z-order differs across Windows configurations and can make Compose controls unclickable or invisible.

For full screen, use a deliberate two-state composition rather than overlaying Compose on the active AWT surface:

- **Controls hidden:** one full-window native video surface.
- **Controls visible:** reserve opaque top and bottom Compose bands outside a resized native viewport, then restore the full viewport when hiding controls.

The resize must be debounced and must preserve playback. If LibVLC cannot resize its HWND without a perceptible flash, retain a persistent opaque full-screen dock instead of attempting an unreliable overlay.

## State presentation

| State | Viewport | Top band | Dock |
|---|---|---|---|
| No fixture | matte, centred Open action | `Choose a local fixture` | Open enabled; transport disabled |
| Ready | matte or last frame | fixture name + `Ready` | Play enabled |
| Playing | fitted video | fixture name + `Playing` | Pause, Stop, timeline enabled if seekable |
| Paused | frozen frame | fixture name + `Paused` | Play, Stop, timeline as available |
| Ended | last frame | fixture name + `Ended` | Play restarts, Stop enabled |
| Error | matte or last safe frame | clear one-line error | Open remains enabled; retry is Play only when a fixture is still loaded |
| LibVLC unavailable | matte | `LibVLC needs setup` | Open disabled; show the exact non-secret configuration hint |

The POC may state the container extension and the error class in its status. It must never show a provider address, credential, complete local path, or diagnostic stack trace in the window.

## Implementation acceptance checks

- The native viewport, top band, and dock never overlap in windowed mode.
- Resizing retains the source aspect ratio and produces at most the intentional application matte.
- Full-screen controls remain keyboard-operable and disappear only according to the documented timer/focus policy.
- Focus, hover, keyboard shortcuts, disabled controls, and status messages meet the rules above.
- The design is validated with the POC's local synthetic MP4, MKV, and TS fixtures only. No validation opens a provider stream.

## Deferred decisions

- Audio/subtitle track menus wait for track-discovery evidence from POC-D1.
- Frame stepping, playback speed, playlist behavior, capture, and media-library UI are out of scope.
- The application remains desktop-player POC chrome, not a desktop port of Android TV browse UI.
