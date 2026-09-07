# IPTV Android TV Client (POC)

Native Android TV app that connects to an Xtream Codes IPTV panel and plays
Live TV, Movies (VOD), and Series.

## Status

Discovery phase complete. API surface fully mapped and verified against a live
panel. No code written yet. See `docs/` for everything needed to start
implementation.

## Stack

- Kotlin, native Android (Android TV / leanback)
- Media3 ExoPlayer for playback
- Room for local caching
- WorkManager for scheduled cache refresh
- Retrofit for the Xtream Codes API client

## Docs

- [`docs/xtream-api-reference.md`](docs/xtream-api-reference.md) — full API
  contract: auth, all endpoints used, response shapes, playback URL patterns.
- [`docs/architecture.md`](docs/architecture.md) — module layout, caching
  strategy, player integration, build sequencing.
- [`docs/decisions.md`](docs/decisions.md) — key decisions made during
  discovery and why.

## Entry point for Claude Code

See [`CLAUDE.md`](CLAUDE.md) for project context and conventions.

## Two-TV context

- **Android TV** — primary target for this POC.
- **LG (webOS)** — second target, deliberately out of scope for now. webOS
  uses a different app model (web-based, Luna SDK, `ares-cli` packaging) and
  should be treated as a separate build later, not designed for prematurely.
