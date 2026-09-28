# Fix batch 19 handoff

Source, test, and handoff edits only. No build, test, app run, launch, install, emulator, or Python was used.

## VERDICT

- **Player to catalog navigation — invariant strengthened in source; high confidence no call site can omit caller intent, but the flicker cause is not reproduced by source inspection.** At the start of this batch, `ShowCatalogAsync` already inferred Player origin from `_currentPage`, despite the four navigation handlers not passing the flag. The flag was then passed into `ShowPage`. This batch removes the flag from both method signatures and moves the actual check into `ShowPage`, where it captures the current page before overwriting it. Every Player-to-Catalog transition applies the opacity-zero layout barrier and existing catalog fade-in. The reported runtime flicker still needs live confirmation.
- **Open-folder listing to another catalog mode — transition gap fixed in source; medium-high confidence.** `PrepareModeAsync` now fades the currently rendered open-folder content out before it changes the active mode, restores destination mode state, and updates the mode chrome. The normal snapshot apply then updates and lays out the destination content before fading it in. Transition generations prevent an obsolete fade from stopping a newer transition. If the destination load fails while the old snapshot is still attached, its existing refresh-warning fallback is faded back in.
- **Tests — added, not run.** A policy test checks the player-return barrier decision for every `CatalogMode` with current page state both inside and outside Player.

## STRONGEST EVIDENCE

- At batch start, `ShowCatalogAsync` already inferred Player origin internally, so the reported missing-argument cause was not present in this checkout's source. It now has no boolean parameter; its trace decision comes from current-page state, and `ShowPage` itself enforces the barrier for Player-to-Catalog transitions.
- Grep found a single `_currentPage` assignment, inside `ShowPage`; the catalog-return source is captured immediately before it. All `ShowCatalogAsync` call sites use one mode argument. The only Player exit branch that does not use catalog navigation is the saved Home/Setup return path, which does not show the catalog surface.
- `ShowPage` captures `page == Catalog && _currentPage == Player` before assigning `_currentPage = page`. It sets catalog opacity to zero, calls `CatalogPageArea.UpdateLayout()` and `WindowRoot.UpdateLayout()`, then reaches the existing `FadeIn(CatalogPageArea)` path.
- Escape is handled by `PlayerPage_KeyDown` through `ReturnFromPlayer`. Back calls the same method. If the stored return page is Catalog, both call `ShowCatalogAsync`; if it is Home or Setup, the path leaves for that non-catalog page. The four top-nav routes and shared brand/logo route also call `ShowCatalogAsync`.
- Folder transition root cause: before this change, `PrepareMode` synchronously changed `_activeMode`, restored the destination mode state (including `_openShelfId`), and updated category chrome while the prior open-folder visuals remained attached. `ApplySnapshotAsync` only started the normal mode fade after the new snapshot became ready. The old folder could therefore remain visible during the query interval with new mode chrome already selected. The new async preparation fades `ContentState` before those synchronous mutations; `ApplySnapshotAsync` still performs the destination layout and incoming fade.
- If loading the destination snapshot fails, `LoadCatalogAsync` now reveals the still-attached fallback surface after showing its existing refresh warning instead of leaving it at opacity zero.
- Repository search confirms all `ShowCatalogAsync` invocations have only a `CatalogMode` argument. No call site passes or omits a caller-controlled Player-return flag. `ShowPage` has no such argument and owns the Player-to-Catalog check.

## EXACT BOUNDARY

- `src/Tvivo.App/MainWindow.xaml.cs` — derive catalog transition state from the current page; keep the barrier inside `ShowPage`; remove caller-supplied return flags; await mode preparation and reject stale catalog requests.
- `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` — fade an open-folder surface before changing modes; make transition completion generation-safe; update mode callers to await preparation.
- `src/Tvivo.App/CatalogUiPolicies.cs` — add the mode/current-page policy used by the catalog routing decision.
- `tests/Tvivo.App.Tests/AppSmokeTests.cs` — add per-mode policy coverage.
- `HANDOFF-fix-batch-19.md` — record source diagnosis, evidence, and live verification gate.

## RULED OUT

- No remaining `ShowCatalogAsync` entry point depends on a caller remembering to set `returningFromPlayer`.
- No direct Player-to-Catalog `ShowPage` route bypasses the barrier: the barrier is inside `ShowPage` itself. The only `ShowPage(_playerReturnPage)` branch targets a saved Home or Setup page when the stored return page is not Catalog.
- Folder mode changes do not intentionally clear `OpenShelfGrid` before the incoming snapshot is ready. The prior listing is faded out first; the established snapshot apply remains responsible for swapping and laying out content.

## UNCERTAINTY

- The source call graph and transition ordering are verified by inspection only. The reported flicker has not been reproduced or checked live after this change.
- The app and the added test were not built or run. WinUI compositor timing and rapid repeated category clicks still need live verification.

## NEXT GATE

When live verification is authorized:

1. Start a movie and immediately click each of My Tvivo, Movies, Series, and Live TV before playback starts. Confirm no resize/flicker and that the Player-return layout-barrier trace precedes the catalog fade.
2. Repeat each category route while playback is active, including the brand/logo-to-My-Tvivo route. Use Player Home, Back, and Escape as separate exits.
3. Open a folder's full listing, then switch to every other category with the shell top navigation. Confirm the folder fades away before the title/chrome changes and the replacement category fades in only after layout.
4. Rapidly alternate category clicks, including clicking the currently active category during a folder transition. Confirm no stale category reappears and the visible content returns to full opacity.
5. Run the new per-mode policy test and relevant App tests. None were run in this batch.
