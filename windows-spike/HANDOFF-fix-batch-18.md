# Fix batch 18 handoff

Source and documentation edits only. No build, test, app run, launch, install, emulator, or Python was used. A focused repository sorting test was added but remains unrun.

## VERDICT

- **Home from Player transition — fixed in source; high confidence.** The Home handler omitted `returningFromPlayer`. A source audit also found that the flag was only included in trace text in this checkout; it did not drive a layout barrier. `ShowCatalogAsync` now infers player-origin navigation for every call site, and passes it into `ShowPage`. That path masks the catalog to opacity zero, forces catalog/root layout, then uses the existing fade-in. Home explicitly passes the flag too.
- **Click then navigate away — stale visual writes guarded; cause confidence medium.** `StartPlaybackAsync` already guarded its post-handoff result, but the stop/setup callback awaited `_playbackStopTask` and stopped both engines before calling `PlayerPage.UpdateLayout()` without rechecking its generation or page. It now checks after each await and before layout. Metadata already checked a generation and selected item after provider completion; it now also requires the Player page before applying metadata or changing player visuals. `CLICK-AWAY-TRACE` records selection, rapid departure (within five seconds), and discarded late completions. Static evidence supports a race window, but does not prove it caused the reported redraw.
- **Folder full listing sort — wired in source; high confidence for provider category folders.** The shared sort combo was hidden while a folder grid was open. It now stays active, changes its label to “Sort titles”, and passes the existing `CategorySort` choice through to the paged SQLite query so ordering happens before offset/limit. My Tvivo/activity folder results also apply alphabetical and date ordering before paging.
- **Info button — fixed in source; high confidence.** The `ⓘ` button used the platform default button template, which accounts for the default grey circular chrome. It now uses shared `AppIconButtonStyle`, based on the transparent caption-button template; the reusable rule is in `DESIGN-RULES.md`.
- **Sibling/episode selection — virtualization path addressed; medium-high confidence.** The orange row is the item-template `Border.Background` bound to `PlayerListEntry.BackgroundBrush`, and `IsCurrent` raises `PropertyChanged` for that brush. There is no `ListViewItem` container style or `ContainerContentChanging` refresh handler. Selection can be assigned while the list is collapsed or its target is virtualized. The code now ensures the current entry is inside the viewport after selection and after offset restoration, scrolling it into view when its container is absent or outside the visible bounds. Runtime confirmation is still needed.

## STRONGEST EVIDENCE

- `MainWindow.xaml.cs`: all catalog navigation routes pass through `ShowCatalogAsync`; that method now sets `returningFromPlayer` when `_currentPage == Player`. The prior flag was only present in the trace call. `ShowPage` now performs the zero-opacity layout barrier before its existing `FadeIn` on the catalog surface.
- `StartPlaybackAsync`: `_playbackHandoff.RunAsync` calls an async UI callback that awaited `_playbackStopTask`, stopped both engines, yielded, and updated `PlayerPage` layout. The new validity checks prevent that callback from reaching later UI/layout work after cancellation, generation change, or page departure. Final playback-result writes remain behind a generation/page check.
- `LoadPlayerMetadataAsync`: before this batch, provider completion was rejected on metadata generation and channel id; leaving Player increments the generation and clears the selected channel. The explicit page check now makes the visual boundary direct and logs rejected completions.
- `CatalogLandingPage.xaml.cs`: `RenderShelfSurface` previously set `SortPanel.Opacity = 0` and disabled hit testing for an open shelf. Full-category page queries now use the selected item order in SQLite before applying page offsets. The added test asserts recently-added order across two pages and descending alphabetical order.
- `SqliteCatalogRepository.GetChannels` builds ordering only from the closed `CatalogItemSortOrder` enum. `RecentlyUpdated` uses `first_indexed_at` because the item schema has no separate last-updated column.
- `PlayerListEntry.IsCurrent` raises notifications for both brush properties; the XAML item template binds the orange background to `BackgroundBrush`. The UI path checks the selected container against the ScrollViewer viewport after list selection and after restoring an offset; it calls `ScrollIntoView` when the selected container is absent or outside the viewport.

## EXACT BOUNDARY

- `src/Tvivo.App/MainWindow.xaml.cs` — infer and apply player-return barrier for every catalog route; guard asynchronous player-start/metadata UI work; add `CLICK-AWAY-TRACE`; bring selected sibling/episode entry into view.
- `src/Tvivo.App/MainWindow.xaml` — apply `AppIconButtonStyle` to the title info button. The existing batch 17 single-selection and pointer-offset changes are present in the worktree.
- `src/Tvivo.App/App.xaml` — add the shared icon-only style based on caption-button chrome.
- `src/Tvivo.App/Pages/CatalogLandingPage.xaml` — name the shared sort label.
- `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` — keep the sort control available on open folder grids and wire order into paging.
- `src/Tvivo.Core/Contracts.cs` and `src/Tvivo.Infrastructure/SqliteCatalogRepository.cs` — define and execute stable item ordering in the paged query.
- `tests/Tvivo.Infrastructure.Tests/SqliteCatalogRepositoryTests.cs` — add a sort-before-paging test; not run.
- `DESIGN-RULES.md` — document the default shared icon-button style.
- `HANDOFF-fix-batch-18.md` — this report.

## RULED OUT

- The player metadata completion path does not apply its returned metadata after leaving Player: its generation is invalidated and the selected channel cleared by navigation; the explicit page check reinforces this.
- Playback-result UI writes already had a final generation and page guard. The uncovered source gap was the earlier stop/setup callback's awaited path to `PlayerPage.UpdateLayout`.
- The selected orange background is not supplied by the native ListView selected-container background. It is the data-template border binding to `IsCurrent`; no container refresh event or custom ListViewItem style exists in this markup.
- Folder sorting is not a per-visible-page reorder: full provider category folders sort in the SQL order clause before `LIMIT` and `OFFSET`.

## UNCERTAINTY

- No runtime evidence establishes that the uncovered `PlayerPage.UpdateLayout` continuation caused the intermittent catalog redraw. The new trace can confirm whether a stale start or metadata completion follows a rapid departure.
- WinUI's actual realization timing after `ScrollIntoView`, visual response of the title button, and player-return transition remain unverified live.
- The schema has no item last-updated timestamp. `RecentlyUpdated` uses `first_indexed_at` for provider-category pages; activity-folder fallback ordering uses `updated_at` metadata when present, otherwise `AddedAt`. This label therefore has a weaker data source in activity folders.
- The test was added but not executed. No build or test result is claimed.

## NEXT GATE

When live verification is authorized:

1. From a playing movie, use Home, Back, and each available top navigation route; confirm the catalog opacity/layout barrier is logged before fade-in and no resize/redraw flashes.
2. Click a movie and navigate away immediately. Inspect `CLICK-AWAY-TRACE` ordering for `rapid-navigation-away`, then any `playback-completion-discarded` or `metadata-completion-discarded` event. Confirm there is no player surface visible over the catalog.
3. Open a large category folder, change each sort option, and verify ordering continues correctly across the 99-item page boundary. Check My Tvivo activity folders too.
4. Inspect the `ⓘ` control at rest, hover, and pressed; confirm it has no grey circle and only the subtle orange highlight.
5. In the player related list, navigate to selected sibling/episode entries both on-screen and outside the current viewport. Confirm the orange row is visible immediately and list position remains sensible after clicks and category refresh.
6. Run the added repository sorting test and the relevant app/infrastructure tests; none were run for this batch.
