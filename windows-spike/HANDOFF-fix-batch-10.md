# Fix batch 10 handoff

Source and handoff edits only. No build, test, launch, install, playback, or live verification was performed.

## VERDICT

The three My Tvivo concepts are now queried and rendered independently:

- **Recently added** uses the provider catalog's `added_at` timestamp, including Movies, Series, and Live TV, ordered newest first across types.
- **Recently played** uses visit history (`visit_count > 0`) and orders by `last_tuned_at` descending, independent of provider add dates.
- **Favorites** uses an account/type/item keyed SQLite list, and orders by the time the user favorited each item.

Schema 15 creates the favorites table on new databases and migrates existing schema 14 favorites from the prior `items.favourite` fields. The player side list now has an outline/filled star button; filled stars use the accent brush. Movie and Live TV lists include the current item and sibling items from the selected shelf. Episode rows represent their parent Series favorite because episodes are provider details rather than catalog rows. The Favorites shelf consequently contains the playable Series catalog item.

The category-switch redraw cause is in readiness handling: switching categories while retaining the old snapshot called `SetInteractionReadiness(false)`, disabling the shell search and category sort controls until the new snapshot arrived. WinUI can repaint their disabled/enabled templates during that interval. The old snapshot surface already blocks pointer input, so the readiness toggle is no longer applied for that retained-snapshot transition. Sort selection is also global across catalog modes instead of being restored from per-mode state, avoiding a selection mutation as a category changes. The controls are static XAML elements and are not recreated or rebound to a replacement ViewModel/DataContext in the inspected path.

## STRONGEST EVIDENCE

- `PrepareMode` retained `ContentState` for category switches but also called `SetInteractionReadiness(false)`. That method disables `SearchBox`, `CategorySortBox`, and the shell search via `InteractionReadinessChanged`; applying the new snapshot later re-enabled them. The category transition does not replace the `CatalogLandingPage` instance.
- `TopSearchBox` is declared once in `MainWindow.xaml`, outside catalog content. `CategorySortBox` is declared once in `CatalogLandingPage.xaml`, outside `ContentState`. Neither control has a category-specific `ItemsSource` or DataContext binding. `SetSort` previously changed selected items when restoring category state; that restoration was removed and the sort selection is now shared.
- `ReplaceSnapshot` stores the provider's `Channel.AddedAt` value in `items.added_at`. The cross-type added query orders by this column descending and omits missing/zero timestamps. Visit history and favorite membership each use their own persisted data and ordering.
- Favorite state is re-read when the player list is populated or reused. Episode favorite keys resolve to `(Series, seriesId)`; movie/live rows resolve to their catalog type and id.
- Added tests cover provider-timestamp ordering across catalog types, separate added/played/favorite result sets, toggle persistence across repository reopen, and schema 14 favorite migration. The tests were not run.

## EXACT BOUNDARY

- `src/Tvivo.Infrastructure/SqliteCatalogRepository.cs` — schema 15 migration, legacy favorite transfer, separate added/played/favorite queries, toggle/state methods.
- `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` — three My Tvivo shelves, complete mixed-type shelf paging, favorite snapshot invalidation, stable readiness and sort selection across category transitions.
- `src/Tvivo.App/MainWindow.xaml` and `src/Tvivo.App/MainWindow.xaml.cs` — per-row star UI, favorite status loading, episode-to-series key mapping, persistence toggle.
- `tests/Tvivo.Infrastructure.Tests/SqliteCatalogRepositoryTests.cs` — added ordering, separate shelf criteria, persistence/toggle, schema migration coverage.
- `HANDOFF-fix-batch-10.md` — this report.

No provider contract, stream URL construction, playback selection, credential storage, or unrelated catalog behavior was changed.

## RULED OUT

- The observed search/sort flicker is not explained by either control being recreated from a category-specific DataContext or ItemsSource: both controls are static and have no such binding in the XAML path reviewed.
- The category snapshot opacity fade targets `ContentState`, which does not contain the top search box or sort combo. Their identified transition-side visual state changes came from readiness disabling and category-specific sort restoration.
- Recently added is no longer inferred from visit activity or alphabetical catalog order. Recently played is no longer sorted primarily by visit count in My Tvivo.

## UNCERTAINTY

- Runtime WinUI repaint behavior has not been observed after this change. Other theme/visual-state causes may remain if flicker persists.
- Mixed-type My Tvivo shelf paging loads the matching ordered set before slicing into pages; this is source-verified but not performance-measured on a large provider catalog.
- The episode star represents the parent series. Runtime confirmation is needed that the owner expects series-level favorites from episode rows.
- SQL migration, XAML binding, and new tests have not been compiled or executed in this environment.

## NEXT GATE

On the next explicitly authorized live verification:

1. Switch rapidly among My Tvivo, Movies, Series, and Live TV while observing the shell search and sort combo; confirm their enabled appearance and selection remain steady.
2. Type in the shell search during a category transition and confirm the new category receives the query without a visual reset.
3. Inspect Recently added across all three types; confirm provider add timestamps determine descending order, including when visit history differs.
4. Visit two items in different orders and confirm Recently played uses last-played time, independently of visit count and add date.
5. Favorite and unfavorite movie and Live TV shelf siblings and a series episode; confirm accent/outline state, persistence after reopening, and parent-series behavior for episodes.
6. Open Favorites and page through it; confirm it includes all favorited Movies, Series, and Live TV entries and excludes removed favorites.
7. Run the focused Infrastructure tests, then the normal solution build. Neither was run in this batch.
