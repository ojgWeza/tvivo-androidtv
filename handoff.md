# Desktop product handoff

## PoC #2 — this session is a deliberate test, read this first

This is the second run of the Claude(plan+execute)/Codex(review+test) loop
documented in `bible-detail\claude-07-agent-division.md` — **read that file in
full before doing anything else**, it is binding, not background. PoC #1
(QA-10, a trivial two-line UI fix) proved the role split itself works: Codex's
raw tool output never touched Claude's context. It also showed Codex burned
9%→57% of its own context on that one small item, which is unaffordable for a
harder one — so `claude-07-agent-division.md` gained a "Context harness for
Codex" section (file-only `uiautomator` dumps, plan-before-probe navigation,
tight file reads, single-view screenshots, explicit budgets, self-reported
context-used numbers) that is now binding for every future review/test pass.

**This PoC's actual test:** pick up **D-Desktop-10** (below — Critical,
already attempted once, still broken) and run the full loop under the new
harness rules. Both the previous Claude session and the previous Codex pane
had their context cleared before this handoff was written, specifically so
this is a clean measurement, not a continuation with residual context.
Session-start self-check (Claude): check `herdr agent list` for an existing
Codex pane in this Herdr workspace before starting a new one. If starting
fresh, the standing brief plus the context-harness addendum both live in
`claude-07-agent-division.md` — read it into the brief rather than
reconstructing it from memory.

**What to measure and report, same as PoC #1:** context spent on each side,
and specifically whether the harness rules actually kept Codex under a
reasonable ceiling this time on a task with real native/FFI complexity (log
lines, JNA errors, the plugin-path change below) rather than a two-screen UI
check. If Codex still balloons despite the rules, that's a real finding —
report it, don't paper over it.

The desktop WIP below (`LibVlcPlayer.kt`, `DesktopShell.kt`,
`DesktopCatalogRepository.kt`) is now committed (`01f89f2`) as an unverified
attempt, so the tree is clean going in. Diagnose from there, not from a dirty
working copy.

## Objective

The owner has dismissed the desktop POC-D1 gate. Windows desktop is now an
active product target and must reach functional parity with the Android TV app,
while adapting only input and resizing behavior for Windows.

Do not retain the POC behavior that routes accepted credentials directly to a
local-fixture player. After authentication, the desktop app must open Home,
then route to Live TV, Movies, or Series. Playback opens only from selected
content.

## Authoritative design and contracts

Read these before editing:

- `AGENTS.md`
- `CLAUDE.md` and the relevant `bible-detail/claude-*.md` files
- `todo-tree/00-overview.md`
- `docs/ui-scope.md`
- `docs/architecture.md`
- `docs/design/design artifacts/README.md`
- `docs/design/design artifacts/android-tv-reference.html`
- `docs/design/design artifacts/desktop-journey-states.html`
- `docs/design/design artifacts/desktop-player-states.html`

The desktop journey reference establishes:

- Persistent top navigation: Home, Movies, Series, Live TV, Account.
- Compact keyboard-first provider sign-in inside the application shell.
- Home as the signed-in landing screen, with Live TV, Movies, and Series
  choices plus a Home-level refresh action.
- Browse as a 220 px category rail, category filter, item search, and a
  responsive whole-card grid.
- Never clip a focusable column. Reduce column count instead and keep symmetric
  grid padding.

The desktop player-state reference establishes the fitted native viewport,
opaque Compose control bands outside the AWT surface, and full-screen control
behavior. Keep LibVLC dynamically linked and do not commit native binaries.

## Required feature scope

Implement desktop sign-in, Home, Browse for Live/Movies/Series, category rail,
search/filter, favourites, continue watching, item details and episode
selection, Account/Subscription, diagnostics, and LibVLC player behavior.

Use Windows DPAPI credentials, desktop SQLite with real migrations, and a
provider-agnostic server entry. Share Android-independent protocol/domain code
deliberately; do not port Android-only APIs.

## Non-negotiables

- No hardcoded provider information and no plaintext credentials.
- Never use destructive migration or advise clearing user data.
- No automated test may open a real provider stream.
- Playback URLs are constructed client-side from IDs and extensions; live uses
  `ext`, VOD uses `container_extension`, and episode IDs are strings.
- Do not weaken TLS validation. Provider HTTP remains allowed when user entered.
- Preserve user data across desktop schema upgrades.
- Keep all technical content in English.

## Workspace state

- Branch: `codex/desktop-foundation`.
- Design artifact commit reported by owner: `e27c4f8`.
- `desktop/src/main/kotlin/com/dev/tvivo/desktop/Main.kt` is modified but must
  not be overwritten wholesale. The only observed unstaged change removes the
  invalid `androidx.compose.foundation.layout.weight` import. Preserve it when
  making an intentional, minimal entry-point change.
- Numerous generated captures, logs, images, and local tool folders are
  untracked user material. Never stage or delete them.

## Current implementation and evidence

- `desktop/` is Compose Desktop with a small `shared-core/` module.
- `Main.kt` currently loads DPAPI credentials, shows sign-in when absent, and
  incorrectly routes accepted credentials to `PlayerScreen`.
- `DesktopAuthRepository` uses a provider-neutral `player_api.php` request.
- `WindowsCredentialsStore` protects its DPAPI payload before writing it.
- `LibVlcPlayer` dynamically loads a user-provided LibVLC directory and embeds
  an AWT canvas after it becomes displayable.
- A forced desktop build succeeded after removing the invalid `weight` import:
  `:desktop:build` completed successfully. The former app window was closed.
- No desktop feature-parity work has been implemented yet.

## Recommended implementation order

1. Establish a conflict-safe desktop shell and route model: splash, sign-in,
   Home, Browse, Account, detail/series picker, and player.
2. Extract Android-independent API models/parsing/error/URL rules into
   `shared-core` with platform-independent tests.
3. Implement desktop SQLite schema and real migrations, scoped by account.
4. Port category and full catalog sync, artwork cache, and offline Browse.
5. Add favourites, resume positions, virtual folders, details, series episodes,
   subscription/account, and redacted diagnostics.
6. Wire selected movie/live/episode URLs to the LibVLC player; do not test real
   streams automatically.
7. Replace obsolete POC-only wording in TODO/design/decision documentation as
   desktop implementation becomes authoritative.

## Validation

The owner has not granted emulator or APK deployment approval. Desktop builds
and unit tests are safe when the local shell is available. Do not launch an
emulator or install/deploy an APK without explicit approval. Preserve the
account connection limit by keeping provider stream playback out of automated
tests.

## Next-session deployment handoff — 2026-09-14

### Current user-reported regressions

Treat the following as the next batch, in this order:

1. **D-Desktop-10 (Critical):** movie playback does not start. The recent
   bundled-LibVLC/plugin-path change did not restore observable playback.
   Diagnose with a local synthetic fixture first; never automatically open a
   provider stream. An owner-approved manual provider-stream check is required
   only after the local player is proven.
2. **D-Desktop-9 (High):** Full screen currently maximizes the whole app. Build
   a player-only full-screen state per `docs/design/desktop-player.md`: native
   viewport owns the window and the control dock is a non-overlapping Compose
   sibling with Play/Pause, Stop, seek, Exit full screen, and Back.
3. **D-Desktop-8 (High):** the movie title can collapse to one character per
   line. Use a horizontal, single-line, ellipsized title area with separately
   bounded player status.
4. **D-Desktop-7 (High):** remake the series episode activation interaction.
   It needs an unmistakable primary action, no stale partial selection, and a
   visible player preparation/error transition.
5. **Picker/account follow-up:** the intended fixes are a clearly signposted
   season selector, filename-style episode labels reduced to `Episode N`, and
   a parsed `YYYY-MM-DD` expiry date with invalid provider values suppressed.
   They require visual verification in the next fresh desktop run.

### Workspace and launch state

- The latest source changes are uncommitted in
  `desktop/src/main/kotlin/com/dev/tvivo/desktop/DesktopShell.kt` and
  `desktop/src/main/kotlin/com/dev/tvivo/desktop/LibVlcPlayer.kt`; preserve
  unrelated user changes and review the diff before editing.
- `:desktop:compileKotlin` reached Kotlin compilation but initially failed when
  Gradle tried to hash a missing generated incremental-cache file under
  `desktop/build`. The generated `:desktop:clean` task was then run, and a
  detached `:desktop:run` was launched successfully at 05:55 local time.
- Do not assume the current running window includes any later edits. Build and
  launch a fresh desktop target after the next implementation batch.

### Deployment and validation gate

Desktop launch is in scope. Do not deploy/install an Android APK or start the
Android emulator without a separate explicit owner approval. Before a desktop
launch, stop only desktop/Gradle processes that the next session itself started;
do not kill unknown user-owned windows. Validate windowed and player-specific
full-screen layouts, episode selection, Account expiry, and a local synthetic
fixture. Record the exact player state/error without URLs, provider details, or
credentials. Ask for explicit owner approval immediately before any real
provider-stream validation.
