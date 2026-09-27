# Fix batch 13 handoff

Code and handoff edits only. No build, test, launch, install, or live verification was performed. No Python was used.

## VERDICT

The favorite buttons now use a 40×40 dp target with a 22 dp star, exceeding the existing 36×36 dp info button. The disabled Compact title-bar button was removed; source search found no matching code-behind field or reference. The first-snapshot transition now hides content before binding and fades it in after the content is attached and laid out. A focused policy test was added but not run.

The source-level first-snapshot gap is corrected. I am not claiming the reported flicker is fixed: the exact cold-start timing has not been observed live, and the prior batch's layout-measurement attempt did not resolve the user's report.

## STRONGEST EVIDENCE

- `MainWindow` constructs one `CatalogLandingPage` (whose constructor calls its own `InitializeComponent`) before the window's `InitializeComponent`, assigns it to `CatalogPageHost` after the window XAML loads, and immediately starts `ShowCatalogAsync(MyTvivo)`. `ShowCatalogAsync` calls `ShowPage`, `PrepareMode`, then awaits `LoadSavedAsync` when the account is not yet known. The catalog area is initially collapsed; showing it has a separate 150 ms shell fade.
- Movies, Series, Live TV, and My Tvivo all select modes on this same page instance. Their `MainWindow` click handlers map to `ShowCatalogAsync` with the corresponding mode. When a category is selected while already on Catalog, `ShowPage` returns because the shell page did not change; `PrepareMode` and `ApplySnapshotAsync` own the content transition.
- `LoadSavedAsync` clears `ShelvesItems.ItemsSource` and `_renderedSnapshot` before awaiting credential load and provider authentication. A category click during this interval calls `PrepareMode`; with no rendered snapshot, that path sets `_showModeLoadingFrame`, clears the shelf sources, and enters loading state.
- `LoadCatalogAsync` may asynchronously delay 32 ms, reads the catalog using `Task.Run`, and then calls `ApplySnapshotAsync`. The catalog read does not synchronously block UI layout.
- Before this change, `ApplySnapshotAsync` faded out only when `_renderedSnapshot` existed and its mode differed. Its fade-in was guarded by `fadedOut`. With no previous snapshot, it attached `ShelvesItems.ItemsSource`, made `ContentState` visible, and called `UpdateLayout`, but neither fade ran. The first ListView shelf realization can instantiate its nested GridView and card templates on that attach/layout path.
- The no-snapshot path now sets the `ContentState` visual opacity to zero before updating/binding the snapshot. After `UpdateLayout` completes, the common 110 ms incoming fade runs. The pure policy test covers first content, changed mode, and unchanged mode decisions.
- The page XAML has no root `Loaded` or `SizeChanged` handler. `ContentState` starts collapsed and `LoadingState` starts visible. Its `Loaded` handlers are for artwork images and realized shelf/grid templates. There is no `ItemsRepeater`; the relevant controls are `ListView`, nested shelf `GridView`, and the full-list `GridView`. No `DispatcherQueue` hop is used by the catalog load/apply path; the explicit queue uses found in this page belong to shelf pointer handling.
- Top-nav logo and My Tvivo both call `MyTvivoNavigation_Click`, so they reach the same `ShowCatalogAsync(MyTvivo)` path. Subsequent swaps with an existing snapshot still use the outgoing fade, bind/layout, then incoming fade.

## EXACT BOUNDARY

- `src/Tvivo.App/MainWindow.xaml` — remove the dead Compact button; enlarge the series-title and player-list favorite buttons to 40×40 with 22 dp glyphs.
- `src/Tvivo.App/CatalogUiPolicies.cs` — add the pure incoming-transition decision used for the initial snapshot.
- `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` — hide the no-snapshot content surface before binding; fade it in only after application/layout succeeds; clarify the layout comment.
- `tests/Tvivo.App.Tests/AppSmokeTests.cs` — cover initial, changed-mode, and unchanged-mode transition decisions.
- `HANDOFF-fix-batch-13.md` — this report.

## CONFIDENCE

- Favorite size and Compact removal: high source-level confidence. The info button is 36×36; the favorite targets are now 40×40. `CompactWindowButton` had only its XAML declaration and no code-behind reference.
- Transition branch diagnosis: high confidence that the no-rendered-snapshot code path previously skipped both fades, based on the exact predicate and finalization guard.
- Symptom attribution and resolution: moderate confidence. Startup starts My Tvivo loading immediately, and that path clears `_renderedSnapshot` while credential/auth/catalog awaits are pending. A category click in that interval reaches the diagnosed no-snapshot branch. If the user waits for My Tvivo to finish first, the next category change has a retained snapshot and already uses the fade path; that timing would point to an additional deferred child-layout effect instead. Only live reproduction can establish which case matches the reported clicks.

## RULED OUT

- The Compact control has no backing field or code-behind reference to remove.
- The initial catalog query is not a synchronous UI-thread database query: it goes through `Task.Run`, with an awaited delay when `_showModeLoadingFrame` is set.
- No page-level `Loaded`/`SizeChanged` handler or explicit catalog `DispatcherQueue` post was found that resizes the shell during the snapshot path. Template realization still happens as `ItemsSource` is attached and measured.
- A prior snapshot is not guaranteed during startup: `LoadSavedAsync` explicitly clears it while loading credentials and authenticating.

## UNCERTAINTY

- The reported behavior cannot be tied conclusively to the no-snapshot branch without live timing evidence. If My Tvivo is fully rendered before the first click, `PrepareMode` retains that snapshot and `ApplySnapshotAsync` already fades the mode swap; an asynchronous child realization after that fade begins could still cause a visible adjustment.
- `UpdateLayout` does not prove every nested virtualized child has completed all deferred layout work. The new opacity-zero interval protects the first snapshot attach path, but the 110 ms reveal needs visual verification with populated and empty categories.
- The added test and XAML/C# compilation were not run.

## NEXT GATE

1. Build and run the focused App test suite when execution is permitted; confirm the added transition policy test and XAML compile.
2. From a cold start, click Movies, Series, Live TV, and the Tvivo/My Tvivo logo while the initial catalog is still loading; confirm first content appears through a smooth reveal without size jumps.
3. Repeat after My Tvivo has finished rendering. If a first-per-category resize remains, capture its timing and inspect nested virtualized child layout after the fade, since that is outside the diagnosed no-snapshot branch.
4. Verify the 40×40 favorite hit targets and glyph size in both the series header and player related-item list.
