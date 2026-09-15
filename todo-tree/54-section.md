## Phases 0-3 + Account screen — **DONE**

Full detail is in git (`96051c8`, `a75931e`) and the docs. What a new session
needs to know:

- **The browse screen is generic.** The grid renders `BrowseItem` with
  `CardShape` as the only per-type input, and one `BrowseViewModel` picks a
  `CatalogSource`. `StreamListParser` holds the streaming JSON reader;
  `VodStreamParser` / `LiveStreamParser` are field mappings over it.
  **Phase 4 adds a `CatalogSource` factory, not a screen.**
- **Each content type gets its own table.** `stream_id` is unique only *within*
  a type, so a shared `(accountId, streamId)` key would let a channel and a film
  overwrite each other. Series needs the same.
- **A zero-row full-catalog response must never flip the generation.** A panel
  that rejects the no-`category_id` call answers with an object, which the
  parser reports as zero rows — indistinguishable from an empty catalog, and
  flipping on it deletes everything cached. Both paths record `partial` instead.
  This was latent in the VOD path and only found while building live.
- `get_live_streams` **does** answer with no `category_id` (6,425 channels in one
  call). `get_series` is the last unverified endpoint.
- Live playback is shipped but **never executed** — `max_connections` is 1.

