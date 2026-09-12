## T-T1 — Room DAO and transaction tests — **DONE**

`CatalogDaoTest` and `CatalogSyncerTest` (Robolectric + in-memory Room, the
latter with MockWebServer). They cover `replaceCategory`'s delete-then-insert,
the generation flip, account scoping, the per-content-type table separation, and
the zero-row guard for VOD, live and series. `SeriesRepositoryTest` adds the
per-show `get_series_info` TTL and its own empty-result guard.

