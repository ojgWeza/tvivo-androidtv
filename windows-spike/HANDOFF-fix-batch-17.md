# Fix batch 17 handoff

Requested scope was source and handoff edits only. No build, test, launch, install, or live verification was performed. No Python was used. The confirmed batch-16 playback-surface visibility logic was not touched.

## VERDICT

- **Tooltip:** No in-app visual tooltip, nav hover popup, or debug HUD source was found after searching the spike's app source, XAML resources, templates, and project files. The visible numbered hover behavior remains unidentified; an OS accessibility/automation surface is possible but unproven. No speculative fourth tooltip fix was added.
- **Spotlight:** Moved previous/next and position indicators into the right-side text column, aligned them right, and applied the frameless caption-button style to arrows and dots.
- **Player list:** The normal movie/live click route does not rebuild the collection: it updates `IsCurrent` in place. However, a movie click can start an asynchronous category refresh when `_playerSiblings.Count <= 1`; a changed result then reaches `SetPlayerList`, which cleared and repopulated the collection without preserving scroll. Also, `SelectionMode="None"` meant the ListView had no native selected item. The update now synchronizes native selection with the orange current-row marker and restores the pre-click or pre-rebuild vertical offset after dispatch/layout.

## STRONGEST EVIDENCE

- `MainWindow.xaml.cs`: `PlayerRelatedList_ItemClick` routes movie/live clicks to `PlayRelatedChannel`; that method calls `SetCurrentPlayerEntry` and does not call `SetPlayerList` for an ordinary multi-row list. The source `ObservableCollection` remains attached to `PlayerRelatedList.ItemsSource` from construction.
- `PlayRelatedChannel` has one collection-refresh exception: for a movie with `_playerSiblings.Count <= 1`, it starts `LoadMovieCategorySiblingsAsync`. That method assigns refreshed `_playerSiblings` and calls `SetPlayerList`. `SetPlayerList` clears/adds rows when count or ordered `(Source.Kind, Id)` identities differ. Before this batch, that path had no scroll-offset capture/restore.
- The item template already binds its orange `BackgroundBrush` and text `ForegroundBrush` to `PlayerListEntry.IsCurrent`; `IsCurrent` raises `PropertyChanged` for both brushes. Batch 16's in-place property notification exists. This source path does not explain the reported failure to repaint on a multi-row list, so runtime confirmation is still needed.
- The ListView was explicitly `SelectionMode="None"`. Batch 17 sets it to `Single`, assigns its `SelectedItem` from the same current entry used by the orange marker, captures the vertical offset on pointer press, and restores it after click handling. `SetPlayerList` also preserves the offset when it must replace rows.
- The spike has no project `Generic.xaml`/theme override. The app's top-nav Button style has no ToolTip setter; nav buttons already have explicit null tooltips. No nav pointer-hover Popup/handler or app tooltip content was found. Searches for FPS/frame-rate counters, overdraw/text-performance visualization flags, debug HUDs, and related `DebugSettings` flags found none enabled. `App.xaml.cs` does enable XAML resource-reference and binding tracing; the `TVIVO_XAML_DIAGNOSTICS` configuration only enables `FailFastOnErrors`, not a visual overlay.

## EXACT BOUNDARY

- `src/Tvivo.App/MainWindow.xaml` — use native single selection and capture list pointer presses.
- `src/Tvivo.App/MainWindow.xaml.cs` — synchronize `SelectedItem` with `IsCurrent`; locate the player list ScrollViewer; restore the captured vertical offset after click updates and row replacement.
- `src/Tvivo.App/Pages/CatalogLandingPage.xaml` — move the Spotlight controls into the right text column and use `AppCaptionButtonStyle` on the arrow buttons.
- `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` — use the same flat caption style for generated dot buttons.
- `HANDOFF-fix-batch-17.md` — this report.

No tests were added: the changed behavior depends on WinUI ListView/ScrollViewer event and layout behavior and cannot be meaningfully exercised by the existing non-UI unit test setup. No tests were run, as requested.

## CONFIDENCE

- Tooltip absence of a code-defined app source in the searched spike files: **medium-high**. Tooltip origin itself: **unknown**; OS-level behavior is only a possibility until a screenshot identifies the surface.
- Spotlight layout/style changes: **high from XAML/resource source**, visual spacing remains unverified.
- Ordinary movie/live click not rebuilding the list: **high from call path**.
- Asynchronous one-row movie refresh can replace the list and previously had no offset restoration: **high from source**.
- Native selection now follows the current entry and scroll restoration is scheduled after click/rebuild: **high from source; runtime effect unverified**.
- Why a multi-row click previously appeared to scroll and fail to repaint despite the existing `INotifyPropertyChanged` marker update: **unresolved from source**. The new offset and native-selection handling addresses both symptoms, but is not proof of the original runtime trigger.

## RULED OUT

- The confirmed synchronous playback-surface collapse path was not changed.
- A normal movie/live click unconditionally calling `SetPlayerList` or rebuilding `_playerEntries`: source contradicts this; it only updates current state in place, except for the one-row asynchronous movie refresh described above.
- An in-project generic theme template override: no `Generic.xaml` or theme-resource override exists in the spike source tree.
- A nav-bar `ToolTip` setter or app-defined nav hover popup: none found; top-nav buttons explicitly null their tooltips.
- Known in-app FPS/frame-rate or overdraw debug visualizations: none found. XAML binding/resource tracing writes diagnostics; it does not add a visual HUD in this code.

## UNCERTAINTY

- The reported nav number has not been matched to an app visual. Without a screenshot showing the hover target and popup, it cannot be attributed to Windows accessibility, UI Automation, or any other external component.
- The source proves a possible async collection-replacement route for a one-row movie list, but it does not explain scrolling on every reported multi-row click. The existing marker binding raises property changes correctly in source.
- WinUI's selected visual, pointer event ordering, deferred ScrollViewer restoration, and the revised Spotlight placement have not been executed or inspected live.

## NEXT GATE

When live verification is permitted:

1. Hover the nav control and capture a screenshot that includes the number and its anchor/placement. If it persists, use that evidence to distinguish an app surface from Windows accessibility/automation UI.
2. In a multi-row player list, scroll away from the top, click visible movies and live channels, and confirm the vertical offset is retained and the orange/native selection immediately follows playback. Also verify the one-row movie refresh path.
3. Inspect Spotlight at the target window size: arrows and dots should sit together at the right of the component and show only subtle hover fill, with no button border/frame.
4. Recheck Back and Movies-from-Player flicker to confirm the already user-confirmed surface-collapse fix remains intact.
