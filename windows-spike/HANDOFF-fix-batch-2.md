# Fix batch 2 handoff

Code changes are prepared; no build, test, install, or app launch was run, per the user's gate.

## Changes by reported defect

1. **Window restore and duplicate native buttons** — `src/Tvivo.App/WindowStateController.cs` removes the native caption while retaining the app's resizable overlapped style. `src/Tvivo.App/MainWindow.xaml.cs` keeps the app chrome visible in windowed and fullscreen modes (except cinema mode) and minimizes without first switching out of fullscreen. `AppWindow.SetIcon` was left in place. No `ExtendsContentIntoTitleBar` or `SetTitleBar` usage was added.
2. **Artwork** — `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` now requests artwork bytes explicitly with HTTP headers, accepts HTTP and HTTPS, decodes from a memory stream, and retains the existing load throttling/cancellation. The card continues to use the catalog's `LogoUri`/`artwork` field.
3. **Placeholder mark** — `src/Tvivo.App/Pages/CatalogLandingPage.xaml` replaces the shirt/play silhouette with a screen outline and pulse line.
4. **My Tvivo ordering** — `src/Tvivo.Infrastructure/SqliteCatalogRepository.cs` adds schema migrations for a persisted visit count and punctuation-clean title key, records visits, orders My Tvivo by visit count then last visit, and preserves visit data through catalog refreshes. `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` requests the visit ordering for its shelves and refreshes the snapshot on return from playback. `tests/Tvivo.Infrastructure.Tests/SqliteCatalogRepositoryTests.cs` covers fallback order, visit ranking, and visit preservation.
5. **Series playback and side pane** — `src/Tvivo.Core/Contracts.cs` adds distinct series/episode models and a tested resolver. `src/Tvivo.Infrastructure/XtreamInfrastructure.cs` reads `get_series_info`, preserving string episode IDs and using the `duration` field. `src/Tvivo.Infrastructure/SqliteCatalogRepository.cs` stores last-opened/finished episode state in schema 12. `src/Tvivo.App/MainWindow.xaml.cs` resolves a show to its first, last-opened, or next episode and builds per-season episode rows. `src/Tvivo.App/MainWindow.xaml` adds the season selector and now-playing row. `tests/Tvivo.Core.Tests/CoreSmokeTests.cs` covers episode resolution; the repository test also advances the schema-8 migration expectation to schema 12.
6. **Player Home action** — `src/Tvivo.App/MainWindow.xaml.cs` routes Home to My Tvivo.
7. **Spotlight activation and label** — `src/Tvivo.App/Pages/CatalogLandingPage.xaml` makes the image area tappable. `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` routes it through the same item activation as the action button and labels the action by content type.
8. **Persistent now-playing row entry** — `src/Tvivo.App/MainWindow.xaml` and `src/Tvivo.App/MainWindow.xaml.cs` keep the active movie among movie siblings, show a now-playing status for the current item, and show the current episode in the selected season list. Completion changes the status to Finished.

## Live verification still needed

- Minimize and restore from the taskbar in fullscreen, maximized, and windowed states; confirm only app-owned chrome appears in both app window modes.
- Confirm artwork loads for both HTTP and HTTPS URLs, including a slow response, a failed response, and recycled shelf cards.
- Confirm My Tvivo ordering before and after opening items and across a catalog refresh.
- Against the provider, open an unplayed series, resume an unfinished last-opened episode, finish one and reopen the series, and switch seasons in the side pane.
- Open movies from the shelf, Spotlight image, and Spotlight action; verify the active movie remains in the side list and the indicator follows playback.
- Confirm player Home returns to My Tvivo.

Build and test results are intentionally absent. The requested tests were edited but not run.
