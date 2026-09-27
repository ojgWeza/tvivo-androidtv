# Fix batch 11 handoff

Source and handoff edits only. No build, test, launch, install, playback, or live verification was performed, as requested.

## VERDICT

Recently added, Recently played, and Favorites are now single-type shelves everywhere. Each Movies, Series, and Live TV catalog screen puts its matching three activity shelves before the existing all-items and category shelves. Opening an activity shelf pages the same type-filtered result set shown in its preview. My Tvivo uses nine fixed shelf definitions ordered by concept (Added, Played, Favorites) and then Movies, Series, Live TV. These nine rows remain present when a result set is empty.

Typed repository queries now accept the same optional title filter as the cross-type queries and retain their existing activity-specific ordering. Visit and favorite changes invalidate cached snapshots for every catalog mode so the category shelves refresh along with My Tvivo.

## STRONGEST EVIDENCE

- `SqliteCatalogRepository.GetRecentlyAdded`, `GetRecentlyPlayed`, and `GetFavorites` all route through `GetOrderedItems` with an explicit account and type predicate. That query applies the optional escaped title filter before ordering and limiting.
- My Tvivo's nine definitions are an ordered, app-test-visible list. Shelf rendering and opening both use each definition's `CatalogItemType`; no shelf combines multiple types.
- Category pages derive their one type from the active catalog mode. Their activity previews and opened pages call the typed repository overloads, before the existing all-items and provider-category rows.
- Repository tests now cover per-type results for all three queries, provider timestamp / visit / favorite ordering, title filtering, and the nine My Tvivo titles and type sequence. Tests were added but not run.

## CONFIDENCE

High for the source-level type mapping, shelf order, query wiring, and intended paging path. Runtime and test confidence remains unestablished because no build or execution was performed.

## EXACT BOUNDARY

- `src/Tvivo.Infrastructure/SqliteCatalogRepository.cs` — optional type-query title filters; existing query order is retained.
- `src/Tvivo.App/Pages/MyTvivoShelfDefinitions.cs` — nine stable single-type shelf definitions and required order.
- `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` — category activity shelves and paging, nine My Tvivo shelves, and snapshot invalidation after visits/favorite changes.
- `tests/Tvivo.Infrastructure.Tests/SqliteCatalogRepositoryTests.cs` — per-type filtering and order coverage.
- `tests/Tvivo.App.Tests/AppSmokeTests.cs` — nine-row title and type-order coverage.
- `HANDOFF-fix-batch-11.md` — this report.

No provider contract, schema, stream construction, playback path, credential storage, XAML layout, or unrelated catalog behavior was changed.

## RULED OUT

- No shelf depends on view-layer filtering to remove other content types. Type filtering is part of the repository query.
- The old category-page `Recently added` label no longer describes an unfiltered catalog-order preview; that existing preview is labeled `All movies`, `All series`, or `All Live TV` while the actual Recently added shelf is query-backed.
- No shelf combines Movies, Series, and Live TV. My Tvivo definitions each carry one type, and category shelves use the page's one catalog type.

## UNCERTAINTY

- Build and test compilation, SQL execution, XAML integration, shelf rendering, and keyboard/pointer interaction were not verified because execution was explicitly out of scope.
- Category activity shelves are omitted when their typed result set is empty. My Tvivo always retains all nine named rows as requested.
- Activity query APIs return lists rather than count-plus-page results, so shelf construction reads the full matching type-specific list to calculate counts and retain ordering before taking the 12-card preview. Large-catalog performance was not measured.
- The placement of activity shelves above all-items and provider-category shelves is source-level only; live visual density and scrolling remain unobserved.

## NEXT GATE

At the next authorized verification:

1. Run the focused Infrastructure and App test projects; confirm typed query isolation and order, and the exact nine My Tvivo titles/types.
2. Open Movies, Series, and Live TV with data in all three types; confirm each activity shelf contains only the page's type and appears above the all-items/category shelves.
3. Open each category activity shelf and confirm its full paged listing preserves the same type, title filter, and query ordering as the preview.
4. Confirm My Tvivo displays the nine rows in Added → Played → Favorites, each Movies → Series → Live TV, including empty shelves.
5. Record a visit and toggle a favorite, then revisit the relevant My Tvivo and category screens to confirm their cached activity shelves refresh.
