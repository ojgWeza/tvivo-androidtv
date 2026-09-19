# WinUI Catalog Design Plan

**Status:** Design plan for approval before implementation
**Source baseline:** `a0e1cc1903879b07b9d44e7f506713f1962c23b9` — “Add WinUI-owned SQLite cache for Live TV catalog browsing”
**Scope:** Tvivo Windows spike catalog page

## Decision

Reshape the catalog page around a bounded WinUI `Grid` layout and make catalog loading cache-first. Keep the existing shell, palette, SQLite schema, provider, paging size, search, group selection, and channel playback path.

The first implementation increment is limited to `CatalogLandingPage.xaml` and `CatalogLandingPage.xaml.cs`. Do not change `App.xaml`, `MainWindow.xaml`, the repository, or the refresh service.

## Current issues

At the source baseline, the catalog files are under `windows-spike/src/Tvivo.App/Pages/`.

- The page puts its heading, loading, empty, error, and catalog content into one vertical `StackPanel`. The content has no explicit remaining-height region.
- Both catalog panes use vertical `StackPanel` containers around their `ListView` controls. The channel list has no explicit star-sized row, and the pager has no reserved bottom row.
- The group pane uses a fixed 320-pixel column. It reserves that width even when the window is constrained.
- `LoadCatalogAsync` awaits the network refresh before reading SQLite. Cached channels are hidden behind a loading state, and a refresh failure replaces usable cached content with an error state.
- Repeated loads have no in-flight guard or page-generation check. A completion from an earlier request can update the page after a later load or account switch.
- Channel playback is activated through `DoubleTapped`. The DEBUG fixture button is inserted into the heading stack when a local fixture is found.
- `App.xaml` already contains the app’s shared static dark palette brushes. A shared theme-dictionary change is unnecessary for this page layout.

## First increment layout

Replace the outer vertical stack with a page `Grid` containing:

1. An auto-sized header row for the provider label and Catalog title.
2. A remaining-height state/content row.
3. A content grid with a flexible, minimum-width group column and a star-sized channel column.
4. A channel-pane grid with an auto-sized heading/search region, a star-sized results row, and an auto-sized pager row.

Keep loading, empty, and error states in the remaining-height region so the catalog’s content area is bounded. Keep the current group-and-channel master/detail pattern. Do not add window-width visual states, change shell navigation, or redesign channel rows in this increment.

## Theme policy

Keep this slice dark-only to match the existing app palette. Set `RequestedTheme="Dark"` on the catalog `UserControl` so native WinUI controls use dark control resources. Continue using the existing `App.xaml` brushes for page surfaces and text.

No shared `App.xaml` theme dictionaries are needed: the palette already exists, and this increment does not add app-wide light/dark switching. High-contrast palette behavior and a broader theme system are deferred accessibility work.

## Cache-first refresh state machine

Cache is considered present when the account has at least one cached group or channel, matching the existing empty-state condition.

| Event or result | Required page behavior |
| --- | --- |
| Load begins and cache is present | Read SQLite immediately and show the cached catalog. Keep it interactive while refresh runs and show a nonblocking “Refreshing…” status. |
| Load begins and cache is empty | Show the loading state while refresh runs. |
| Refresh succeeds with channels | Reload from the committed SQLite snapshot. Preserve the selected group and filter when still valid; otherwise select “All channels.” Clamp the offset to a valid page, then update the results and pager. |
| Refresh succeeds with groups but no channels | Show the catalog content state with zero results and disabled paging. |
| Refresh succeeds with no groups and no channels | Show the existing empty state. |
| Refresh fails with cached data | Keep cached content visible and usable. Show a concise inline refresh error and Retry action. |
| Refresh fails without cached data | Show the existing error state and Retry action. |
| Retry | Repeat this cache-first path. Cached content remains visible during retry; an empty cache shows loading. |
| Duplicate same-account load while refresh is active | Share or await the active operation rather than start a competing refresh. |
| Completion belongs to a prior page load or account | Do not let it replace the current page. Use a load-generation check before applying results to the UI. |

`SqliteCatalogRepository.ReplaceLiveSnapshot` commits each complete snapshot in a transaction. After refresh, reload from SQLite so the page displays the committed snapshot. Keep concurrency coordination and the generation check in the page code-behind for this increment; no repository or refresh-service edits are planned.

## Behaviors to preserve

- Saved-connection authentication and provider setup flow.
- SQLite-backed live catalog and transactional snapshot refresh.
- Group selection, channel filtering, and 100-item paging.
- Existing loading, empty, error, and retry meanings, with cached results retained on refresh failure.
- Double-tap channel selection and existing player routing.
- DEBUG-only local fixture button behavior.

## Deferred work

- Window-width visual states or a different narrow-window composition.
- Shell navigation changes, including adopting `NavigationView`.
- Light theme support, app-wide theme dictionaries, and high-contrast palette work.
- A broader keyboard and accessibility audit or changes to channel activation semantics.
- Channel row redesign or additional metadata.

Microsoft references for later review: [NavigationView design guidance](https://learn.microsoft.com/en-us/windows/apps/design/controls/navigationview), [WinUI Gallery and Windows App SDK samples](https://github.com/microsoft/WindowsAppSDK-Samples), and [ListView and GridView sample](https://learn.microsoft.com/en-us/samples/microsoft/windows-universal-samples/xamllistview/).

## Acceptance checks

- With cached groups and channels plus a delayed refresh, cached results appear first and remain usable until refresh completes.
- Refresh success reloads the committed snapshot; a successful empty snapshot shows the empty state.
- Refresh failure preserves cached results and exposes Retry; failure with no cache shows the error state.
- Retry during an active same-account refresh does not start a competing request.
- Switching accounts while a request is pending cannot let the earlier completion replace the current page.
- At a constrained window size, the group pane yields room to channels; the results list uses the available height; page status and Previous/Next controls remain reachable without scrolling the whole page.
- Native controls use the dark theme and remain consistent with the existing palette.
- Existing group, search, paging, double-tap activation, fixture, and playback-routing behavior remains intact.

These are implementation acceptance checks. No build, test, or launch was performed as part of this design plan.

## Rollback boundary

Use baseline commit `a0e1cc1903879b07b9d44e7f506713f1962c23b9`. If the increment is rejected, revert only `CatalogLandingPage.xaml` and `CatalogLandingPage.xaml.cs` to that baseline. No other files are in the planned change set.

## Design-skill availability

The requested `winui-design` skill was unavailable in this session. No matching skill file was found under `C:\Users\Dell\.codex\skills`, `C:\Users\Dell\.agents\skills`, or `C:\Users\Dell\.codex\plugins\cache`; therefore there is no skill path or version to report. The WinUI references above are Microsoft guidance and samples, not a claim that the skill was loaded.
