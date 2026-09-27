# Fix batch 16 handoff

Requested scope was source and handoff edits only. No build, test, launch, install, emulator, or live verification was performed. No Python was used.

## VERDICT

Six reported behaviors were investigated and source fixes were made. **None is declared live-fixed** because this batch was not executed. The best-supported Back diagnosis is that the LibVLC XAML `VideoView` remained `Visible` throughout the transition, including after `PlayerPage` collapsed. Source inspection confirms this WinUI control is backed by a `SwapChainPanel`, not a separate child HWND, so the classic HWND airspace explanation is not supported for this integration. The surface now collapses synchronously at the beginning of player navigation, before playback teardown, page visibility changes, or catalog fade; the same `ShowPage` path handles Back and top-category navigation.

## STRONGEST EVIDENCE

- Live log evidence in `%LOCALAPPDATA%\Tvivo\tvivo-launch.log`:
  - `BACK-TRACE id=1`, 2026-09-27 12:56:53.613551 +03:00: `show-page-player-visibility-set page=Catalog player=Collapsed`; 12:56:53.613720: `show-page-catalog-visibility-set page=Catalog catalog=Visible`. The first sampled frame at 12:56:53.661903 and all 12 samples through 12:56:54.083659 still report `video=vlc=Visible,native=Collapsed` while `player=Collapsed`.
  - `BACK-TRACE id=2`, 2026-09-27 12:57:44.182602 +03:00: player becomes `Collapsed`; 12:57:44.182849: catalog becomes `Visible`; 12:57:44.183105: catalog fade starts. Samples 1–12, 12:57:44.185689–12:57:44.382013, all report `player=Collapsed` and `video=vlc=Visible,native=Collapsed`.
  - In trace 2, both playback engines had already stopped by 12:57:44.181800, yet the subsequent page transition still logged VLC `Visible`. The mismatch is therefore directly in surface visibility state, not a delayed engine stop.
- The app pins `LibVLCSharp.WinUI` 3.10.1. The corresponding WinUI implementation in [VideoViewBase.cs](https://github.com/videolan/libvlcsharp/blob/3.x/src/LibVLCSharp/Platforms/Windows/VideoViewBase.cs) declares a `SwapChainPanel` template part, derives `VideoViewBase` from XAML `Control`, and creates a D3D11 swap chain. This is also consistent with `WindowsPlaybackEngine.cs` receiving `InitializedEventArgs.SwapChainOptions`. There is no separate video HWND whose visibility/z-order can be logged; new `BACK-TRACE` surface state records XAML visibility, load/size, the swap-chain host, and `nativeHwnd:not-applicable`.
- `PlayerRelatedList_ItemClick` already receives `ItemClickEventArgs.ClickedItem` directly, so a delayed `SelectedItem` binding was not the cause. The movie route called `CatalogLandingPage_ChannelSelected`, which replaced `_playerSiblings` with just the selected movie and called `SetPlayerList`; that clears and repopulates the bound `ObservableCollection`. The later category query repopulated it again. The new in-player route updates `IsCurrent` in place and preserves the category rows and scroll position.
- `SpotlightArtwork_Tapped` was attached to a Border containing both artwork and the previous/next Buttons. A tap routed from the nested buttons could also reach the Border handler and invoke the open/play action. The artwork target and navigation row are now siblings, so navigation has a separate hit-test area. The row has previous/next buttons and up to seven clickable position indicators.
- MainWindow and navigation-control source searches found explicit tooltip nulls on the top navigation, no `AutomationProperties.HelpText`, and no timestamp Popup or hover handler. Batch 16 extends explicit null tooltip values to the caption buttons and reusable `ShellNavigationButton`. The original appearance was not reproducible here, so this remains subject to live hover verification.

## EXACT BOUNDARY

- `src/Tvivo.App/MainWindow.xaml.cs` — synchronously hide the selected video surface at the start of player navigation; restore only the selected engine surface when entering Player; record XAML host state in `BACK-TRACE`; start a trace for player departures initiated from the top navigation; route Escape through `ReturnFromPlayer`; handle related movie/live clicks in place without rebuilding the list.
- `src/Tvivo.App/MainWindow.xaml` — suppress explicit tooltip content on title-bar and navigation/header controls.
- `src/Tvivo.App/Controls/ShellNavigationButton.xaml` — suppress explicit tooltip content on the reusable navigation button.
- `src/Tvivo.App/Pages/CatalogLandingPage.xaml` — move Spotlight navigation below and outside the artwork tap target; add compact dot indicators between previous/next affordances.
- `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` — render/cycle/select bounded indicator dots.
- `src/Tvivo.App/CatalogUiPolicies.cs` and `tests/Tvivo.App.Tests/AppSmokeTests.cs` — add the pure indicator-window policy and a focused bounds/selection test. Test was not run.
- `HANDOFF-fix-batch-16.md` — this report.

The working tree already contained batch 15 edits in other files (`WindowStateController.cs`, catalog repository/contracts, and their tests); they were left intact. The code boundary above lists files changed for batch 16.

## CONFIDENCE

- Back surface visibility cause and synchronous hide path: **high for the logged state mismatch; medium for visual resolution** until live confirmation. The observed control is swap-chain-backed, not an HWND airspace surface.
- Top-navigation Movies-to-catalog path: **high from source**. It reaches `ShowPage(Catalog)`, which detects departure from Player and now calls the same early surface hide.
- Related list click/scroll root cause: **high from source**. The click uses the clicked object directly; rebuilding the bound rows explains the reset. Runtime retention/highlight still needs confirmation.
- Navigation tooltip: **low-to-medium**. Source scan found no timestamp tooltip implementation; explicit nulls now include title-bar controls and the reusable nav control, but the live source of the timestamp has not been identified.
- Spotlight click-through and redesign: **high from event-tree/layout source**; live hit testing was not checked.
- Escape behavior: **high from source**. One Escape now follows the same return path as the on-screen Back button, including cinema exit and surface hiding.
- Compilation and tests: **unknown**; neither was run as requested.

## RULED OUT

- A separate native video child HWND as the expected LibVLC WinUI integration: the pinned WinUI package's source uses a XAML `Control` with a `SwapChainPanel`/D3D11 swap chain. No independent HWND visibility or z-order state is exposed for this video surface.
- Delayed playback shutdown as the reason for the captured stale `Visible` value in trace 2: both engines stopped before the page became visible, but VLC still logged `Visible` after the transition.
- `SelectedItem` binding latency as the primary in-source list defect: the handler uses `ClickedItem`. The collection reset in the route it called is the source-supported cause.
- A periodic playback timer replacing the list: `PlaybackUiTimer_Tick` updates progress and changes the current marker only when playback ends; it does not set `ItemsSource` or mutate `_playerEntries` during normal playback.
- A code-defined timestamp Popup or `HelpText` in MainWindow and the reusable navigation control: none was found in source searches.

## UNCERTAINTY

- The live trace records XAML visibility and compositor-frame samples, not the actual displayed pixels. It shows an invalid state where the video surface remains visible after its page collapses, but only live verification can prove that collapsing the swap-chain host removes the visible flicker.
- The trace's original `video=vlc=Visible` values do not independently prove that video pixels were drawn above catalog content. The source-level XAML state bug is concrete; the final visual mechanism remains a live check.
- The live tooltip's origin remains unknown after the source scan. If it persists with the added null values, capture the hover target and visible popup in a screenshot so it can be matched to a native/system surface or another control.
- WinUI layout/hit-testing, indicator sizing, category list scroll/highlight behavior, and the new test remain unverified because no execution was allowed.

## NEXT GATE

After a build is permitted, run the focused `Tvivo.App.Tests` suite and build the app. Then live-check:

1. Press the on-screen Back button and Escape from windowed and cinema playback. Confirm the first `playback-surface-state` after player-leave reports `vlc=Collapsed` before `show-page-catalog-visibility-set` and the first compositor frame; verify one Escape returns directly to the category.
2. While playing, click Movies, Series, and Live TV in the top navigation. Confirm a `player-leave-navigation` BACK-TRACE and collapsed selected surface before catalog reveal; check for flicker.
3. Load a category with more than one page of related movies, scroll the right pane, click several visible entries, and confirm first-click playback, unchanged scroll position, and immediate current-item highlight. Repeat for episodes across seasons.
4. Hover every title-bar and navigation/header control. If any timestamp tooltip persists, capture its target and appearance.
5. Click Spotlight previous, next, and several dots; confirm the indicator tracks the active item and no navigation click opens playback. Separately confirm tapping the artwork/action still opens the spotlight item.
