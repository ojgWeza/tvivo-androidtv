# AGENTS.md — for Codex and any non-Claude agent

`CLAUDE.md` in this directory is the full project brief. **Read it before
proposing anything.** This file is the short version of what will break if you
do not, plus what you are and are not expected to do here.

## What you are being asked to do on this machine

**Your shell is blocked here.** Every `pwsh -Command` / `cmd /c` spawn is
rejected at CreateProcess by policy on this box. You cannot run Gradle, the unit
suite, `adb`, or the emulator, and working around it through another tool is
extremely expensive — one `git log` cost ~32k tokens that way. **Do not try.**

So: **read, reason, and write code. Do not attempt to build or verify it.**
A human, or Claude Code, runs `./gradlew test` and drives the emulator. When you
finish, say plainly what you could not check — that is useful, not a failure.

State assumptions instead of proving them. If a task genuinely cannot be done
without executing something, say so and stop rather than guessing.

## Non-negotiables — each of these has already cost real time

- **Room DB is v5 on real migrations. Never restore
  `fallbackToDestructiveMigration`.** It drops `favourites` and
  `resume_positions` — the only user data the app holds, and the tables the
  `CONTINUE WATCHING` and `FAVOURITES` rail folders are built from.
- **Never suggest clearing app data.** Credentials are DataStore + Tink and the
  keyset is **not exportable**, so a cleared install cannot be recovered and a
  backup of the blob would not decrypt. To reach the Login screen use
  Account → "Sign in to a different account", which keeps the current account
  until a new one is *accepted*.
- **`focusRestorer()` is not a focus fix here.** On Compose 1.6.8 alongside an
  explicit `focusGroup()` it leaves nothing focused, which on a remote means the
  app stops responding. Focus has two distinct required paths: re-entering the
  grid from the rail is `focusProperties { enter }`; returning from the pre-run
  page is `pendingFocusItemId` and must **request focus**, not merely scroll.
- **This repo is public and provider-agnostic.** No panel hostname, port,
  provider name, or account detail is ever hardcoded or committed. Server and
  port are user-entered and read back from `server_info`. Docs use placeholders.
- **`max_connections` is an account property, never an app rule.** It is `1` on
  the dev account. The UI reports the value from `server_info` and must never
  state a stream limit as a product fact.
- **No automated test may open a stream** (`max_connections` is 1). Live and
  episode playback are shipped-but-never-executed code paths. Do not write a
  test that plays anything.
- **Native Kotlin, Media3 ExoPlayer, Room.** Do not propose Flutter or any
  cross-platform abstraction; the Kotlin choice is specifically about Android TV
  D-pad focus. Streams are direct `.mkv`/`.mp4`/`.ts` files, not HLS manifests.
- **Cleartext HTTP is permitted deliberately** via `network_security_config.xml`.
  Never "fix" this by disabling certificate validation.
- **Card sizes are an image-pipeline input, not styling.** `POSTER(220x330)`,
  `CHANNEL(220x124)`. Posters are downsampled to card size before caching, so
  changing either means re-encoding the whole cache.
- **Never truncate a category label** — wrap to two lines. The panel ships
  `RAMADAN EGYPT 2026 SD` and `... HD`, which truncate to the same string and
  recreate a false-duplicate defect.
- **Nothing focusable may sit below y = 297 dp** on a screen that opens a
  keyboard. The TV IME owns the lower half and every arrow press while it is up.
  `imePadding()` alone does not achieve this.
- Playback URLs are built client-side from `stream_id`/`episode_id` +
  `container_extension` (`ext` for live). `direct_source` is typically empty.
  Episode ids are **strings**; `duration_secs` is wrong on this panel, read
  `duration`. An empty `get_series_info` must never wipe a cached season.
- **Technical content in this repo is always English.**

## Where to look

- `TODOS.md` — open defects (Part 1/1d), new unscheduled items (Part 2c),
  remaining phases (Part 3), environment gotchas (Part 5). **Start here.**
- `docs/architecture.md` — implementation view, caching tiers, paging/focus.
- `docs/ui-scope.md` + `docs/design/comps.html` — anything visual. The comps are
  the approved reference at true 1920x1080, 1 dp = 2 px.
- `docs/xtream-api-reference.md` — endpoints and the URL pattern table.
- `docs/decisions.md` — why things are the way they are, before re-litigating.

## House style

Match surrounding code. Unit tests are JUnit + kotlinx-coroutines-test + Turbine,
Room in-memory for DAO work, MockWebServer for API contract. Keep docs updated
in place rather than appending new files.
