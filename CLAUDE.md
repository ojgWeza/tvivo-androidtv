# Project Brief for Claude Code

## What this is

An Android TV app (POC first, native Kotlin) that connects to an Xtream
Codes IPTV panel and lets the user browse and play Live TV, Movies, and
Series. Built by Hoda as a personal project, separate from her Health
Insights / Medica Cloud Care work.

## Current state

Nothing built yet. This repo currently contains only discovery docs. Your
job, when picked up, is to start implementing against `docs/architecture.md`
using the confirmed API contract in `docs/xtream-api-reference.md`.

## Non-negotiable constraints

- **Native Kotlin**, not Flutter — chosen specifically for Android TV D-pad
  focus handling and leanback support. Do not suggest switching.
- **Media3 ExoPlayer** for playback. Streams are `.mkv`, `.mp4`, `.ts` direct
  files (not HLS manifests) for movie/series/live — progressive download,
  not adaptive streaming.
- **Room** for local caching with a 24h TTL, plus a manual refresh button
  that bypasses the TTL. See `docs/architecture.md` for the exact caching
  logic.
- **Credentials** (server, username, password) must be stored in
  `EncryptedSharedPreferences`, never in plaintext, never committed to the
  repo. `max_connections` on this account is `1` — only one stream can play
  at a time across all devices/apps, which matters for testing.
- **Provider-agnostic.** No panel hostname, port, provider name, or account
  detail is ever hardcoded or committed — this repo is public. The server and
  port are user-entered at runtime and read back from `server_info`, so the
  same build works against any Xtream Codes panel (and, later, any other
  M3U/list source) the user points it at. Docs use placeholder hosts only.
- All playback URLs are constructed client-side from `stream_id`/`episode_id`
  + `container_extension` (or `ext` for live) — see the URL pattern table in
  `docs/xtream-api-reference.md`. Never assume a `direct_source` field is
  populated; it's typically empty on this panel.

## Build order

1. Auth screen (server/user/pass entry, validate via `player_api.php`)
2. Movies vertical slice end-to-end (categories → list → player) — this is
   the pattern every other content type follows
3. Live TV (same pattern, reuses category/list UI)
4. Series (one extra layer: category → shows → `get_series_info` →
   season/episode picker → play)

## Conventions

- Technical content in this repo is always in English, regardless of the
  language used to discuss the project elsewhere.
- Keep docs actionable and non-redundant — update `docs/architecture.md` and
  `docs/xtream-api-reference.md` in place as implementation reveals new
  details, rather than letting this file or the docs drift out of sync with
  the code.
- Second TV target (LG webOS) is explicitly out of scope until the Android
  POC is working end-to-end. Don't introduce cross-platform abstractions
  "just in case" — they're premature here.
