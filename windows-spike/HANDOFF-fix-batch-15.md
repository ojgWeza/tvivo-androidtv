# Fix batch 15 handoff

Code and handoff edits only. No build, tests, launch, install, emulator, or live verification was performed. No Python was used.

## VERDICT

Batch 14's player-to-catalog opacity mask/layout barrier was removed because live testing confirmed it did not fix the Back flicker. The flicker's mechanism is **not established** from source inspection. This batch adds timestamped transition instrumentation; a live run and its `tvivo-launch.log` are required before a real flicker fix can be chosen. No replacement visual-transition theory is marked fixed.

The folder/category title, full-category player list, rating formatting, navigation tooltip suppression, and Spotlight arrows are implemented in source. These changes are also unbuilt and unverified at runtime.

## STRONGEST EVIDENCE

- `ShowPage(Player → Catalog)` calls `StopPlaybackForNavigation()` synchronously before switching visibility. That clears player UI state and calls `SetCinemaMode(false)`. Cinema exit changes the XAML player layout, restores the saved native window state using `SetWindowPlacement`/`SetWindowPos`, and then returns to `ShowPage`; `ShowPage` collapses `PlayerPage`, makes `CatalogPageArea` visible, and starts its 150 ms fade in the same synchronous call stack.
- `StopBothPlaybackEnginesAsync()` is started after the page-state cleanup and returns at its first incomplete `await`, so native playback-surface teardown may complete after `PlayerPage` is collapsed. Source inspection cannot show which of these operations corresponds to the user's visible flash.
- The new `BACK-TRACE` entries include UTC wall-clock and monotonic elapsed timestamps for Back/Escape entry, `ShowPage`, the player stop, cinema layout/window restore, player-collapse and catalog-visible assignments, fade changes, XAML size changes, playback-surface unload/visibility, and engine-stop start/completion. A temporary `CompositionTarget.Rendering` handler records the first 12 rendered frame states, including catalog/player visibility and opacity, the video surfaces, the curtain, and root size. `WindowStateController` separately timestamps native placement and `SetWindowPos` calls. Microsoft documents `CompositionTarget.Rendering` as firing when the core rendering process renders a frame ([Microsoft Learn](https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.ui.xaml.media.compositiontarget.rendering?view=windows-app-sdk-2.0)).
- The player side-pane title previously came from the selected movie's genre or the generic `Live TV` label. Movie and live selections now carry their `GroupId` into a category-name lookup, which updates the title before any full-list query finishes. For movies, the list reads all pages with the same `GetChannels` query used by the paged folder listing; the exact group display name comes from the stored category rows.
- Shelf click events pass the realized shelf cards as `RelatedChannels`; shelf previews are capped at 12. The new player query uses the clicked movie's category ID and is independent of the shelf/Spotlight event list.
- The reachable Git history search found no earlier Spotlight previous/next controls. The restored arrows use the same candidate set as the existing eight-second Spotlight rotation.

## EXACT BOUNDARY

- `src/Tvivo.App/MainWindow.xaml.cs` — remove batch 14's barrier path; instrument Back/Escape, `ShowPage`, resize/layout, fade, surface state, and playback stop; load the player's movie side pane from the selected item's full category.
- `src/Tvivo.App/WindowStateController.cs` — timestamp native window mode, placement, and bounds calls.
- `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` — expose the category-name and full-category queries; restore manual Spotlight cycling; format shelf rating labels.
- `src/Tvivo.App/Pages/CatalogLandingPage.xaml` — add Spotlight previous/next controls.
- `src/Tvivo.App/MainWindow.xaml` — suppress tooltips on the navigation/header controls.
- `src/Tvivo.Infrastructure/SqliteCatalogRepository.cs` — query a whole category in bounded pages.
- `src/Tvivo.Core/Contracts.cs` — shared rating formatter, rounding to the nearest 0.5 and omitting `.0` for whole values (`7.234 → 7`, `6.74 → 6.5`).
- `tests/Tvivo.Core.Tests/CoreSmokeTests.cs` — formatter cases added, not run.
- `tests/Tvivo.Infrastructure.Tests/SqliteCatalogRepositoryTests.cs` — 37-item category paging case added, not run.
- `src/Tvivo.App/CatalogUiPolicies.cs` and `tests/Tvivo.App.Tests/AppSmokeTests.cs` — remove batch 14's now-invalid barrier policy and test.
- `HANDOFF-fix-batch-15.md` — this report.

## CONFIDENCE

- Back flicker cause: low; unresolved until the new logs and live frame behavior are observed.
- Synchronous Back call ordering and instrumentation coverage: high from source inspection; event timing and whether a recorded frame matches the visible flash are unverified.
- Category lookup and paged query path: high from source; the new repository test was not run.
- Rating, tooltip, and Spotlight changes: implemented in source; compilation and live behavior were not checked.

## RULED OUT

- Batch 14's mask-then-fade ordering as a sufficient fix: user live testing confirmed it did not resolve the symptom in windowed or cinema-mode Back.
- Shelf `RelatedChannels` as a complete movie category: source shows it is the realized shelf-card list, with shelves limited to 12.
- Raw movie rating strings being displayed unchanged in the player metadata line and shelf-card details: both display paths now call `RatingDisplayFormatter`.
- An explicit timestamp tooltip declaration in the current App XAML/C# source: none was found by source search. Null tooltip values are now explicitly assigned to all top-header navigation, search, account, and engine-selector controls. The tooltip's origin still needs a live hover check.
- An earlier Spotlight arrow implementation in reachable history: searches of the relevant XAML/C# history found none; controls were restored based on the current Spotlight candidate/rotation behavior.

## UNCERTAINTY

- `ShowPage` changes the player and catalog visibility sequentially in one UI-thread call. Whether a blank frame is actually presented between those mutations cannot be determined without the compositor callback trace and live observation.
- Cinema restore may expose native window compositor timing; the async player backend stop may tear down a video surface after the shell page switch; or the shell visibility/fade may be involved. These remain hypotheses, not findings.
- The user's tooltip may come from a runtime/system source not represented by a XAML tooltip declaration. Verify after the explicit null values are present in a live build.
- The folder title request was interpreted as the player page's right-side pane title: the paged category grid header already assigns `OpenShelfTitle.Text = openShelf.Title`. The player pane now shows the exact category display name after a short asynchronous lookup for both movie and live selections.
- No compilation or tests were run, so XAML/API or build errors remain possible.

## NEXT GATE

1. Build and launch when permitted, then inspect `%LOCALAPPDATA%\Tvivo\tvivo-launch.log` for a single `BACK-TRACE` ID and the adjacent `WINDOW-STATE` timestamps.
2. Reproduce Back from a normal windowed placement and from cinema mode. Compare `SetWindowPos`/`SetWindowPlacement`, `xaml-size-changed`, `show-page-visibility`, `showpage-fade-*`, video surface stop events, and the 12 frame samples with the visible flash. Capture a screen recording/frame if the visual flash does not line up with logged XAML state.
3. Use that evidence to choose a fix at the layer that flashes; repeat both Back cases live before declaring the flicker fixed.
4. Verify the folder title and a category with over 12 movies from shelf preview, full listing, and Spotlight; confirm list selection still works across loaded pages.
5. Verify ratings in player and shelf cards, hover the full navigation bar for a tooltip, and cycle Spotlight both directions. Run the added focused tests and build when execution is allowed.
