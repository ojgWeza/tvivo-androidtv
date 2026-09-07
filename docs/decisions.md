# Decisions

Short log of choices made during discovery, with rationale, so Claude Code
doesn't re-litigate them without new information.

## Kotlin (native) over Flutter

**Decision:** Build native with Kotlin, not Flutter.
**Why:** Android TV specifically benefits from native D-pad focus handling
and mature leanback/TV UI libraries. Flutter would have reused prior Kemika
experience, but the TV-specific ergonomics tipped it toward native.
**Revisit if:** the LG webOS build later needs shared logic — at that point,
re-evaluate whether a cross-platform layer is worth the cost, but don't
introduce one preemptively.

## Xtream Codes API confirmed (not flat M3U)

**Decision:** Build against the Xtream Codes `player_api.php` structured API,
not flat M3U parsing.
**Why:** Verified via `player_api.php?username=...&password=...` returning a
valid `user_info`/`server_info` JSON payload — confirmed the panel is a full
Xtream Codes install. This gives structured, separate endpoints for live/VOD/
series instead of inferring categories from `group-title` tags in a flat
M3U file.
**Evidence:** see `docs/xtream-api-reference.md` for every verified endpoint
and response shape.

## Caching: 24h TTL + manual refresh

**Decision:** Room-based local cache, 24h TTL per content type, plus a
manual refresh button that bypasses the TTL.
**Why:** Catalog data (categories, VOD/live lists) doesn't change minute to
minute, so refetching on every screen load wastes bandwidth and adds
latency. A day-long TTL balances freshness against unnecessary calls; the
manual override handles the case where the user knows new content just
dropped and doesn't want to wait.
**Not included in the TTL:** `series_episodes` — fetched lazily per show on
first open, not tied to the 24h cycle, since episode lists are cheap to
re-check per show but expensive to refresh for the entire catalog.

## Series treated as a second-pass feature

**Decision:** Build movies end-to-end first, then live (same pattern), then
series last.
**Why:** Series has an extra data layer (category → show → season/episode)
and an extra screen (season/episode picker) that don't teach anything new
architecturally once the movie slice works — it's a mechanical extension of
the same pattern, not a design risk.

## max_connections = 1 constraint

**Decision:** Documented explicitly, not solved for in the POC.
**Why:** The account only allows one active stream at a time. This matters
during testing (don't run the new app and an existing IPTV app
simultaneously) but doesn't require app-level handling beyond surfacing a
clear error if the panel rejects a connection for this reason.
