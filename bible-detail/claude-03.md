## Non-negotiable constraints

- **Native Kotlin**, not Flutter — chosen specifically for Android TV D-pad
  focus handling and leanback support. Do not suggest switching.
- **Media3 ExoPlayer** for playback. Streams are `.mkv`, `.mp4`, `.ts` direct
  files (not HLS manifests) for movie/series/live — progressive download,
  not adaptive streaming.
- **Room** for local caching with a 24h TTL, plus a manual refresh button
  that bypasses the TTL. The TTL is tracked **per category**, and there is a
  **full-catalog sync tier** on top of it that must never block the UI. See
  `docs/architecture.md` for the exact caching logic.
- **Card sizes are an image-pipeline input, not styling.** `POSTER(220×330 px)`
  and `CHANNEL(220×124 px)`. Posters are downsampled to card size before
  caching, so changing either means re-encoding the whole cache.
- **`max_connections` is an account property, never an app rule.** It is `1` on
  the development account; other panels differ. The UI reports the value read
  from `server_info` and must never state a stream limit as a product fact.
- **No third-party brand marks in shipped assets.** Screen stock imagery for
  logos before use; see `docs/design/img/CREDITS.md`.
- **Credentials** (server, port, username, password) must be **encrypted at
  rest** and never in plaintext, never committed to the repo. Current
  implementation is DataStore + Tink — `EncryptedSharedPreferences` is
  deprecated, does synchronous crypto on the main thread, and has
  keyset-corruption crashes on the OEMs most Android TV boxes come from. The
  constraint is the encryption, not the library.
- **Cleartext HTTP must be explicitly permitted** via
  `network_security_config.xml`, or the app cannot reach the panel at all on
  `targetSdk 28+`. Never disable certificate validation to work around a bad
  cert — permit cleartext, keep system trust anchors.
- `max_connections` on this account is `1` — only one stream can play at a
  time across all devices/apps. No automated test may open a stream.
- **Provider-agnostic.** No panel hostname, port, provider name, or account
  detail is ever hardcoded or committed — this repo is public. The server and
  port are user-entered at runtime and read back from `server_info`, so the
  same build works against any Xtream Codes panel (and, later, any other
  M3U/list source) the user points it at. Docs use placeholder hosts only.
- All playback URLs are constructed client-side from `stream_id`/`episode_id`
  + `container_extension` (or `ext` for live) — see the URL pattern table in
  `docs/xtream-api-reference.md`. Never assume a `direct_source` field is
  populated; it's typically empty on this panel.

