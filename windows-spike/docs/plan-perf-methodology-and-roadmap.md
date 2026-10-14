# Tvivo performance: audit, methodology, roadmap (sol, read-only, 2026-10-14)

## Audit, ranked by user impact (file refs are src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs unless noted)
1. Startup: MainWindow builds both playback engines and all pages before activation (MainWindow.xaml.cs:178-214); saved-account load authenticates over the network BEFORE reading the local catalog (:630-675) — a slow provider delays a populated local DB.
2. Tab switch/refresh apply: ApplySnapshotAsync compares every shelf/card and calls ContentState.UpdateLayout() synchronously (:865-984,1938-2058); measured 783 ms (Release) inside a 925 ms apply.
3. BuildShelves fetches previews for ALL categories in one grouped window query and creates up to 12 cards per category before showing the tab (:1209-1279; SqliteCatalogRepository.cs:422-475). Outer ListView virtualization works (22-23 live shelves) but live cards/images climb to ~200 and stay.
4. SQLite: window-function grouped query across the type; LIKE '%term%' search; no WAL/busy-timeout/cache-size/ANALYZE policy visible; connection per call (SqliteCatalogRepository.cs:35-110,351-455). Verify with EXPLAIN QUERY PLAN before changing.
5. Memory: 16 full snapshots + _spotlightShelvesCache; decoded bitmap LRU 128 entries/96 MiB but live images can retain more; unbounded _spotlightWarmUrls/_browsePositions; _artworkLoads kept on ImageFailed.
6. Artwork: per-image composition clip + decode under 6-permit gate; ArtworkDiskCache cancelled callers don't cancel the shared flight; trim scans after each store (ArtworkDiskCache.cs:70-320).
7. Search: every TextChanged requests a snapshot, no cancel/debounce (:3099-3109,3253-3279); My Tvivo shelves request huge limits then take 12; related-playback open materializes a whole category.
8. Sync/logging/idle: full provider JSON parsed then one big upsert transaction; LaunchDiagnostics sync file writes under lock; XAML tracing on; timers 250 ms/2 s/8 s/60 s.

## Provisional Release budgets (documented 8 GB iGPU reference machine)
Cached launch to usable frame p95 <= 3 s (offline <= 4 s); tab switch warmed p95 <= 300 ms, cold <= 800 ms; scroll UIA p95 <= 50 ms, p99 <= 100 ms, no stall >= 500 ms; search p95 <= 300 ms after 200 ms debounce; WS <= 450 MB at 100k items (<= 600 MB at 300k); after 3 browse cycles growth <= 50 MB, <= 100 handles, <= 4 threads; ceilings handles <= 2500, threads <= 64; visible refresh apply <= 200 ms.

## Harness
Deterministic SQLite fixtures (100k/300k items, hundreds of categories, skew), local mock HTTP (fast/slow/429/bad image/offline), Release for numbers (Debug for correctness only), UIA-driven scenarios without keyboard (launch cached/offline, tab cycle, 30-notch bursts at top/100/300/800, bottom + 10 Page-up, search typing, open/close big category, refresh during scroll, 3 cycles + 60 s idle). Record UIA latency, CPU, WS/private, GC, handles, threads, SQLite durations/plans, cache hit/bytes, existing perf.surface/perf.stall. Three fresh processes per condition; report median/p95/p99/max + deltas.
Gates: before commit — focused tests + query-plan/100k benchmark for changed SQL + Release smoke of the surface; before "ready for users" — full suite, offline/slow network, 3-cycle plateau, low-end check.
Logging: low-rate aggregates; no per-event/per-card file writes; no accounts/URLs. Cache checklist: key, byte+entry bound, owner, eviction, invalidation, flight cancellation, native refs outside the counter.

## Roadmap (luna-sized, in order)
1. Instrumentation + 100k/300k fixtures + harness (repo query durations/row counts, snapshot bytes, refresh phase timings).
2. Split category metadata from previews: lightweight shelf descriptors, fetch preview cards only for visible + nearby shelves (generation-cancelled). Owner decision: prefetch distance, placeholder behaviour.
3. Remove routine UI-thread UpdateLayout; apply only changed descriptors; keep scroll restore.
4. Bounded caches: byte-bound snapshot/spotlight caches, cap warm URLs/browse positions, retire failed artwork loads, verify unloaded grids/images release refs.
5. Artwork: waiter-aware shared flights; avoid full-directory trim per store; reuse/retire per-card clips if profiling shows cost.
6. SQLite tuning + search (plans first; WAL/busy_timeout/ANALYZE where beneficial; debounce+cancel search; page large-category consumers).
Do-not-touch: title bar (never ExtendsContentIntoTitleBar), playback paths, provider specifics, user-data semantics; no automated test opens a stream.

## Unknowns first
Attribute each stall (layout vs decode/composition vs SQLite vs GC vs refresh); snapshot managed bytes and native surface bytes; SQLite plans; dark vs light baseline difference; owner decisions: prefetch distance, placeholder behaviour, reference hardware, whether cached catalog must be usable before provider auth (it currently authenticates first).
