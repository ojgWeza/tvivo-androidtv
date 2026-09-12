## N-13 — RAM usage profiling and heap pressure
**Cost: medium. Empirical measurement on the physical TV is essential.**

The app holds the full in-memory `PagingData` for each category's grid, plus the
Compose UI tree for the rail, detail pages, and player. With 9,750-row categories
and simultaneous image-loading on a TV with 1–2 GB heap, memory pressure during
scroll/filter operations and background sync is unverified.

Profile on the physical TV during Phase 5:
- Measure heap size at launch, after catalog sync completes, and during active
  browsing (rail changes, grid scroll, category filter, detail page open, player).
- Capture GC frequency and pause times, especially during scroll and while a
  background sync writes to the database.
- Check whether placeholders or stable keys cause memory bloat (they should not,
  but confirm on real hardware).
- Profile image loader concurrency — how many images load simultaneously, and
  whether the downsampling/caching pipeline is a bottleneck or a pressure point.

If GC pauses or OOM behavior are observed, decide whether to: cap the in-memory
PagingData window per category, implement LRU eviction for off-screen image
caches, or reduce background sync concurrency when the heap is above a threshold.

**Current state:** no profiling done; the app is "responsive on the emulator" but
heap constraints are unknown on real hardware.

---

# Part 3 — Remaining build phases
