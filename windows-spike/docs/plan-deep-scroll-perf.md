# Deep-scroll sluggishness on the Movies/Series shelf list — analysis and plan

Source: sol (gpt-6-sol, high, read-only, source analysis only; nothing built or run). Saved by the orchestrator.

## Measurements being explained (Haiku, Debug build, Movies tab, 30 wheel notches per burst)
- ~100 notches deep: CPU 4.28 s/burst, UI-thread latency (UIA read) median 1036 ms, grey full-screen overlay seen. By ~300 notches latency is back to <1 ms.
- Page-up clicks 6-10 each keep the CPU busy ~5 s (first clicks settle in 0.73 s).
- Working set 460 -> 520 MB (544 MB after Page-up), threads 34 -> 43, handles 2045 -> 2270; only -2 MB after 20 s idle.
- 2052 artwork log events in the run (cache miss 372, applied 223, queued 214, cancelled 179, failed 26 = InvalidArtworkUri); decoded-bitmap cache pinned at 128 entries.
- The opened category grid (paged 99) is flat and fine.

## Findings from the source
- No incremental shelf loader on the scroll path; `BuildShelves` (CatalogLandingPage.xaml.cs:1091) creates all previews up front; `SynchronizeRenderedShelves` (:2725) runs on snapshot apply, not per wheel event.
- Each realized outer ListView item hosts its own horizontal GridView of up to 12 preview cards (XAML:393-440, .cs:137); no explicit ItemsPanel / CacheLength / ContainerContentChanging policy on the outer list; no x:Phase in the card template.
- Per card image on Loaded: creates a rounded composition clip (:1705-1715), starts artwork, may create/decode a BitmapImage (:2380-2389, :2485-2506).
- Debug-only amplifier: every shelf Loaded/Unloaded writes a diagnostic after summing cards across ALL realized shelves (:2392, `#if DEBUG`). Every artwork event calls `LaunchDiagnostics.Write` (synchronous file append under a lock, App.xaml.cs:157-170). The tested app was a Debug build; a Release build omits the shelf diagnostic.
- Possible transient stall: a refresh completing mid-scroll applies a snapshot and calls `ContentState.UpdateLayout()` synchronously on the UI thread (:621-669, :1904-1935). Timing not proven.
- Grey overlay: not identified. Candidates present in the page: FirstLoadTakeover (XAML:529), LoadingState after 300 ms on mode swap, ContentState fade (:1844-1875); none is triggered by wheel depth alone.
- ArtworkDiskCache: cancelling a caller stops its wait but the shared flight continues without the token (ArtworkDiskCache.cs:70-120).
- Memory: decoded bitmap cache (128 entries / ~96 MiB) and up to 16 snapshots are intentionally retained. Unbounded sets suspected: `_artworkDiagnosticsLogged`, `_artworkFailuresLogged`, `_spotlightWarmUrls`; cleanup of `_wiredShelfGrids`, `_appliedArtwork`, `_artworkLoads` if Unloaded is missed; ImageFailed leaves its load entry.

## Ranked causes
1. Shelf/card re-realization + synchronous per-event diagnostics (Page-up CPU/latency).
2. Artwork cache churn and shared flights continuing after cancellation.
3. Concurrent refresh + full snapshot apply/layout (transient S1 stall; needs timestamps).
4. Retained image/clip/grid/diagnostic-set state (sustained memory growth; unproven).

## Slices (luna-sized, in order)
1. **Instrumentation** (CatalogLandingPage.xaml/.cs): aggregate counters for outer container realization/recycle, live card elements, image Loaded/Unloaded balance, active/applied artwork, cache bytes, grid counts; a single-outstanding UI dispatch-latency probe; low-rate LaunchDiagnostics summaries and durations for refresh, SynchronizeRenderedShelves and UpdateLayout; log takeover/loading/fade visibility changes. Do not touch title bar, playback, repository, card behaviour.
2. **Cut measured synchronous churn:** replace the Debug per-shelf O(live-shelves) write and routine per-image file lines with periodic aggregates; keep individual failures.
3. **Fix the proven bottleneck:** explicit outer virtualization/cache policy if live containers grow with depth; reuse card clip + defer non-visible artwork work if realization time is high; ArtworkDiskCache cancel a flight only after its last waiter leaves.
4. **Close demonstrated retention:** bound diagnostic URL sets; clean up failed artwork loads; fix any grid/image/clip imbalance found by slice 1.

## Measurement gate (Haiku, same harness before/after)
Same build config, window size, theme, cache state; three fresh-process runs; S0 top, 30-notch bursts to ~100/300/800 notches, jump to bottom, 10 Page-up clicks, 20 s idle, repeat bottom-to-top. Record CPU s, UIA median/max, settle time, working/private MB, threads, handles. Success: S1 UIA median back to ~ms, later Page-up clicks near 0.73 s, live container/image counts plateau instead of climbing.

## Unknowns
No event timestamps, container counts or UI-thread probe data yet; grey overlay identity unknown; whether WinUI recycles outer containers under the default template; native memory of clips/surfaces. Shared-flight cancellation must not cancel another card using the same URL.

## Measured results after slices 1-2 (Haiku, dark theme, Movies tab, 30-notch bursts)
- Debug and Release behave alike: 48 vs 59 `perf.stall` lines; stalls of 1.0-2.0 s begin in the very first scroll burst (S0) and recur at ~100 notches (dispatchMaxMs ~1.6 s in both builds); the 800-notch stage itself is quiet. So the stalls are NOT Debug logging; they are realization/layout cost on the UI thread.
- Launch refresh apply: refreshApplyMs 1367 (Debug) / 925 (Release); `ContentState.UpdateLayout` 1213 / 783 ms in one synchronous call.
- liveShelves flat (22-23) = shelf virtualization works. liveCards/liveImages grow with depth (84 -> ~200 by 300 notches, 203-217 at the end) and are not released when scrolling back; decoded-bitmap cache bounded (128 entries, ~50-60 MB).
- Memory: WS 366 -> 413-447 MB, handles 2013 -> ~2200, threads 31-62; idle drops ~1 MB. Release private memory ~15-20 MB higher than Debug in late stages.
- Page-up settle times: Debug 0.9-5.2 s, Release 0.7-5.2 s (worse than the first-pass 0.73 s).
- No visibility (takeover/loading/fade) changes logged; grey overlay not reproduced in reviewed frames.
- Dark WS baseline (366 MB at S0) vs light (253 MB, partial) is unexplained.
