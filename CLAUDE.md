# Project Brief for Claude Code

## What this is

An Android TV app (POC first, native Kotlin) that connects to an Xtream
Codes IPTV panel and lets the user browse and play Live TV, Movies, and
Series. Built by Hoda as a personal project, separate from her Health
Insights / Medica Cloud Care work.

## Current state

**Phases 0-4 are built and working against the real panel** (as of 2026-09-08),
plus an Account screen (`ui/settings/`) carrying sign out, switch account,
refresh everything, exit, and the subscription expiry. The app authenticates,
browses movies, live channels and series by category, plays movies, and shows a
season/episode picker per show. 48,780 VOD rows, 6,424 live channels and 13,279
series shows are synced and cached; **149 unit tests pass**. Live and episode
playback are the paths never exercised — `max_connections` is 1, so no automated
test may open a stream.

**As of 2026-09-10, two things changed that everything else now assumes.**

*Activating a card does not play anything.* Every content type opens a pre-run
page (`ui/detail/`) carrying poster, title, description, Play (focused on
arrival) and a favourite heart. `ContentGrid`'s callback is `onActivate`, not
`onPlay`. The long-press menu keeps a direct Play as the shortcut. A movie's
description comes from `get_vod_info`, fetched lazily and cached — this panel
sends no `plot` on `get_vod_streams`, verified over a forced re-sync of all
48,780 rows.

*The rail leads with three folders the panel does not publish* — `RECENTLY
ADDED` (100), `CONTINUE WATCHING` (50) and `FAVOURITES`, in that order, above
the panel's categories. They are `CategoryEntity` rows with `__`-prefixed ids;
see `docs/architecture.md`. The Room DB is **v5, on real migrations** — never
restore `fallbackToDestructiveMigration`, it drops `favourites` and
`resume_positions`, which are the only user data the app holds and the two
tables those folders are built from. v5 adds `rating` to `vod_streams` and
`series`; nothing back-fills it, so ratings appear per category as each one
re-syncs.

**Every focusable surface has two distinct restore paths and both are required.**
Re-entering the grid from the rail is `focusProperties { enter }`; returning from
the pre-run page is `pendingFocusItemId` and must *request focus*, not merely
scroll. `focusRestorer()` is **not** a substitute — on Compose 1.6.8 alongside an
explicit `focusGroup()` it leaves nothing focused, which on a remote means the
app stops responding. The reasoning is in `docs/architecture.md` under paging.

**To test the login screen, use Account → "Sign in to a different account" and
enter a deliberately wrong account.** That path does **not** wipe anything: it
routes to Login and keeps the current credentials until a new sign-in is
*accepted*. Only the separate `Sign out` button calls `store.wipe()`. Clearing
app data destroys credentials that cannot be recovered — the Tink keyset is not
exportable.

**Start here:** `TODOS.md`. It carries the open QA defects (Part 1), the missing
test coverage (Part 2), the remaining phases (Part 3), and the emulator
environment gotchas that each cost real time to rediscover (Part 5).

**Phase 5 (hardening) is next.** Phase 4 landed as one `CatalogSource` factory in
`BrowseViewModel`, a `series` table and the `get_series_info` season parsing —
not another browse screen. The one thing series does add is `ui/series/`: a show
is not playable (`series_id` addresses no stream endpoint), so activating one
opens the season/episode picker and `MainActivity` routes on content type. The
browse UI stays typed to `BrowseItem`, not to any entity.

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
4. Series — done; one extra layer (category → shows → `get_series_info` →
   season/episode picker → play). Episode ids are **strings**, `duration_secs`
   is wrong on this panel (read `duration`), and an empty `get_series_info`
   result must never wipe a cached season
5. Hardening — RTL, empty/loading/error states, `RefreshWorker`, physical TV

**Design pass (2026-09-08), decided and unbuilt — `TODOS.md` Part 2b.** Sits
alongside Phase 5 rather than inside it: D-1..D-12 are UI (login layout, type
scale, colour roles, rail width, overlaid card titles, Home icon row, tiles,
splash, app mark, guarded sign-out), D-13..D-17 are features (category filter,
item filter, read-only Subscription screen).

**For any UI work, start from `docs/ui-scope.md`, then open
`docs/design/comps.html` in a browser.** `ui-scope.md` carries the layout system,
type scale, card sizes, focus contract, state table and error copy.
`comps.html` is the approved visual reference from the 2026-09-08 `/impeccable`
pass: 12 frames at true 1920x1080, real palette hex, real catalog titles,
1 dp = 2 px, so its measurements are the ones to build.
`architecture.md` is the implementation view and deliberately says nothing about
how screens look. `PRODUCT.md` holds product truth (users, operating context,
constraints) and does not restate layout.

**A whole design pass is decided and unbuilt.** `TODOS.md` Part 2b lists it as
D-1..D-17, split into UI and feature work, with rationale in `docs/decisions.md`.
Land D-4 (type scale) and D-5 (Material colour roles) first — they touch nearly
every screen, and building anything else before them means building it twice.

**Two rules that were each violated twice and are now written down:**
- **Nothing focusable may sit below y = 297 dp** on a screen that opens a
  keyboard. The TV IME owns the lower half of the display and every arrow press
  while it is up. `imePadding()` alone does not achieve this.
- **Never truncate a category label.** This panel ships
  `RAMADAN EGYPT 2026 SD` and `... HD`, which truncate to the same string and
  recreate the false-duplicate defect. Wrap to two lines instead.

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
