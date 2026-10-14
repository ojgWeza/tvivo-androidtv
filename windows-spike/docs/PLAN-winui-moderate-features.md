# WinUI Moderate Features — Combined Implementation Plan

This plan covers four follow-on features for the WinUI app. Each starts with a bounded feasibility or data-contract check, then adds UI only after the underlying behavior is confirmed. No provider stream should be used for automated verification; use local media fixtures or mocked provider responses.

## 1. Audio and subtitle track switching

### Current surface and capability gap

- `Tvivo.Playback/WindowsPlaybackEngine.cs` creates a LibVLC `MediaPlayer` privately and assigns it to `VideoView`. The installed LibVLCSharp 3.10.1 API documentation includes `AudioTrackDescription`, `AudioTrack`, `AudioTrackCount`, `SetAudioTrack(int)`, `SpuDescription`, `Spu`, `SpuCount`, and `SetSpu(int)`. The engine currently exposes none of those operations to the UI.
- `Tvivo.Playback/FFmpegInteropPlaybackEngine.cs` exposes its Windows `MediaPlayer`, creates an `FFmpegMediaSource`, and assigns `_source.CreateMediaPlaybackItem()` to `Player.Source`. FFmpegInteropX 2.1.0 documents multiple audio streams and subtitle support, and says subtitle support requires using a `MediaPlaybackItem`. The current engine does not retain that item or provide track operations. Windows media-item track lists are the likely enumeration/selection surface (`AudioTracks` and `TimedMetadataTracks`), but verify the actual entries and selection behavior produced by this FFmpegInteropX build with local files before treating it as feature parity.
- The codebase has no shared track-selection contract or track UI. LibVLC exposes explicit audio/SPU descriptions and integer selectors; FFmpegInteropX routes through WinRT media-item tracks. Labels, disabled-subtitle behavior, and track IDs may differ. Do not hide those differences behind an assumed identical index model.

### Proposed implementation

1. Add a small shared track model and optional capability API in `Tvivo.Core` (or keep it in `Tvivo.Playback` if it does not need to cross the app boundary): track kind, stable engine selection key, display label, selected state, and an explicit “Off” subtitle option.
2. In `WindowsPlaybackEngine`, add dispatcher-safe enumeration and selection wrappers around the current LibVLC player. Normalize the “disable subtitles” entry without losing LibVLC's actual track ID semantics.
3. In `FFmpegInteropPlaybackEngine`, retain the active `MediaPlaybackItem` alongside `_source`, enumerate its audio and timed-metadata tracks after media opens, and map selections through the WinRT track APIs. Confirm whether subtitle tracks have useful language/name labels and whether “Off” is supported. Clear the item when `StopCore` clears the player source.
4. Add a compact Tracks button/flyout beside the player transport controls in `Tvivo.App/MainWindow.xaml`; populate it from the active engine in `MainWindow.xaml.cs`, refresh after media opens, and keep the current selection visible. Handle an engine with no tracks by disabling or hiding the relevant choice.

### Human decisions before starting

- Should the control be one “Tracks” menu with Audio/Subtitles sections, or two controls?
- Should the app remember track choice only for the current playback, or per engine/account/title?
- If an engine cannot enumerate subtitles for a file, should its subtitle choice be hidden or shown as unavailable?

### Order and acceptance

First make a local fixture that contains multiple audio streams and at least one embedded subtitle. Prove enumeration, labels, changing tracks during playback, subtitle Off, and selection after a fresh open on each engine. Then add the shared contract and UI. Treat unsupported enumeration on either engine as a visible capability gap, not as an empty-but-working menu.

## 2. EPG-style Live TV guide

### Current data availability

`Tvivo.Infrastructure/XtreamInfrastructure.cs` currently wires category and item actions (`get_live_categories`, `get_live_streams`, plus VOD/series actions), `get_series_info`, and `get_vod_info`. There is no `get_short_epg`, `get_simple_data_table`, EPG DTO, or EPG cache in the repository. The API request helper currently builds only the username/password, action, and optional category ID; EPG requests need a stream ID and limit as well.

This confirms EPG is not implemented in this client. It does **not** prove that the configured panel has no EPG data. Probe both usual endpoints against a mocked response first, then verify endpoint support with a human-selected provider account before committing to a guide. Do not infer “no EPG” from the current code alone.

### Proposed implementation

1. Add an `IEpgProvider` contract and compact programme DTO in `Tvivo.Core`; implement `get_short_epg` first in `XtreamInfrastructure.cs`, with bounded result count, tolerant parsing, cancellation, and no credentials in logs. Add `get_simple_data_table` only if the first endpoint does not supply the fields needed by the chosen screen.
2. Add repository/cache support only after the response shape and refresh cadence are known. Keep guide data separate from the existing `items` catalog rows; programme rows have their own time window and expiry.
3. Add a “Guide” mode for Live TV in `CatalogLandingPage.xaml` and its code-behind. Start with a readable channel list showing current and next programme and a selected-channel action; do not begin with a horizontally scrolling multi-hour grid. Reuse existing channel groups, focus behavior, and playback path.
4. Add loading, empty, stale-cache, and unsupported-provider states. If the provider returns no usable EPG, retain the existing category/channel browse flow and offer a compact “channel details / recently watched” list as the fallback; do not present a fabricated time grid.

### Human decisions before starting

- Is a one-channel-at-a-time Now/Next list an acceptable first guide, or is a multi-channel grid essential?
- What is the acceptable stale-data window and should guide data refresh in the background?
- Should unsupported EPG be a quiet fallback or a visible “Guide data unavailable” message?

### Order and acceptance

Confirm real response shape and endpoint availability first, then define a provider contract and cache lifetime, then build the Now/Next list. Only consider a time grid after real data volume, timezone handling, and navigation have been validated.

## 3. Series next-episode prompt (and skip-intro boundary)

### Existing building blocks

`SeriesEpisodeResolver` in `Tvivo.Core/Contracts.cs` orders episodes and resumes the next episode after a finished one. `MainWindow.xaml.cs` loads series details in `OpenSeriesAsync`, retains `_playerSiblings`, `_playerSeriesSeasons`, `_currentSeriesId`, and the selected episode, and polls the engine timeline from `PlaybackUiTimer_Tick`. The timer already handles completion and stall state. There is no intro-marker or chapter metadata contract in the current provider model.

### Proposed implementation

1. Add a small overlay to `MainWindow.xaml` over the video surface with “Next episode in 5s”, a countdown, Play now, and Cancel. Keep it nonmodal and keep playback controls usable.
2. In `MainWindow.xaml.cs`, derive the next episode from the ordered episode list using the current episode ID. Use `PlaybackUiTimer_Tick` only when the current source is an episode, duration is known, and remaining time crosses a chosen threshold. Do not show for live TV, movies, unknown duration, or the final episode.
3. Start and stop a dedicated countdown timer with the prompt. Cancel it when the user seeks away from the end, pauses for a long time, changes episode, leaves playback, or dismisses it. On timeout or Play now, call the existing episode open/start path with the resolved episode and preserve the series context.
4. Reset completion/resume flags per episode and prevent the completion handler from reopening or double-marking the old episode while the next one starts.

“Skip intro” is a separate capability. There are no intro timestamps, chapter markers, or provider fields in the current series data. Do not label an arbitrary early-playback timer as Skip Intro. Add it only if a reliable marker source is chosen and represented in the data contract.

### Human decisions before starting

- How many seconds before the end should the prompt appear, and should the five-second countdown begin immediately or after a user-visible delay?
- Should autoplay default on, and what is the minimum interaction to cancel it?
- Is skip-intro explicitly deferred until intro markers are available?

### Order and acceptance

Implement next-episode resolution and timer state first, then the overlay. Verify with local fixture timelines and a multi-episode fixture, including seek-back, pause, final episode, and page-exit cases. Keep tests local and never open a provider stream.

## 4. Picture-in-picture / mini-player while browsing

### Hosting constraint

`Tvivo.App/MainWindow.xaml` hosts `LibVLCSharp.Platforms.Windows.VideoView` and `MediaPlayerElement` as separate elements in the player page. `WindowsPlaybackEngine` binds LibVLC to `VideoView`; the FFmpeg engine's `MediaPlayer` is bound to `MediaPlayerElement`. `VideoView` is a swap-chain-backed video host, while `MediaPlayerElement` is a WinUI media control. A simple resize/reparent of both surfaces is not proven safe. In particular, the LibVLC surface may have different clipping, overlay, and z-order behavior from normal XAML content. The earlier native-surface decision in `../../docs/decisions.md` documents similar matte/control overlap friction in the Compose desktop POC; that is a reason to run a WinUI fixture spike, not evidence that the WinUI host has the same defect.

### Proposed implementation

1. Run a fixture-only feasibility spike for each engine: keep playback running while switching to a catalog page, resize the existing host into a corner, overlay normal XAML controls, and navigate/resize the window. Record input, clipping, z-order, DPI, and lifecycle behavior. Do not move or recreate the player instance during the spike.
2. If both hosts remain stable in one window, restructure the page visibility/layout in `MainWindow.xaml` so the active video host has one stable parent and can switch between full and mini layout without teardown. Add a mini-player shell with title, play/pause, close/return-to-player, and restore-to-full-player actions.
3. If LibVLC cannot render correctly above catalog content, stop before adding platform-window work. Compare a dedicated secondary `Window`/AppWindow surface with keeping PiP limited to the native engine, and bring that engine-parity choice back for approval.
4. Add explicit lifecycle rules for navigation, app minimize/restore, engine switching, close, and account changes. Maintain exactly one active playback engine and one video host at a time.

### Human decisions before starting

- Does “picture-in-picture” mean an in-app corner mini-player or a separate always-on-top desktop window?
- Must both engines support it at launch, or is a staged capability acceptable?
- Should catalog interaction continue while the mini-player plays, and what should closing it do to playback?

### Order and acceptance

Do the two-engine rendering spike before production XAML work. Only proceed with a shared mini-player after both surfaces pass resize, z-order, pointer-input, DPI, and repeated navigation checks using local media. If the swap-chain host fails, choose the product scope before designing a fallback window.

## Suggested overall order

1. Audio/subtitle local-track feasibility, because it establishes whether a shared engine capability is viable.
2. Next-episode prompt, since current series data and the playback polling loop already provide most inputs.
3. EPG endpoint probe and response/cache contract, then the Now/Next view if data is available.
4. Mini-player surface spike, followed by a human scope decision if the two hosts behave differently.
