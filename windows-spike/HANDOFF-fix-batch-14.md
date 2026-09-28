# Fix batch 14 handoff

Code and handoff edits only. No build, test, launch, install, or live verification was performed. No Python was used.

## VERDICT

The player-to-catalog Back path did reuse the existing `CatalogLandingPage` and its rendered snapshot; it did not detach or recreate the catalog page. The source-level transition defect was in the outer shell reveal: Back called `ShowCatalogAsync`, whose `ShowPage` immediately started a 150 ms catalog-area fade. When cinema mode was active, `ShowPage` first called `StopPlaybackForNavigation`, which synchronously restored the normal player layout and the saved native window mode/bounds. The incoming catalog fade began in that same call stack, without a UI layout turn after the resize.

The Back-to-catalog path now sets the catalog area's composition opacity to zero before the page switch, suppresses `ShowPage`'s immediate fade, yields one UI-dispatch turn, forces the root and catalog layout, then fades the retained catalog area in. This corrects the ordering gap in source. The reported flicker is **not declared fixed** because the transition has not been observed live.

## STRONGEST EVIDENCE

- `MainWindow` creates one `_catalogLandingPage`, assigns it once to `CatalogPageHost`, and the player is a separate overlapping `Grid` in the same window XAML. `ShowPage` only changes `Visibility`; it does not replace `CatalogPageHost.Content`.
- `ReturnFromPlayer` calls `ShowCatalogAsync` for the active catalog mode. It does not invalidate the catalog snapshot. `PrepareMode` on the same mode keeps its current snapshot; the catalog is not reattached from scratch by the return path.
- Before this batch, `ShowPage` made `CatalogPageArea` visible and immediately called `FadeIn(CatalogPageArea)`. `FadeIn` set composition opacity to zero and started its animation immediately.
- Leaving Player invokes `StopPlaybackForNavigation` before changing `_currentPage` or page visibility. That method calls `SetCinemaMode(false)`. `SetCinemaMode` restores header/player rows and calls `_windowState.Apply(_preCinemaWindowMode)`; `WindowStateController.Apply` restores the saved native window placement with synchronous `SetWindowPlacement`/`SetWindowPos` calls.
- There was no layout/dispatch barrier between that synchronous restore and the catalog's immediate outer fade. This is the concrete ordering gap. The inner catalog snapshot fade in `CatalogLandingPage.ApplySnapshotAsync` is a separate transition and does not order the shell-level window resize.
- The return path now masks `CatalogPageArea` before `ShowPage`, defers its shell fade, yields with `Task.Yield`, calls `WindowRoot.UpdateLayout()` and `CatalogPageArea.UpdateLayout()`, and starts the existing 150 ms fade only afterward. `CatalogReturnTransitionPolicy` and an App smoke test cover selection of the player-to-catalog layout-barrier path.

## EXACT BOUNDARY

- `src/Tvivo.App/MainWindow.xaml.cs` — mask catalog opacity before cinema exit and defer its shell fade until a post-dispatch root/catalog layout pass; only used for player Back to catalog.
- `src/Tvivo.App/CatalogUiPolicies.cs` — define the pure policy selecting that layout barrier.
- `tests/Tvivo.App.Tests/AppSmokeTests.cs` — cover player-to-catalog barrier selection and nonmatching transitions. Not run.
- `HANDOFF-fix-batch-14.md` — this report.

## CONFIDENCE

- Retained page/snapshot and immediate-fade ordering: high source-level confidence; the relevant construction, return, visibility, cinema-exit, and native window-state methods were traced directly.
- Cause of the user's observed visual flicker: moderate confidence. The transition had no post-resize layout barrier and started a fade concurrently with returning layout, matching the report, but only a live reproduction can tie the symptom conclusively to that ordering.
- Effectiveness of the new zero-opacity/layout/fade sequence: unverified until runtime. The new test exercises only the pure routing policy.

## RULED OUT

- Catalog page recreation or loss of the rendered snapshot on Back: no `ContentControl.Content` replacement or snapshot invalidation occurs on this path; the existing page instance and mode are reused.
- Catalog category loading as the cause of the initial visibility transition: the shell-level reveal occurs synchronously before the later awaited catalog load completes. The new reveal barrier is placed before that await.
- A distinct asynchronous resize API in the player path: source uses synchronous Win32 placement/bounds calls, followed by XAML layout work that was not explicitly awaited before this batch.

## UNCERTAINTY

- Source inspection establishes the ordering defect and the retained-page path, but cannot establish that this was the only visible flicker source. No runtime trace or screenshot was captured.
- One UI-dispatch yield plus explicit `UpdateLayout` ensures WinUI measures the restored tree before the fade is started. Whether the actual window/compositor resize is visually settled by that point must be checked live, particularly when returning from cinema mode to a restored windowed placement.
- The new test covers the pure defer decision, not WinUI opacity, dispatcher, native resize, or animation behavior. No code compiled and no tests ran.

## NEXT GATE

1. Run the focused App tests and build when execution is allowed; this batch did not run either.
2. With an approved local fixture, enter cinema mode from a windowed placement, then use Back. Confirm the resize completes while catalog opacity remains zero and the retained catalog fades in only after the layout settles.
3. Repeat from a maximized/fullscreen pre-cinema placement and with cinema mode off, checking that catalog content, selected mode, scroll/open-shelf state, and window bounds settle without a redraw flicker.
4. If flicker remains, capture a live frame/size-change timeline to distinguish native window compositor timing from XAML layout or catalog snapshot changes; revise the barrier based on that evidence.
