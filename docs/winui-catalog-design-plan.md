# WinUI Catalog Design Plan

**Status:** Design plan for approval before implementation
**Source baseline:** `a0e1cc1903879b07b9d44e7f506713f1962c23b9` — “Add WinUI-owned SQLite cache for Live TV catalog browsing”
**Scope:** Tvivo Windows spike catalog page

## Decision

Reshape the catalog page around a bounded WinUI `Grid` layout and make catalog loading cache-first. Keep the existing shell, palette, SQLite schema, Xtream request/data path, paging size, search, group selection, and channel playback path. Keep provider error-taxonomy changes out of this increment.

The first implementation increment changes only `CatalogLandingPage.xaml` and `CatalogLandingPage.xaml.cs`. Do not change `App.xaml`, `MainWindow.xaml`, `Tvivo.Core/Contracts.cs`, `XtreamInfrastructure.cs`, the repository, or the refresh service.

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
3. A content grid with a star-sized group column (`2*`, `MinWidth="200"`) and a star-sized channel column (`3*`, `MinWidth="432"`).
4. A channel-pane grid with an auto-sized heading/search region, a star-sized results row, and an auto-sized pager row.

Keep loading, empty, and error states in the remaining-height region so the catalog’s content area is bounded. Keep the current group-and-channel master/detail pattern. Do not add window-width visual states, change shell navigation, or redesign channel rows in this increment.

### Minimum supported window width

The minimum supported **app window client width is 1,000 effective pixels**. The existing `MainWindow` reserves 224 epx for its left shell navigation. `CatalogLandingPage` keeps its 52 epx horizontal padding on both sides (104 epx total), and the two content panes keep their 28 epx gap. The group pane has a 200 epx minimum. The channel pane has a 432 epx minimum, composed of a 216 epx page-status slot, two 96 epx pager buttons, and the existing two 12 epx gaps. Thus the page needs 200 + 28 + 432 + 104 = 764 epx; adding the 224 epx shell gives 988 epx, rounded up to 1,000 epx for 12 epx of sizing tolerance.

At and above 1,000 epx, keep both panes visible. Use the `2*`/`3*` columns with those minimum widths: as the window narrows, the group pane gives up its proportional share first while the channel pane retains at least 432 epx. At exactly 1,000 epx, 644 epx remain for the columns after shell, page padding, and gap; the 3:2 ratio alone would yield about 386 epx for channels, so the 432 epx channel minimum clamps that column and leaves 212 epx for groups. The 200 epx group minimum is a hard lower bound, with 12 epx spare at the supported minimum; do not imply that both minima are simultaneously exact at 1,000 epx. Do not claim support below 1,000 epx; add a later responsive/reflow decision before lowering that minimum. Keep the pager in its own auto-sized row and do not wrap the lists in a `ScrollViewer`.

## Theme policy

Keep this slice dark-only to match the existing app palette. Set `RequestedTheme="Dark"` on the catalog `UserControl` so native WinUI controls use dark control resources. Continue using the existing `App.xaml` brushes for page surfaces and text. High Contrast is explicitly **not supported by this increment**: fixed app palette brushes (including the inline error color) are not system contrast brushes, and this slice does not provide High Contrast resources or prove that the forced dark subtree follows the user's Contrast theme. Do not describe the page as High Contrast accessible or supported.

No shared `App.xaml` theme dictionaries are needed: the palette already exists, and this increment does not add app-wide light/dark switching. High Contrast handling is deferred to a separate accessibility increment. Acceptance for this limitation: run the page once with a Windows Contrast theme selected, record that the fixed dark palette is not guaranteed to adapt, and verify that this limitation is documented; do not count that run as a High Contrast pass. A future support claim requires system-aware foreground/surface/error resources, no unconditional dark override that defeats Contrast, and a dedicated Contrast-theme review.

## Cache-first refresh state machine

Cache is considered present when the account has at least one cached group or channel, matching the existing empty-state condition.

| Event or result | Required page behavior |
| --- | --- |
| Load begins and cache is present | Read SQLite immediately and show the cached catalog. Keep it interactive while refresh runs and show a nonblocking “Refreshing…” status. |
| Load begins and cache is empty | Show the loading state while refresh runs. |
| Refresh succeeds with channels | Reload from the committed SQLite snapshot. Preserve the selected group and filter when still valid; otherwise select “All channels.” Clamp the offset to a valid page, then update the results and pager. |
| Refresh succeeds with groups but no channels | Show the catalog content state with zero results and disabled paging. |
| Refresh succeeds with no groups and no channels | Show the existing empty state. |
| Refresh fails for any reason with cached data | Keep cached content visible and usable. Show the same nonblocking inline message, “Couldn’t refresh. Showing saved channels,” and Retry. This increment deliberately does not distinguish offline from authentication/permission failures; do not infer a cause from exception text or diagnostic strings. |
| Authentication or refresh fails with no cache | Keep the full-page error state with the neutral message, “Couldn’t load the catalog. Check your connection and provider details, then retry.” Keep Retry and the existing Home → Set up provider route available. This is temporary generic recovery copy; it makes no claim that the cause is specifically offline, invalid credentials, or permission denial. |
| Retry | Repeat this cache-first path. Cached content remains visible during retry; an empty cache shows loading. |
| Duplicate same-account load while refresh is active | Share or await the active operation rather than start a competing refresh. |
| Completion belongs to a prior page load or account | Do not let it replace the current page. Use a load-generation check before applying results to the UI. |

`SqliteCatalogRepository.ReplaceLiveSnapshot` commits each complete snapshot in a transaction. After refresh, reload from SQLite so the page displays the committed snapshot. Keep concurrency coordination and the generation check in the page code-behind for this increment; no repository, refresh-service, contract, or provider edits are planned. The cache-first behavior depends on retaining cached rows and retrying refresh, not on classifying why refresh failed.

### Follow-up boundary: provider error taxonomy

HTTP 401/403 classification is a separate follow-up increment. It is not required to make catalog loading cache-first, preserve cached rows, or retry refresh. Until that work lands, use the neutral full-page and inline messages above for all no-cache and refresh failures; preserve the current authentication flow and never label a generic failure as specifically offline or access-denied.

The follow-up owns `Tvivo.Core/Contracts.cs` and `Tvivo.Infrastructure/XtreamInfrastructure.cs`, plus both current `AuthenticateAsync` callers: `CatalogLandingPage.LoadSavedAsync` and `ProviderSetupPage`. It must add an explicit access-denied result for HTTP 401/403, retain `NetworkFailure` for transport/timeouts, and preserve existing `InvalidCredentials`, `AccountInactive`, `AccountExpired`, `ProtocolError`, and `Unknown` behavior. Catalog refresh HTTP errors must remain distinguishable by status to its caller. Update/add focused `InfrastructureTests` for 401, 403, transport failure, and unchanged credential/account/protocol mappings; cover both caller recovery messages and cached-refresh retention in app-level tests. Do not fold that taxonomy into this layout/cache-first change.

### Initial cache-read responsiveness

The initial cache read must run away from the UI dispatcher and query only the groups plus the first 100 channels needed for the initial page; do not materialize the full catalog. With a populated 48,780-channel SQLite fixture, measure from each `LoadAsync` call to the first cached-content render over 20 warm-cache page-load trials: p95 must be at most 500 ms, and a 50 ms dispatcher heartbeat must have no gap greater than 100 ms during those reads. Keep the loading state visible until that first render. These thresholds define the acceptance target; they are not claims about an unmeasured current build.

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
- At 1,000 epx client width, the outer shell is 224 epx, the catalog keeps 52 epx side padding and a 28 epx pane gap, the group pane is at least 200 epx, and the channel pane is at least 432 epx. The page-status slot is at least 216 epx, pager buttons are at least 96 epx each, and both controls and status remain visible without horizontal or page scrolling. Confirm the group column contracts before the channel column drops below its minimum. Below 1,000 epx is unsupported by this increment.
- On a populated 48,780-channel fixture, 20 warm-cache `LoadAsync` runs meet p95 first-cached-render latency <=500 ms and a 50 ms dispatcher heartbeat has no gap >100 ms. Confirm the catalog query runs off the UI dispatcher and retrieves only the first page.
- Native controls use the dark theme and match the existing palette in the supported standard dark presentation. Select a Windows Contrast theme once and record the known limitation: fixed dark palette behavior is not guaranteed and this is not a High Contrast pass; do not claim High Contrast support.
- With cached data, simulate a refresh failure and confirm rows remain usable with the neutral “Couldn’t refresh. Showing saved channels” status and Retry, regardless of failure category.
- With no cache, simulate authentication and refresh failures and confirm the neutral “Couldn’t load the catalog. Check your connection and provider details, then retry” state and existing Home → Set up provider route; confirm the message does not assert a specific cause.
- Existing group, search, paging, double-tap activation, fixture, and playback-routing behavior remains intact.

These are implementation acceptance checks. No build, test, or launch was performed as part of this design plan.

## Rollback boundary

Use baseline commit `a0e1cc1903879b07b9d44e7f506713f1962c23b9`. If the increment is rejected, revert only `CatalogLandingPage.xaml` and `CatalogLandingPage.xaml.cs` to that baseline. No other files are in the planned change set.

## Design-skill review

Reviewed with the installed `winui-design` skill v0.6.1 at `C:\Users\Dell\.codex\plugins\cache\microsoft-winui\winui\0.6.1\agent-plugin\skills\winui-design\SKILL.md`, including its data-page layout and theme/accessibility references. The Grid/ListView layout follows its platform-control guidance. High Contrast remains an explicit, bounded deferral as specified above.
