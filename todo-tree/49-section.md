## N-9 — Profile and optimize Room retrieval for instant-feeling browse
**Cost: medium. Performance milestone, not speculative index work.**

The local catalog is large enough that database latency is product behavior:
opening a category, moving between virtual folders, filtering, returning from a
detail page, and restoring a far-away card should feel immediate even while a
full-catalog sync is writing in the background. Define budgets before changing
the schema: cached category/header data visible within 100 ms, first grid page
within 200 ms, filter results within 300 ms after the existing debounce, and no
main-thread disk I/O or focus loss while paging invalidates.

Measure representative worst cases on the real database (large category,
48k-row VOD catalog, Recently Added, Continue Watching, Favourites, filtered
query, and BACK at a deep grid position). Capture Room query plans with
`EXPLAIN QUERY PLAN`, query timings, PagingSource invalidations, allocation/GC
pressure, and sync/read contention. Add benchmark fixtures that preserve the
catalog's real order of magnitude without using provider data.

Optimize only from observed plans. Check composite covering indexes against the
actual WHERE/ORDER BY clauses; normalized-name filtering; joins/lookups used by
virtual folders; bounded projections instead of full entities where possible;
transaction size and WAL behavior during generation flips; and whether count
queries duplicate expensive scans. Every added index must justify its sync and
storage cost. Do not disable placeholders or stable keys: deep focus restoration
depends on them. Treat the latency budgets as regression gates, with
Macrobenchmark or instrumented measurements on the emulator and final
confirmation on the physical TV after explicit deployment approval.

