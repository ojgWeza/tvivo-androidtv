# Static audit — WinUI overnight matrix

Scope: source review only in `windows-spike`; no build, Tvivo launch, stream, or live metric was run. Priorities describe credible failure impact, not observed frequency. File references are relative to this worktree. No live matrix row is marked PASS by this audit.

## Findings

### SA-01 · P1 · Close teardown resumes off the UI apartment

- **Evidence:** `src/Tvivo.App/MainWindow.xaml.cs:6061,6076,6089` uses `ConfigureAwait(false)` before invoking engine disposal. `src/Tvivo.Playback/FFmpegInteropPlaybackEngine.cs:322-335` disposes a WinRT `MediaPlayer`; `src/Tvivo.Playback/WindowsPlaybackEngine.cs:459-477` runs `StopCore` and disposes LibVLC. `src/Tvivo.Core/PlaybackService.cs:37-39` explicitly says engine objects are apartment-bound.
- **Scenario:** Close during a pending open. The first asynchronous stop causes `OnClosed` to continue on a pool thread, then native disposal accesses its apartment-bound player. An exception is caught at the window layer, but teardown may be incomplete before process exit; a native fault may terminate the process.
- **Minimal fix:** Keep all engine stop/dispose calls on the owning dispatcher; perform bounded waiting without moving the engine calls to a pool thread. Make disposal idempotent after a timeout.
- **Live rows:** PC-02, PC-03, PC-08, NE-01, LC-07.

### SA-02 · P1 · Close can exceed the matrix limit before forced exit

- **Evidence:** `src/Tvivo.App/MainWindow.xaml.cs:6021-6025` synchronously flushes visits and saves resume; `:6061`, `:6076`, and `:6089` then allow three sequential 3-second waits, with `Environment.Exit(0)` only at `:6105`. `src/Tvivo.App/MainWindow.xaml.cs:2576-2583` writes each pending visit separately. `src/Tvivo.Infrastructure/SqliteCatalogRepository.cs:977-986` opens and writes SQLite synchronously for each visit.
- **Scenario:** After many opens or under a locked/slow catalog database, Alt+F4 first blocks the UI in visit/resume writes, then can wait roughly nine more seconds across stop and disposal. `WaitAsync` bounds the await, not the underlying operation. This violates the 3-second normal and 5-second in-flight close targets.
- **Minimal fix:** Bound and batch persistence before close, and use one total close deadline for all teardown stages. Cancel or safely abandon operations that exceed it; record incomplete persistence explicitly.
- **Live rows:** LC-02, LC-07, LC-08, PC-02, PC-08, EN-01.

### SA-03 · P1 · Catalog work remains current after shutdown

- **Evidence:** `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs:406-418` stops timers and artwork but does not increment `_loadGeneration`, `_pageQueryGeneration`, `_shelfPreviewLoadGeneration`, or `_browseRestoreGeneration`. `:4884-4885` considers a load current solely by generation. `:1109-1118` and `:1213-1217` can apply an awaited snapshot; `:3195-3317` queues browse restore callbacks. `src/Tvivo.App/MainWindow.xaml.cs:6052` calls `Shutdown` while close continues asynchronously.
- **Scenario:** Close one second into startup or refresh. An already running database or provider task returns before `Environment.Exit`, passes the unchanged generation check, and updates a closed XAML tree or restarts shelf work. This can produce an exception or delay exit.
- **Minimal fix:** Add a shutdown flag and invalidate every async generation at the start of `Shutdown`; require `!_isShuttingDown` in continuation guards and stop restore timers.
- **Live rows:** LC-07, LC-08, NV-03, PC-08.

### SA-04 · P1 · Sleep-timer stop completion can overwrite a new playback UI

- **Evidence:** `src/Tvivo.App/MainWindow.xaml.cs:4177-4225` awaits `StopBothPlaybackEnginesAsync`, then unconditionally hides the playback surface and stops `_playbackUiTimer` in `finally` at `:4219-4224`. `:4147-4155` permits Watch again from the ended overlay; new starts reset their generation at `:2591-2596`. The stop continuation does not compare that generation.
- **Scenario:** Timer expires, the user presses Watch again or selects another item while engine stop is pending. The new start makes a fresh player visible, then the old sleep-timer continuation hides its surface or stops its UI updates. The same applies to a fast Back/Home transition followed by another open.
- **Minimal fix:** Capture the expired session generation and only mutate player UI after `await` if it is still current and the timer-ended state still belongs to it. Keep engine stop serialization separate from UI cleanup.
- **Live rows:** ST-02, ST-05, SE-06, RO-06, NV-02.

### SA-05 · P1 · A superseded cooldown can stop the next session's progress bar

- **Evidence:** `src/Tvivo.App/MainWindow.xaml.cs:2754-2769` creates and starts a cooldown timer; `:2843-2850` unconditionally calls `StopRecentlyLeftCooldownProgress` in the old task's `finally`. The same unconditional stop occurs after a playback attempt at `:3196-3203`. New retry progress uses that shared timer at `:2068-2084`.
- **Scenario:** Cancel a cooldown with Back, immediately open another item, and enter its retry/cooldown state. The old async continuation finishes after the new one starts and collapses the new bar; its status/progress no longer advances even though probes continue.
- **Minimal fix:** Give the shared progress UI an owner generation. In each `finally`, stop it only if its owner still matches the completing session; release old CTS independently.
- **Live rows:** RO-02, RO-03, RO-05, RO-06, RO-08.

### SA-06 · P1 · Spotlight settings Save does not invalidate catalog snapshots

- **Evidence:** `src/Tvivo.App/Pages/AccountPage.xaml.cs:257-267` saves settings without notifying the catalog. The only AccountPage invalidation wired by MainWindow is for layout changes at `src/Tvivo.App/MainWindow.xaml.cs:358`. `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs:3040-3051` keys snapshots by account, mode, browse state, sort, and offset, with no Spotlight settings revision. `:1107-1118` reuses such a snapshot before building a new one.
- **Scenario:** Visit Movies, change its Spotlight source to three specific folders in Settings, Save, then return to Movies while the saved catalog is still fresh. The cached Spotlight selection can be rendered despite the new settings. A refresh already in flight can also complete with a selection built under the old settings.
- **Minimal fix:** Raise a settings-saved event and invalidate Spotlight-bearing snapshots, or include a settings revision in the snapshot key. Reject refresh results whose settings revision is stale.
- **Live rows:** SS-01, SS-05, SS-06, SS-08, NV-06.

### SA-07 · P1 · Spotlight metadata may apply to a card changed by refresh

- **Evidence:** `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs:2670-2688` cancels dwell work but reuses `CatalogCard` objects by ID during `RenderSpotlight`; it does not cancel `_spotlightMetadataCts`. `:2983-3015` checks cancellation and card reference after lookup, but not the captured channel or a render/settings generation. `:5364-5382` updates the reused card's channel data.
- **Scenario:** A summary lookup starts for an open card, then a catalog refresh replaces that card's metadata or account-specific source while preserving its ID and object. The old lookup returns and writes its summary into the refreshed card. Repeated hover and refresh can show stale descriptions.
- **Minimal fix:** Cancel metadata work on a material Spotlight render change and validate an immutable card/content revision, account, and item identity before applying a result.
- **Live rows:** SP-06, SP-08, NV-06, SS-01.

### SA-08 · P1 · Artwork decode slots are released before a canceled decode ends

- **Evidence:** `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs:3561-3600` acquires a decode slot, then awaits `BitmapImage.SetSourceAsync` without a cancellation token. `:3735-3742` removes a canceled load and calls `ReleaseArtworkSlot`; `:3756-3766` releases both decode semaphores immediately. The in-flight `SetSourceAsync` may still finish later; its `finally` at `:3618-3621` sees the slot already released.
- **Scenario:** Rapidly switch pages or refresh Spotlight while several images are decoding. Canceled decodes keep using native memory while new decodes acquire the prematurely released slots. Working set and UI stalls can spike in SP-06/SP-07 or resize storms despite the nominal concurrency limit.
- **Minimal fix:** Cancellation may detach the image immediately, but keep each acquired permit until that load's decode task actually exits. Release it once in the task's `finally`.
- **Live rows:** SP-06, SP-07, TH-04, NV-01, EN-01.

### SA-09 · P1 · Resume persistence runs synchronously on the UI thread

- **Evidence:** The 500-ms UI timer calls `SaveResumePosition` at `src/Tvivo.App/MainWindow.xaml.cs:4397`. That method gates writes to five seconds at `:4612-4613` and calls repository `SaveProgress` at `:4629`. `src/Tvivo.Infrastructure/SqliteCatalogRepository.cs:939-951` opens SQLite, begins a transaction, executes multiple statements, and commits synchronously.
- **Scenario:** Playback and catalog refresh contend for SQLite or the disk is slow. A periodic save blocks input, pointer hiding, and video controls; Back/close can also block on forced saves. This can exceed the matrix's 1-second UI stall threshold even when the engine is healthy.
- **Minimal fix:** Queue immutable progress snapshots to a serialized background writer, coalesce the five-second updates, and keep explicit final-save ordering for Back and close.
- **Live rows:** SK-04, PC-02, SE-01, RO-07, EN-01.

### SA-10 · P1 · Episode changes perform repeated synchronous database work before reset

- **Evidence:** `src/Tvivo.App/MainWindow.xaml.cs:2168-2228` runs `GetEpisodeProgressForSeries`, `SaveProgress`, and then `GetEpisodeProgressForSeries` again at `:2188-2199`, plus season/list reconstruction, before calling `StartPlaybackAsync` at `:2227`. `:4350-4358` invokes this path from the 500-ms UI tick for autoplay.
- **Scenario:** A long season or busy SQLite database makes Next episode or autoplay freeze the UI before the previous picture is cleared. Three presses 400 ms apart queue visible work and may miss the intended final episode.
- **Minimal fix:** Read progress once, write on a worker, update in-memory progress optimistically, and start/reset the new playback session before expensive season-list work. Recheck item generation before applying asynchronous results.
- **Live rows:** SE-01, SE-02, SE-07, SE-08.

### SA-11 · P2 · Subsecond diagnostic and playback timers run while idle

- **Evidence:** `src/Tvivo.App/MainWindow.xaml.cs:343-345` starts the 500-ms playback UI timer in the constructor, before any playback. It stops on playback navigation at `:3394`, but the initial catalog idle window still ticks. `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs:300-305` starts a 250-ms `System.Threading.Timer` and a 2-second perf timer; `SetActive(false)` at `:338-353` does not stop either. Both stop only in `Shutdown` at `:406-415`.
- **Scenario:** LC-01 or SK-01 sits idle with no stream; SK-03 minimizes the app. Timers continue polling/sampling and posting dispatcher work, raising CPU and wakeups on a memory-constrained machine. The code alone does not establish that the 3% CPU threshold is crossed.
- **Minimal fix:** Start the playback timer with a playback session, suspend the probe/perf timers when the catalog is inactive or minimized, and restart them on activation if diagnostics are needed.
- **Live rows:** LC-01, SP-05, SK-01, SK-03.

### SA-12 · P2 · Spotlight and canceled artwork CTS instances are not disposed

- **Evidence:** `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs:2833-2855` and `:2983-3030` repeatedly create, cancel, and drop Spotlight dwell/metadata `CancellationTokenSource` objects without `Dispose`, including their async `finally`. Artwork `StopArtwork` at `:3735-3742` cancels and removes its load; `ExpireArtworkAsync` at `:3719-3733` disposes only on the noncanceled 10-second path.
- **Scenario:** Hover across cards, switch pages, and recycle hundreds of artwork elements. Canceled sources and callback state survive until GC instead of being released deterministically, adding allocation pressure to SP-08 and EN-01. This is not proof of a permanent managed leak.
- **Minimal fix:** Let each async owner dispose its CTS in `finally` after outstanding awaits finish; cancellation sites should cancel and detach only. Dispose canceled artwork sources after the decode/expiry task completes.
- **Live rows:** SP-08, NV-01, EN-01.

### SA-13 · P2 · Failed metadata keys can accumulate without a capacity bound

- **Evidence:** `src/Tvivo.Infrastructure/HoverMetadataLookup.cs:15-16` holds `_failedUntil` for the lifetime of the catalog page. `:126-134` removes an expired entry only when that same key is looked up; `:137-145` sweeps expired entries only when another failure is recorded. There is no count limit.
- **Scenario:** A long browsing session touches many distinct cards whose metadata request fails or returns empty. If failures arrive faster than expiry or the user stops after a burst, the dictionary retains those item keys, increasing memory until another failure triggers a sweep.
- **Minimal fix:** Bound the failure cache by count and expiry, and opportunistically sweep it on reads or with a coarse timer; preserve the existing 30-second retry behavior.
- **Live rows:** SP-08, NV-08, SK-01, EN-01 (only if failures occur).

### SA-14 · P2 · Spotlight folder search rebuilds every visual row per keystroke

- **Evidence:** `src/Tvivo.App/Pages/AccountPage.xaml.cs:239-243` calls `RenderSpotlightFolderList` on every text change. `:315-381` filters folders, clears all children, then allocates grids, text, checkboxes, and click handlers for every visible folder on the UI thread. `:119-128` also queries both folder lists synchronously whenever For You opens.
- **Scenario:** On an account with many folders, typing ten characters at 50-ms intervals repeatedly tears down and rebuilds the whole list; UI ping can exceed the SS-04 250-ms target. Opening For You during catalog refresh can add synchronous database contention.
- **Minimal fix:** Debounce search input, virtualize/bind folder rows, and load descriptors off the UI thread with an account/settings generation check.
- **Live rows:** SS-04, SS-03, NV-01.

## Reviewed protections and limits

- The decoded artwork cache has explicit 96-MiB/128-entry LRU limits (`CatalogLandingPage.xaml.cs:145-147,192-193,3637-3655`); `StopArtwork` clears `Image.Source` when cards leave the view (`:3735-3745`). The static artwork disk cache has a 200-MiB default cap (`ArtworkDiskCache.cs:13`). No unbounded static artwork cache was established.
- The cinema tooltip dictionary is cleared on cursor restoration (`MainWindow.xaml.cs:5881-5898`), and its key set is the same five buttons. Cooldown, cinema pointer, sleep notice, and player EPG timers have explicit stop paths (`MainWindow.xaml.cs:2877-2896,3840-3885,6028-6050`). The finding above concerns cross-session ownership and idle starts, not absence of those stops.
- `MainWindow`, `CatalogLandingPage`, and `AccountPage` are constructed once per window. Their own XAML event subscriptions therefore do not by themselves show per-open growth; the external EPG event is unsubscribed on close (`MainWindow.xaml.cs:6045`, `CatalogLandingPage.xaml.cs:416`). I did not promote self-owned handlers to leak findings.
- `BuildShelves` is invoked inside `Task.Run` (`CatalogLandingPage.xaml.cs:1309-1388`), and Spotlight selection is likewise built off the UI thread (`:1390`). The evidence supports a UI-thread shelf **apply** risk in `SynchronizeRenderedShelves` (`:4213-4281`), including an O(shelves²) move scan, but not a 100k-row O(n²) catalog scan. Measure `BuildShelvesResultApply` and `SynchronizeRenderedShelves` separately before treating the logged 1.8-second `BuildShelves` duration as a UI stall.
