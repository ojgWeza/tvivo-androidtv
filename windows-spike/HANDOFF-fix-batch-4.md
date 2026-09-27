# Fix batch 4 handoff

Code, tests, `DESIGN-RULES.md`, and this handoff were edited. No build, test, app launch, install, process control, or deployment was performed.

## VERDICT

The requested code paths are implemented. The series handoff now stops both backends, waits for pending LibVLC cleanup, and serializes the next start behind a 350 ms close interval. LibVLC's startup deadline is 30 seconds. Movie and series metadata are fetched lazily and stored in SQLite schema 14. Navigation keeps the page instances and catalog snapshot alive and fades the incoming surface for 150 ms.

The provider connection-limit theory is plausible from the lifecycle code but is not proven by the saved logs. The exact series failure still needs live verification.

## STRONGEST EVIDENCE

- `C:\Users\Dell\AppData\Local\Tvivo\tvivo-launch.log` has two entries on 2026-09-27 at 07:12:49 and 07:12:53: `Playback start failed: COMException`. Those entries lack an HRESULT, provider response, URL, stream kind, and engine state.
- `%TEMP%\tvivo-playback-engine.log` records LibVLC construction starting at 07:09:57.721, the 10-second readiness timeout at 07:10:07.879, and successful construction at 07:10:10.456. This confirms the old 10-second readiness deadline could expire before LibVLC became ready. The startup deadline is now 30 seconds.
- `GetSeriesInfoAsync` reads episode `id` as a string and `container_extension`, trims a leading dot, and builds `/series/{username}/{password}/{episodeId}.{extension}`. A new test asserts the episode ID and `.mkv` path. There is no observed provider stream request to establish redirect or server behavior.
- The old main-window path stopped only the selected `PlaybackService` on an in-player switch. The services own separate engines. LibVLC's timed-out `Play()` path also detached its player and deferred disposal without making the next start wait for that disposal. Both backends are now stopped before each start, and LibVLC tracks and awaits deferred cleanup.
- `PlaybackService` previously did not call the engine's stop method when its session had already been cleared after a timeout. It now invokes `StopAsync` in that case as well, allowing the LibVLC engine to finish deferred cleanup before handoff.
- The prior spotlight eyebrow was selected from the active catalog mode, even when My Tvivo supplied a spotlight card of a different type. It now uses the selected card's actual `StreamKind` and item title.
- The previous Back path explicitly invalidated catalog snapshots before loading the same mode again. It now reuses the cached snapshot; the shelf collection and its scroll state remain attached. Home and setup pages are both hosted once in a persistent grid.

## Root causes and changes

1. **Series playback and release:** switching episodes passed through one backend's service gate, while the app has two independent engines. A LibVLC startup timeout could also return while native `Play()` was still unwinding and its media/player were still alive. The app now serializes starts through `PlaybackHandoff`, stops both engines, waits 350 ms after stop, and waits for deferred LibVLC close/dispose work. LibVLC startup timeout is 30 seconds, matching the native engine's current startup deadline. Failure text now names the stream kind and gives checks for provider connection, ID/extension, network, and decoding. No automatic retry loop was found; Retry is user initiated.
2. **Spotlight title/type:** the eyebrow was based on `_activeMode`, which does not identify mixed-type My Tvivo spotlight items. The card's actual title and kind now drive the spotlight title, subtitle, and `MOVIE`/`SERIES`/`LIVE` eyebrow.
3. **Sort control position:** the sort panel had no explicit right alignment inside its auto-sized grid column. It now aligns right in the persistent controls row regardless of loading visibility.
4. **Stale player surface:** title assignment already occurred before `StartPlaybackAsync`, and no playback-start handler assigns the title. The video surface had no cover while the previous source was stopping/opening, so the old frame could remain visible through the transition. A panel-colored curtain now covers the surface immediately on selection and stays until startup succeeds; the selected title is also reaffirmed at the start of the handoff. Metadata requests are guarded against updating a later selection.
5. **Missing plot/details:** the catalog had no `get_vod_info` method, series info metadata was discarded, and the existing SQLite `rating`/`plot` fields were not populated or returned to cards. Added Xtream movie and series metadata mapping for year, rating, genre, plot/description, and cast/actors; added columns and schema-v13-to-v14 migration; metadata is cached per account/type/item and returned with catalog channels. The player has a collapsible About section; cards have a year/rating/genre details line. Empty or failed provider responses show a neutral unavailable message.
6. **Back flicker/transitions:** Back cleared snapshot cache state and forced the catalog to render its lists again. Home/setup were also swapped through a `ContentControl`. Back now uses the retained snapshot, the shell keeps both page instances attached, and screen surfaces fade in through Composition opacity animation. `DESIGN-RULES.md` now says transitions never rebuild visible content.

## Files by defect

- **1 and 4:** `src/Tvivo.App/MainWindow.xaml(.cs)`, `src/Tvivo.Core/PlaybackService.cs`, `src/Tvivo.Playback/WindowsPlaybackEngine.cs`
- **2, 3, and 6:** `src/Tvivo.App/Pages/CatalogLandingPage.xaml(.cs)`, `src/Tvivo.App/MainWindow.xaml(.cs)`, `DESIGN-RULES.md`
- **5:** `src/Tvivo.Core/Contracts.cs`, `src/Tvivo.Infrastructure/XtreamInfrastructure.cs`, `src/Tvivo.Infrastructure/SqliteCatalogRepository.cs`, `src/Tvivo.App/MainWindow.xaml(.cs)`, `src/Tvivo.App/Pages/CatalogLandingPage.xaml(.cs)`
- **Regression coverage authored:** `tests/Tvivo.Playback.Tests/PlaybackSmokeTests.cs`, `tests/Tvivo.Infrastructure.Tests/InfrastructureTests.cs`, `tests/Tvivo.Infrastructure.Tests/SqliteCatalogRepositoryTests.cs`

## EXACT BOUNDARY

The diff contains code, regression tests, the specifically requested design rule, and this handoff. Tests were authored but not executed. No build, app launch, install, process control, provider stream, or deployment was performed. `git diff --check` reported no whitespace errors; it emitted only a CRLF-to-LF working-copy warning for `SqliteCatalogRepositoryTests.cs`.

## RULED OUT

- The current source does not show a wrong series URL pattern or integer-only episode ID handling. A unit test covers the expected string ID and extension URL.
- No automatic playback retry loop appears in the inspected app path.
- The saved diagnostics do not show `active_cons`, a provider rejection, an episode redirect, or a stream URL. They cannot establish that the provider's account-specific connection limit was exceeded.
- The stale player title is not assigned only by a playback-start event; selection code assigns it before playback. The missing immediate cover of the old video frame is directly present in the UI.

## UNCERTAINTY

The user-reported alternating `hostFailure` and `playback: Timeout` were not captured with stream-specific details in the saved logs. The LibVLC log proves one too-short engine-readiness deadline; it does not prove the reported series timeout came from that path. No live run was permitted, so provider `active_cons`, redirects, actual first-frame time, visual fade, sort placement, metadata response shape on the configured provider, and playback-engine close timing remain unverified.

## Live-verification list

- Using the approved account, select and rapidly switch series episodes in both Native and LibVLC modes. Confirm the old frame is covered, the new title/season list appears immediately, one stream starts at a time, and the next episode starts after the prior source closes.
- Leave playback with Back, reopen the same catalog, then re-enter the same episode. Confirm no stale frame/title, no failure until minutes pass, and the shelf scroll position is unchanged.
- If playback still fails, inspect the new launch log HRESULT and the LibVLC engine log; record engine, stream kind, whether LibVLC readiness or media startup timed out, and provider response/status without exposing credentials.
- Open Movies, Series, and Live while loading. Confirm the sort control stays at the right edge. Confirm spotlight title and type match the selected item in My Tvivo.
- Open a movie and a series with provider metadata. Confirm About shows year/rating/genre/plot/cast, collapses and expands, and persists across catalog refresh. Also check an item with absent metadata.
- Switch Home, setup, catalog, and player surfaces repeatedly. Confirm page instances, visible lists, catalog scroll, and player episode list do not flicker or reset.
- Run the infrastructure and playback test projects, including schema 8-through-14 migration and the handoff serialization test. Do not use an automated test to open a provider stream.

## NEXT GATE

Owner runs the build and tests, then approves one live verification session. Revisit provider connection-limit attribution only if the new HRESULT/engine diagnostics and observed stop/start sequence still show a series-only failure.
