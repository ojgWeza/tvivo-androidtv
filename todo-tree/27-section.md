## Q-26 — Virtual folders rendered as "Loading…" forever — **FIXED, verified**
**Severity: High. Found 2026-09-10 on-emulator, building the virtual folders.**
`ui/browse/BrowseViewModel.kt`

`RECENTLY ADDED` showed `100` in the rail and `Loading…` in the grid, indefinitely. The
list was in hand the whole time.

**Cause:** `PagingData.from(list)` — the overload without `sourceLoadStates` — leaves
`refresh` as `LoadState.Loading` forever. The grid's loading branch therefore always won,
and `itemCount` read 0 over a list it was already holding.

**Fix:** pass explicit `LoadStates`, all `NotLoading(endOfPaginationReached = true)`,
because a virtual folder *is* the whole list — there is no next page. **Verified on
emulator:** 100 cards render.

