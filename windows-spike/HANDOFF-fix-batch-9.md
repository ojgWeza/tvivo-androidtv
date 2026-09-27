# Fix batch 9 handoff

Code and handoff edits only. No build, test, app launch, install, playback, or live verification was performed, as requested.

## VERDICT

The five reports trace to three code defects plus one already-correct layout boundary:

- Artwork hover/rebind entered `StartArtwork`, which always called `StopArtwork` before checking the LRU. `StopArtwork` cleared the image source and hid the bitmap even when that exact item, URL, and decoded bitmap were still applied. The page now records the applied item/URL/bitmap by image and returns early when all three still match; a stale or different source continues through the normal load path.
- My Tvivo recent shelves were ordered by committed `RecordVisit` data, but the page kept a 15-minute `CatalogSnapshot` for My Tvivo. Returning from playback reused that old snapshot. The write itself uses SQLite autocommit (`ExecuteNonQuery` followed by connection disposal). After each movie, live, or series visit write, the page now invalidates My Tvivo snapshots so the next catalog load reads the updated ordering.
- Snapshot applications were serialized only when the mode changed. Same-mode requests could render concurrently, and a stale category transition could finish its opacity animation after a newer navigation request. All snapshot applications now share the gate, recheck their request generation inside the gate, and only complete fades while their transition generation remains current. Fade-out continues from the current opacity so a superseded transition does not flash the old content back to full opacity.
- The shell search control was already outside the category swap/fade targets: `TopSearchBox` is in `MainHeader`, while category transitions animate only `ContentState`. The category page itself remains attached. No layout change was needed for this item.
- The player pane used generic headings. Episode lists now use the loaded series title; movie sibling lists use the movie genre when available and “More like this” as the fallback.

## STRONGEST EVIDENCE

- `StartArtwork` previously began with `StopArtwork(image)`. That method removed the load, set `Image.Source = null`, and set opacity to zero before the cache lookup. The new applied-artwork check runs before that reset and requires the source reference, item key, and artwork URL to match.
- `SqliteCatalogRepository.RecordVisit` runs `UPDATE items SET visit_count=visit_count+1,last_tuned_at=$now ...` and executes the command on a short-lived open connection. Existing `SqliteCatalogRepositoryTests.My_Tvivo_order_uses_visit_count_then_letters_digits_symbols_fallback` already checks the ordering change and that it survives `ReplaceSnapshot`.
- `LoadCatalogAsync` used `TryGetCachedSnapshot` before reading from SQLite; My Tvivo cache entries had no invalidation on playback return. The new notification removes only the account's My Tvivo entries.
- `ApplySnapshotAsync` previously acquired `_modeTransitionGate` only after deciding that the mode changed. Its delayed fade completion did not check whether a newer navigation had incremented `_modeTransitionGeneration`. The updated path gates every apply, checks the caller's load/query generation after acquiring the gate, and checks the transition generation before finalizing a fade.
- `MainWindow.xaml` places `TopSearchBox` in `MainHeader` (row 1); `CatalogLandingPage.xaml.cs` applies category opacity only to `ContentState`. The shell search is not inside the category-swapped/faded visual.
- Pane title selection now has focused tests in the Core and App test projects. Artwork reuse identity has a focused policy test in the App test project.

## EXACT BOUNDARY

- `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` — applied-artwork reuse; My Tvivo snapshot invalidation notification; serialized, generation-checked snapshot application and fade continuation.
- `src/Tvivo.App/MainWindow.xaml.cs` — notify the catalog after visit writes; carry the series title into the episode pane; choose movie genre/fallback pane title.
- `src/Tvivo.Core/Contracts.cs` — pure player-side title resolver.
- `tests/Tvivo.App.Tests/AppSmokeTests.cs` — artwork reuse policy test.
- `tests/Tvivo.Core.Tests/CoreSmokeTests.cs` — side-pane title tests.
- `HANDOFF-fix-batch-9.md` — this report.

No schema, repository write SQL, provider behavior, URLs, playback selection, shell-search layout, or XAML template was changed. The shell-search requirement was already met by the existing tree.

## CONFIDENCE

- Artwork reset/reload cause and source-identity fix: high; directly visible in the previous call order. Runtime confirmation is pending.
- My Tvivo stale-cache cause and invalidation after visit: high; cache-first read path and write call sites are explicit. Runtime return flow is pending.
- Nondeterministic redraw race: moderate to high; unsynchronized same-mode application and unguarded fade completion were present. The gate/generation fix is source-verified but animation behavior is untested.
- Search persistence boundary: high; XAML and fade target place the shell control outside category content. The symptom itself was not reproducible in this source path.
- Pane-title selection: high for the selected labels and fallback logic; actual provider genre availability varies.

## RULED OUT

- `RecordVisit` does not hold an uncommitted transaction open. SQLite autocommit applies to this single `UPDATE`, and the command/connection are disposed before playback navigation finishes.
- The artwork symptom does not require an LRU miss: the unconditional source clear happened before cache lookup. The source-identity shortcut now also works if the image bitmap has been evicted from the LRU.
- The global search bar is not a child of `ContentState` and is not recreated by category snapshots. `MainHeader` remains outside both category fades and catalog page swaps.
- The database visit-order query and existing ordering test already cover visit count and persistence across catalog refresh replacement. This batch addresses stale UI snapshot reuse rather than changing the ordering SQL.

## UNCERTAINTY

- Source review cannot confirm the runtime's exact hover event order or prove that every reported flicker came through `DataContextChanged`/`Loaded`; the skip handles either callback when the image still carries the matching applied bitmap.
- The source confirms a stale-snapshot path and invalidates it after playback visit writes, but return-to-screen freshness has not been exercised in the app.
- Transition gating and generation checks remove identified concurrent-apply paths, but the animation and rapid-navigation behavior need live UI verification.
- Movie grouping has the genre metadata when the provider supplied it. If genre is absent, the intentionally generic fallback is “More like this”; the app does not currently pass a display category name into the player event.
- The WinUI-backed artwork path has not been built or exercised. The new reuse policy test was added but not run.

## NEXT GATE

On the next explicitly authorized live verification:

1. Open several Movies/Series cards with visible artwork. Move pointer focus/hover across already-painted cards and confirm no placeholder flash; verify a genuinely rebound item still loads its own artwork.
2. Play a new movie and a series episode, return to My Tvivo, and confirm each newly visited item moves according to visit ordering without waiting for the 15-minute snapshot freshness interval.
3. Rapidly alternate Movies, Series, Live TV, and My Tvivo using both shell and page navigation. Confirm there is one deterministic fade/swap sequence, no blank flash, and no older category appears after the latest selection.
4. Change category while watching the shell search field. Confirm the same control remains mounted and its focus/caret do not reset; check the intended per-mode query behavior.
5. Open a series and confirm the side heading shows its name. Open a movie with genre metadata and confirm that genre heads the sibling list; open one without it and confirm “More like this.”
6. Run the focused App/Core tests and the normal build only when execution is authorized. This batch deliberately did not run them.
