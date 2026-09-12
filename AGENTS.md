# AGENTS.md — for Codex and any non-Claude agent

`CLAUDE.md` in this directory is the full project brief. **Read it before
proposing anything.** This file is the short version of what will break if you
do not, plus what you are and are not expected to do here.

## What you are being asked to do on this machine

**Your shell is blocked here.** Every `pwsh -Command` / `cmd /c` spawn is
rejected at CreateProcess by policy on this box. You cannot run Gradle, the unit
suite, `adb`, or the emulator, and working around it through another tool is
extremely expensive — one `git log` cost ~32k tokens that way, so don't.

**But this is a default-flags limitation, not a prohibition.** If you are invoked
with a sandbox or approval mode that gives you a working shell — notably the
interactive `codex` TUI rather than `codex exec` — then build, test, and commit
freely. The owner needs you able to finish work unaided.

Default mode: **read, reason, write.** With a working shell, build and run local
unit tests as needed, but follow the deployment and emulator approval rules
below. A human, or Claude Code, may run `./gradlew test` and drive the emulator.
When you finish, say plainly what you could not check — that is useful, not a
failure.

State assumptions instead of proving them. If a task genuinely cannot be done
without executing something, say so and stop rather than guessing.

## Context discipline

- Keep tool use proportional to the task. Read the named source and its direct
  dependencies, not whole backlogs or unrelated logs.
- For a build or test request, make one properly configured attempt. If it is
  blocked by environment or missing tooling, report the exact blocker; do not
  retry through alternate caches, modes, or long polling unless the owner asks.
- Do not stream or paste large command output into the working context. Extract
  only the error, status, or file path needed to make the next decision.
- Prefer `rg -n`, bounded `Get-Content -First/-Skip`, and focused diffs. Do not
  dump whole source trees or repeat files already read; summarize only the
  relevant symbols and line ranges.
- Builds should write output to a temporary log and report only the final status,
  first error, warnings, and artifact path. Poll long builds with a short tail,
  never by replaying the complete build log.
- After an edit, inspect `git diff --stat` and targeted hunks. Use `git diff`
  without a path only when a complete review is needed before commit.
- Treat generated APKs, Gradle caches, emulator state, and screenshots as
  artifacts: inspect their metadata, not their binary contents, unless visual
  inspection is the task.
- Stop processes started for a failed attempt before handing off, and state
  plainly whether the requested artifact was produced.

## Storage and search policy

- **Treat C: as space-constrained.** Put new tools, SDKs, build caches, Android
  user state, temporary artifacts, and other setup on D: by default. Search D:
  first; inspect C: only when the item cannot reasonably be there or the user
  explicitly asks for it. Do not create new caches or install tooling on C:.

## Deployment, emulator, and memory rules

- **Never deploy/install the APK or launch an emulator test without asking the
  owner first and receiving explicit approval for that deployment and test.** A
  request to develop or fix code is not approval to deploy it.
- After development is finished and deployment/testing is approved, use the
  repository's emulator setup on the D: drive (`tools/emulator.sh`). Never launch
  bare `emulator.exe`.
- This machine is memory-constrained. Development/build processes and the
  emulator must not compete for memory:
  - Before development or builds, stop the emulator if it is running.
  - Before launching the emulator, stop Gradle/Kotlin build daemons and other
    development processes used for the build. `tools/emulator.sh` is expected to
    perform the daemon shutdown; verify that it has done so.
- Prefer dense, coherent implementation batches grouped by dependency and test
  surface. Do not stop to request deployment/emulator testing after each small
  change or single TODO item. Finish the whole batch, perform all safe local
  checks available without deployment, then request approval once for the
  combined deploy-and-emulator validation.
- Approval to deploy and test applies to that test run only. Ask again before a
  later deployment or emulator session unless the owner explicitly grants a
  broader scope.

## Workflow skills

- Before committing, use a focused, meaningful branch name that describes the
  batch (for example, `feat/crash-diagnostics-and-qa`). Follow normal GitHub
  hygiene: keep commits coherent and descriptively named, never commit directly
  to a broad or unrelated branch, and do not push or open a PR unless the owner
  explicitly asks.
- Use the installed gstack skills when they match the work: planning/reprioritizing
  before a dense batch, engineering review after implementation, and QA only
  after explicit deployment/emulator approval. Repository-specific constraints
  in this file and `CLAUDE.md` always override a generic skill workflow.
- Do not let `ship`, `land-and-deploy`, `qa`, or similar skills bypass the
  explicit approval requirement for installation, deployment, streams, or the
  emulator.

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
