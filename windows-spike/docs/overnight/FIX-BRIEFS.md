# Fix briefs for Sol

No live-observed defect yet (run 01 blocked on memory before launch).

Implemented in this slice: DECISIONS.md D-b (both mini X buttons named "Stop playback"; in-window X stops
playback on the current page; Esc and Expand return to the player; Core policy test). Running-app verification
remains for PC-04/PC-05. `docs/test-plan-sleep-timer.md` names still need updating in a docs-authorized slice.

## Static audit batch (Opus, 2026-10-19)

Implementation status: S1-S7 done in the Windows worktree. S1 preserves the close diagnostics and batches visits in one transaction; Core covers its shared deadline. S2 invalidates catalog continuations during shutdown. S3/S4 use Core session ownership checks. S5 uses a settings revision on snapshots and rejects stale cache or refresh results; Core covers revision matching. S6 keeps decode permits until the request exits and reports permits in use. S7 debounces each folder search for 200 ms and cancels pending renders on explicit actions, account changes, and page exit. Live MATRIX verification remains open. Infrastructure visit-batch tests were outside this task's allowed test-file scope.

Source: STATIC-AUDIT.md, verified line by line; decisions in DECISIONS.md D-f. One edit pass, one build, then
`dotnet test` on all test projects. Commit as ONE slice ("Static audit batch: close teardown, stale async UI,
artwork permits, Spotlight settings revision"). Do not touch SA-07/09/10/11/12/13 (deferred or discarded).
No new log line may contain a URL, host, title or credential.

### S1 - P1 - Close teardown: UI apartment + one budget (SA-01 + SA-02) - FIX WITH CARE (D-f1)
- Files: `src/Tvivo.App/MainWindow.xaml.cs` `OnClosed` (~6004-6107), `FlushPendingVisits` (~2576);
  `src/Tvivo.Core/WindowCloseRequestPolicy.cs` (or a new `CloseTeardownBudget` in Tvivo.Core);
  `src/Tvivo.Infrastructure/SqliteCatalogRepository.cs` (`RecordVisit` ~977);
  `src/Tvivo.Playback/FFmpegInteropPlaybackEngine.cs` `DisposeAsync` (~322).
- Behaviour:
  1. Before the first await in `OnClosed`, start a backstop on the pool: `Task.Run` -> `Task.Delay(budget + 1.5 s)`
     -> log `event=window.close-backstop` -> `Environment.Exit(0)`.
  2. Remove all three `.ConfigureAwait(false)` (default context) so engine stop and both disposals run on the UI thread.
  3. One deadline = `_closeRequestedAt` + 5 s. Each stage awaits `WaitAsync(min(3 s, remaining))`; if remaining
     <= 0, skip the stage and log `event=window.close-stage stage=<name> skipped=budget`. Keep the existing
     timeout/exception logging and the final `Environment.Exit(0)` in `finally`.
  4. `FlushPendingVisits` uses a new `SqliteCatalogRepository.RecordVisits(list)` that writes all visits in ONE
     connection + ONE transaction (same SQL as `RecordVisit`). Keep `RecordVisit` as a one-item wrapper.
  5. Native `DisposeAsync`: acquire `_gate` first (as the LibVLC engine does) so it never disposes the gate under an
     in-flight `StopAsync`; return at once if `_disposed`. A hang there is covered by the backstop.
- Regression risk: medium (close path). If the dispatcher stops pumping after the last window closes, step 1 still
  guarantees exit. The visits/resume writes stay synchronous and first; nothing that saved before may be lost.
- Tests: Core - budget helper: remaining time per stage, skip when spent, min(3 s, remaining).
  Infrastructure (`SqliteCatalogRepositoryTests`) - `RecordVisits` with 3 items increments each `visit_count` once;
  a one-item list equals `RecordVisit`; an empty list is a no-op.
- Live verify: LC-02, LC-07, LC-08, PC-02, PC-03, PC-08, NE-01, EN-01 (`window.close-complete totalMs` <= 5000,
  no backstop line in normal closes, no "failed during window close" exception lines).

### S2 - P1 - Catalog async work after Shutdown (SA-03) - FIX NOW
- File: `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` `Shutdown` (~406), `IsCurrentLoad` (~4884), page-query
  check (~4783), shelf-preview checks (~3977, ~4160), browse restore (~3197, ~3207, ~3312).
- Behaviour: add `private bool _isShuttingDown;`. At the top of `Shutdown` set it, then `Interlocked.Increment`
  `_loadGeneration`, `_pageQueryGeneration`, `_shelfPreviewLoadGeneration`, `_browseRestoreGeneration`; stop
  `_shelfViewportTimer`, `_shelfViewportPollTimer`, `_shelfDeferredCommitTimer` (null-safe). `IsCurrentLoad` and the
  page-query / shelf-preview / browse-restore guards also return false when `_isShuttingDown`. `Shutdown` idempotent.
- Regression risk: low (only after close).
- Tests: none practical (XAML page); covered live.
- Live verify: LC-07, LC-08, NV-03, PC-08 (no exception lines between `MainWindow closed` and `window.close-complete`).

### S3 - P1 - Sleep-timer stop must not tear down a newer session (SA-04) - FIX NOW
- File: `src/Tvivo.App/MainWindow.xaml.cs` `EndPlaybackForSleepTimerAsync` (~4177-4226).
- Behaviour: capture `var endedGeneration = Interlocked.Increment(ref _playbackSessionGeneration);` (replaces the bare
  increment). In `finally`, run `SetPlaybackSurfaceVisibility(false)`, the curtain line and `_playbackUiTimer.Stop()`
  ONLY if `Volatile.Read(ref _playbackSessionGeneration) == endedGeneration && _sleepTimerEnded`; always call
  `RaiseSleepTimerStateChanged()`. Otherwise log `event=sleep-timer.stop-superseded`.
  (Why it matters: Watch again -> `ShowPage(Player)` starts `_playbackUiTimer`; the old `finally` then stops it, so
  progress, autoplay and the next sleep timer stop updating.)
- Regression risk: low; the normal path (no new start) is unchanged.
- Tests: optional Core helper `IsSameSession(captured, current, ended)` with 3 cases.
- Live verify: ST-02, ST-05, SE-06, RO-06, NV-02 (Watch again right after expiry: elapsed time keeps moving).

### S4 - P1 - Cooldown/retry progress bar has an owner (SA-05) - FIX NOW
- Files: `src/Tvivo.App/MainWindow.xaml.cs` `WaitForRecentlyLeftCooldownAsync` (~2748-2851),
  `StartPlaybackRetryProgress` (~2068), the post-attempt stop (~3201), `StopRecentlyLeftCooldownProgress` (~2881).
- Behaviour: add `long _cooldownProgressOwner` (playback session generation, -1 = none). Cooldown start and
  `StartPlaybackRetryProgress` set it to their session generation (pass `generation` in). Add
  `StopRecentlyLeftCooldownProgressIfOwner(long generation)` that stops only when the owner matches. Use it in the
  cooldown `finally` and at ~3201. Unconditional stops stay in `CancelRecentlyLeftCooldown`, close and page-leave
  paths, and `StopRecentlyLeftCooldownProgress` resets the owner to -1. CTS release in the `finally` is unchanged.
- Regression risk: low-medium; a missed owner set would leave a bar running, but `ResetPlayerForNewPlayback` ->
  `CancelRecentlyLeftCooldown` still clears it on every new open.
- Tests: Core - if the owner check is extracted as a pure helper: match / mismatch / none.
- Live verify: RO-02, RO-03, RO-05, RO-06, RO-08 (Back during cooldown, open another item: its bar advances).

### S5 - P1 - Spotlight settings Save invalidates snapshots (SA-06) - FIX WITH CARE (D-f2)
- Files: `src/Tvivo.App/SpotlightSettingsService.cs` (`Save`); `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs`
  `CatalogSnapshot` record (~5138), `BuildSpotlightShelves` and its caller (~1390), `TryGetCachedSnapshot`,
  `CacheSnapshot`, the `_renderedSnapshot` reuse path.
- Behaviour: `SpotlightSettingsService` gets `public long Revision` (Interlocked, starts 0), bumped after a successful
  `Save`. The snapshot builder reads the revision BEFORE `ReadEffectiveSettings` and stores it in a new
  `CatalogSnapshot.SpotlightRevision`. `TryGetCachedSnapshot` treats a different revision as a miss and drops the
  entry; `CacheSnapshot` does not store a stale-revision snapshot; rendered-snapshot reuse compares it too.
  No event wiring in MainWindow is needed.
- Regression risk: low; worst case one extra rebuild after Save.
- Tests: App.Tests only if `SpotlightSettingsService` can be constructed there (Revision bumps on Save, not on a
  failed Save); otherwise covered live.
- Live verify: SS-01, SS-05, SS-06, SS-08, NV-06 (change the Movies source, Save, return: Spotlight follows it).

### S6 - P1 - Artwork decode permits held until the decode ends (SA-08) - FIX WITH CARE (D-f3)
- File: `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` `StopArtwork` (~3735), `ReleaseArtworkSlot(Image)` (~3747),
  request method `finally` (~3618).
- Behaviour: `StopArtwork` cancels and detaches (remove from `_artworkLoads`, clear `Source`, opacity 0) but no
  longer calls `ReleaseArtworkSlot`; the `explicit-slot-release` overload only cancels. Permits are released by the
  request's own `finally` (ImageOpened / ImageFailed / expiry run after the decode and may stay). A canceled load must
  not cache or apply its bitmap (existing `IsCurrentArtworkLoad` check - keep it). Add "permits in use"
  (6 - `_artworkSlots.CurrentCount`) to the existing perf summary line.
- Regression risk: medium-low; symptom if wrong = artwork stops loading (permit starvation), visible in the new counter.
- Tests: none practical (WinUI bitmap); covered live.
- Live verify: SP-06, SP-07, TH-04, NV-01, EN-01 (artwork keeps loading after rapid page switches; WS within D-a).

### S7 - P2 - Spotlight folder search debounce (SA-14) - FIX NOW (D-f4)
- File: `src/Tvivo.App/Pages/AccountPage.xaml.cs` `SpotlightMoviesSearchBox_TextChanged` /
  `SpotlightSeriesSearchBox_TextChanged` (~239-243).
- Behaviour: one non-repeating 200 ms `DispatcherQueueTimer` per list; TextChanged restarts it; Tick calls
  `RenderSpotlightFolderList(type)`. Select all / Clear / Save / For You tab open stop the pending timer and render
  at once. Stop both timers when the account changes or the page is left.
- Regression risk: low.
- Tests: none (UI); covered live.
- Live verify: SS-04, SS-03, NV-01 (10 characters at 50 ms: one rebuild, no ping > 250 ms).

### Gate for the batch
Tvivo closed; Debug x64 build 0 errors; `dotnet test` Core, Infrastructure, Playback, App test projects green;
commit + push the branch. Then re-run only Batches 1, 2, 3, 5, 7, 8 for the rows above (D-d), when memory allows.

## Run 02 briefs (Opus, 2026-10-19 15:15) - live-observed defects

Order: land the static audit slice first (it is already in the tree), then F1 as its own slice, then F2.
No log line may contain a URL, host, port, username, title or credential; IDs only as booleans or counts.

### F1 - P1 - Provider "not authenticated" after cached start (DF-01 + DF-04) - DIAGNOSE, THEN FIX
Implementation note: fixed the saved-ID/provider-session key mismatch, added auth outcome and missing-session diagnostics, re-authentication on bounded refresh retry, and an auth wait gate for the first Spotlight/hover lookup. Core gate tests added. Live LC-01, LC-08, SP-01/SP-03 and NV-06 remain to verify.
- Files: `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` `AuthenticateSavedAccountAndRefreshAsync` (~1040-1090),
  `AuthenticateSavedAccountNetworkFirstAsync` (~1013), `ScheduleRefreshRetry` (~1494), the refresh loop (~1174-1200);
  `src/Tvivo.Infrastructure/XtreamInfrastructure.cs` `AuthenticateAsync` (~79-89: `accountId` and `_connections`),
  `GetArrayAsync` / `GetMovieInfoAsync` / `GetSeriesInfoAsync` (throw sites ~161/173/262);
  `src/Tvivo.Infrastructure/HoverMetadataLookup.cs`.
- Step 1 (diagnostic, keep it): in both auth paths log `event=auth.saved outcome=success|failure reason=<AuthFailureReason>
  code=<DiagnosticCode> idMatch=<true|false>`, where `idMatch` compares `result.Account.AccountId` with the cached
  account's ID (boolean only). In the provider, make the "not authenticated" exception message carry no data and log
  `event=provider.not-authenticated op=<categories|items|vodInfo|seriesInfo> knownConnections=<count>` once per minute.
- Step 2 (fix, per what step 1 shows - both are cheap, do both if unsure):
  a. ID mismatch: when auth succeeds, the provider must know the ID the app will use. In `AuthenticateAsync` store the
     connection under the server-adjusted ID AND under `connection.AccountId ?? AccountIdentity.For(connection.Endpoint
     /* before the port rewrite */, connection.Username)`. No catalog-row migration; nothing else changes keys.
  b. Auth failed at start: `ScheduleRefreshRetry` and the activity refresh must re-run `AuthenticateAsync` before
     refreshing when the provider has no connection for the account (one auth per retry, same back-off), and the
     InfoBar keeps the plain reason from `ShowCachedUpdateFailure(AuthenticationResult)`.
  c. DF-04: Spotlight/hover lookups started before `authDone` wait for it (or, on "not authenticated", retry once after
     `authDone` for the still-open card). No lookup storm: at most one retry per open card.
- Tests: Infrastructure - fake HTTP returning `server_info.port` different from the saved port: after `AuthenticateAsync`,
  a category call (the `GetArrayAsync` path) and `GetMovieInfoAsync` with an account keyed by the pre-rewrite ID succeed. App-free helper test for
  "retry re-authenticates when the connection is unknown" if extracted to Core.
- Regression risk: low-medium (auth/keying). Must not change which ID catalog rows, favourites and resume use.
- Live verify: LC-01 (a `catalog.cache.refresh.complete outcome=success` after a cached start, given network), LC-08,
  SP-01/SP-03 (open card gets a summary), NV-06, and `event=auth.saved` shows the outcome on every start.

### F2 - P1 - Unrequested playback while typing in search (DF-02) - DIAGNOSE FIRST, FIX ONLY WITH A FOUND CAUSE
Implementation note: queued Spotlight focus restoration could take focus from a text box during a rerender; it now checks the current focus before restoring. The actual activation that started the stream is still unproven. Added selection input/key/device/focus and playback-trigger diagnostics for LC-05 x5 and NV-03..05.
- Files: `src/Tvivo.App/MainWindow.xaml.cs` `CatalogLandingPage_ChannelSelected` (~1730), `TopSearchBox_*` (~1351-1391,
  ~1707); `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` `SpotlightItem_Click` (~2822, `TouchTapActivates`),
  `SpotlightItem_PointerEntered` (~2833), `ShelfCards_ItemClick` (~4357), `SetSearchText` (~876).
- Step 1 (diagnostic): log the activation source on every selection, before `content-selected`:
  `event=selection.source via=<spotlight-click|spotlight-tap|shelf-itemclick|home|search-result> input=<pointer|keyboard|
  programmatic|unknown> focusBefore=<AutomationId or type>`. Pointer device type from the routed args where available;
  for `Button.Click` record the last `PointerPressed`/`KeyDown` seen on that element (key name only, no text).
  Also log `event=focus.moved from=<id> to=<id> reason=<search-rerender|spotlight-open|other>` when focus leaves
  `TopSearchBox` while it has text.
- Step 2 (fix, only with the cause): a card may start playback only from an explicit activation (Enter/Space/click/tap)
  that happened on that card; a re-render or hover-open must never move keyboard focus out of a focused text box.
- Tests: Core helper for the "explicit activation on the same element" rule if it is extracted.
- Regression risk: medium (activation paths). Do not change the two-tap touch rule or keyboard Enter behaviour.
- Live verify: LC-05 x5 with the pointer resting over the Spotlight strip; NV-03, NV-04, NV-05 (typing never starts or
  controls playback).
