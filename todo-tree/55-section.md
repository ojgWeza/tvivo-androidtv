## Phase 4 — Series — **DONE**

Built as predicted: one `CatalogSource` factory, a `series` table, and the
season-keyed-object parsing. No second browse screen. What a new session needs:

- **`get_series` answers with no `category_id`** — 13,264 shows in one call.
  That was the last unverified endpoint; the full-catalog tier now covers all
  three content types, and all three run through **one** `syncCatalog` body in
  `CatalogSyncer` rather than three copies of the zero-row guard.
- **A show is not playable.** `series_id` addresses no stream endpoint, so
  activating a show opens `SeriesDetailScreen` (season rail + episode list,
  deliberately the browse screen's layout) instead of the player. `MainActivity`
  routes on content type; the grid stays type-agnostic.
- **Episodes are their own table**, keyed `(accountId, seriesId, episodeId)` with
  `episodeId` a **string** — it is what goes in the playback URL, and nothing is
  gained by round-tripping it through Int. No generation column: episodes are
  fetched one show at a time, so plain delete-then-insert is right.
- `replaceEpisodes` **refuses an empty list**. A rejected `get_series_info`
  parses to zero episodes, and wiping a cached season on that makes an
  already-cached show unplayable offline. Same reasoning as the catalog guard.
- Episode freshness reuses `CachedFetch` under a distinct content type
  (`series_info`) with the show id in the category slot, so show 770 and
  category 770 cannot make each other look fresh.
- **`duration_secs` is not trustworthy on this panel** — a 60-episode drama
  reported 9–190 "seconds" per episode. `duration` (`HH:MM:SS`) is read first.
- Episode titles go through the same `NameNormalizer` display pass as the grid;
  without it the picker reads `HD` on every row.

