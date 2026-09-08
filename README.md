# IPTV Android TV Client (POC)

Native Android TV app that connects to an Xtream Codes IPTV panel and plays
Live TV, Movies (VOD), and Series.

## Status

**Phases 0-4 built and running against a live panel** on an API 34 Android TV
emulator (2026-09-08). The app authenticates, browses Live TV, Movies and Series
by category, plays movies, and shows a season/episode picker per show. All three
catalogs sync in full and cache locally. 132 unit tests pass.

Phase 5 (hardening: RTL verification, state coverage, `RefreshWorker`, on-device
diagnostics) is next, together with the one round of physical-TV validation.
Live `.ts` and series episode playback are the paths never executed — the
account's `max_connections` is 1, so no automated test may open a stream.

Open work, with the context to pick it up cold, is in
[`TODOS.md`](TODOS.md).

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
- [`docs/ui-scope.md`](docs/ui-scope.md) — design handoff: screens and their
  states, D-pad/10-foot constraints, the content realities that break naive
  layouts, what is already locked, and the open design questions.
- [`docs/decisions.md`](docs/decisions.md) — key decisions made during
  discovery, during plan review, and while building each phase, and why.
- [`TODOS.md`](TODOS.md) — open defects, missing test coverage, remaining
  phases, and the emulator environment gotchas that each cost real time.

## Entry point for Claude Code

See [`CLAUDE.md`](CLAUDE.md) for project context and conventions.

## Two-TV context

- **Android TV** — primary target for this POC.
- **LG (webOS)** — second target, deliberately out of scope for now. webOS
  uses a different app model (web-based, Luna SDK, `ares-cli` packaging) and
  should be treated as a separate build later, not designed for prematurely.
