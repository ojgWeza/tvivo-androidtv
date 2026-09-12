## Q-13 — Opening any listing re-downloaded the whole catalog — **FIXED**

**Severity: High.** Entering Movies/Live/Series re-ran the full-catalog sync
every time, with the progress line reading "Indexing" over data that was already
complete.

**Root cause:** `BrowseViewModel.init` called `syncer.syncVod()/syncLive()/
syncSeries()` unconditionally, and `CatalogSyncer` had **no freshness check of
its own**. The per-category tier had been TTL-gated since it was written
(`CachedFetch.ensureFresh`, `force = false`); the full-catalog tier never was.
The two tiers simply disagreed, and nothing failed loudly enough to show it.

**Measured on-emulator before the fix:** `complete`/13,264 rows at 07:02:42 →
re-entered the listing 11 minutes later → `indexing` and all 13,264 rows
rewritten. Every entry cost a fresh ~15 MB body.

**Fix:** a `force` parameter plus an `isFresh` gate in `CatalogSyncer`, using the
same 24 h window as `CachedFetch`. Only `complete` counts as fresh — `partial`,
`failed` and an interrupted `indexing` must all re-run — and a backwards clock
cannot pin the catalog fresh forever. `Refresh everything` passes `force = true`.

**`refreshEverything` also now syncs series**, which it never did. That was
latent before and load-bearing after: with the gate in place it is the only way
to re-sync inside the 24 h window, so a series catalog would otherwise have had
no manual escape hatch at all.

**Verified on-emulator after the fix:** entering the listing logged
`series catalog sync: within TTL, keeping cached generation`, `updatedAt` stayed
at 07:13:54, and no rows were rewritten.

---
# Part 2 — Missing test coverage

