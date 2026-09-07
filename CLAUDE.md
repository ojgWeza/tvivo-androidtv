# Project Brief for Claude Code

## What this is

An Android TV app (POC first, native Kotlin) that connects to an Xtream
Codes IPTV panel and lets the user browse and play Live TV, Movies, and
Series. Built by Hoda as a personal project, separate from her Health
Insights / Medica Cloud Care work.

## Current state

**Phases 0-3 are built and working against the real panel** (as of 2026-09-08).
The app authenticates, browses movies and live channels by category, and plays
movies. 48,761 VOD rows and 6,425 live channels are synced and cached; 76 unit
tests pass. Live playback is the one path never exercised — `max_connections`
is 1, so no automated test may open a stream.

**Start here:** `TODOS.md`. It carries the open QA defects (Part 1), the missing
test coverage (Part 2), the remaining phases (Part 3), and the emulator
environment gotchas that each cost real time to rediscover (Part 5).

**Phase 4 (Series) is next.** Phase 3 generalised the movies slice rather than
copying it, so Series adds one `CatalogSource` factory in `BrowseViewModel`, a
`series` table and the `get_series_info` season parsing — not another screen.
The browse UI is typed to `BrowseItem`, not to any entity.

Run the emulator with `tools/emulator.sh`, never bare `emulator.exe`: it stops
the Gradle daemons first and passes the two flags this machine requires. Details
in `TODOS.md` Part 5.

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

## Build order

0. Skeleton + install loop (TV manifest, network security config, `adb connect`
   workflow, **stable debug signing key**, one-time `dumpsys media.codec` probe,
   and verify whether `get_vod_streams` works with no `category_id` — the
   full-catalog sync tier depends on it)
1. Auth screen (one server field parsing `host:port`, user, pass; validate via
   `player_api.php`) then the Home screen (Live / Movies / Series)
2. Movies vertical slice end-to-end (rail → grid → player) — done; this is
   the pattern every other content type follows. Four things must land here
   rather than in Phase 5 because everything copies them:
   `enablePlaceholders = true` with stable keys, focus restoration by item ID,
   the focus frame plus last-active state, and the long-press context menu
3. Live TV — done; **16:9 channel card**, not the poster card, and `ext` not
   `container_extension`
4. Series (one extra layer: category → shows → `get_series_info` →
   season/episode picker → play)

**For any UI work, start from `docs/ui-scope.md`.** It carries the layout
system, card sizes, focus contract, state table and error copy. `architecture.md`
is the implementation view and deliberately says nothing about how screens look.

## Testing

Emulator-first. Create the AVD at the **same API level as the physical TV**
(`adb shell getprop ro.build.version.sdk`).

- Local unit tests: JUnit + kotlinx-coroutines-test + Turbine (repositories),
  Room in-memory DB (DAO + transaction behaviour), MockWebServer (API contract
  and the season-keyed-object parsing), Robolectric where framework classes
  are unavoidable. Run with `./gradlew test`.
- Instrumented: Compose D-pad focus traversal on the Android TV emulator.
- **No automated test may open a stream** — `max_connections` is `1`.
- Physical-TV validation happens once, after Phase 5. Accepted risk; the live
  `.ts` path carries the most exposure under that choice.

**Unit tests are necessary and nowhere near sufficient here.** Every defect
found so far — the login focus trap that made the app unusable on a remote, the
password leaking into the IME suggestion strip, the crushed Home tile, the
missing back stack — passed a green build and 66 green unit tests. All of them
were found by driving the emulator over `adb` and looking at a screenshot.
Budget for that on every UI change, and treat "it compiles and launches" as
saying nothing about whether the screen is usable.

Full coverage map and edge cases:
`~/.gstack/projects/ojgWeza-tvivo-androidtv/Dell-main-eng-review-test-plan-*.md`

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
