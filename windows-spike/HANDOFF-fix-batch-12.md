# Fix batch 12 handoff

Source, test, design-rule, and handoff edits only. No build, test, launch, install, or live verification was performed, as requested.

## VERDICT

Implemented the six requested source changes:

- Empty My Tvivo shelves are filtered out; category activity shelves already used a zero-result guard, and all-items/category rows are only created when their queries return items. No shelf renders an empty-state placeholder.
- Recently Added is capped to 50 items per type, ordered by `added_at DESC, id ASC`. The type-specific repository method enforces the cap even when callers request `int.MaxValue`, so shelf previews and opened listings share the same result boundary in Movies, Series, Live TV, and My Tvivo.
- Full folder listings use 99 items per page. The current reported grid is nine columns at the usual width, so 99 makes eleven complete rows. I chose the permitted fixed multiple over runtime-derived columns; the actual runtime width/column count was not re-measured in this edit-only batch.
- Series episodes no longer show individual favorite stars. A single star beside the series title toggles the parent series favorite; movie and live item stars remain in the list.
- Favorite stars use a gold filled state and muted outline state. Orange remains the selected/now-playing background. This distinction is recorded in `DESIGN-RULES.md`.
- Snapshot application now runs `ContentState.UpdateLayout()` after attaching the new view and setting its visible state, before the new content fades back in. This forces first-use `ItemsWrapGrid` measurement during the transition and is intended to prevent the first Movies/Series click from exposing its resize.

## STRONGEST EVIDENCE

- Both Recently Added repository overloads now clamp every requested limit to 0–50 per type and retain deterministic newest-first ordering with an ID tie-breaker. The cross-type overload merges up to 50 results from each type into global added-date order.
- `BuildMyTvivoShelves` drops shelf models whose preview has no cards. Since the preview is built from the query result, a zero-item query produces no shelf at all. `AddActivityShelf` already omits empty category activity shelves.
- `CatalogGridPaging.PageSize` is 99 and is used by the open listing and pager calculations.
- `FavoriteTargetResolver` routes an episode to its `seriesId`, reports no per-item star for episodes, and allows per-item stars only for movie/live sources. The header action reads and toggles the series key.
- Tests now cover the 50 newest items and ordering separately for all three types, empty-shelf visibility policy, 99/9 page-size math, and series-parent favorite routing. Tests were added but not run.

## EXACT BOUNDARY

- `src/Tvivo.Infrastructure/SqliteCatalogRepository.cs` — per-type Recently Added hard cap and default.
- `src/Tvivo.App/CatalogUiPolicies.cs` — fixed grid page size, empty-shelf visibility policy, and favorite-target/display policy.
- `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` — 99-item paging, empty My Tvivo filtering, and transition-time layout measurement.
- `src/Tvivo.App/MainWindow.xaml` and `src/Tvivo.App/MainWindow.xaml.cs` — series-title favorite action, per-episode star hiding, target routing, and distinct star brush.
- `src/Tvivo.App/App.xaml` — gold favorite brush.
- `DESIGN-RULES.md` — favorite/selection color distinction.
- `tests/Tvivo.Infrastructure.Tests/SqliteCatalogRepositoryTests.cs` — per-type Recently Added cap/order coverage.
- `tests/Tvivo.App.Tests/AppSmokeTests.cs` — shelf visibility policy, grid math, and favorite routing coverage.
- `HANDOFF-fix-batch-12.md` — this report.

## CONFIDENCE

High for the query limit, deterministic ordering, shelf-filter predicate, page-size wiring, and favorite target/display source paths. Medium for the flicker correction: the first-time layout-measurement path is addressed in code, but the reported visual symptom cannot be confirmed without launching the WinUI app.

## RULED OUT

- Open Recently Added pages do not bypass the 50-item cap by asking the repository for `int.MaxValue`; the repository clamps the type-specific query itself.
- Empty My Tvivo definitions are not retained as named zero-card rows. Category activity shelves continue to pass through their existing empty-result guard.
- Episode favorite storage still targets the parent series ID, but episode rows no longer expose their own star control.
- Favorite state no longer shares the orange selection color; selected rows remain orange while a favorite star is gold.

## UNCERTAINTY

- No build or tests were run, so compilation, XAML binding validity, SQL execution, and test results remain unverified.
- No runtime column measurement was possible. The chosen fixed page size is 99 based on the reported nine-column layout; a different window width can render a different column count.
- No live UI check was performed for My Tvivo/category shelf visibility, star placement/colors, paging, or first-click category transitions. The `UpdateLayout()` fix is based on source inspection of the first-time `ItemsWrapGrid` attachment path and needs visual confirmation.

## NEXT GATE

1. Run focused Infrastructure and App tests; verify all three independent top-50 sequences, empty-shelf policy, page-size math, and parent-series favorite routing.
2. At the normal app width, open a folder and confirm 99 items form eleven full nine-column rows; inspect a second width to determine whether the fixed multiple still matches the rendered column count.
3. Open Movies, Series, Live TV, and My Tvivo with empty and populated activity results; confirm zero-result shelves are absent and no placeholder row appears.
4. Open a series: confirm one gold/outline star by the series title and no stars beside episodes; verify movie/live stars remain per-item and the orange selected row remains visually distinct.
5. After app start, click Movies and Series for the first time in the session; confirm no visible resize/redraw flicker. Repeat category changes to check state retention.
