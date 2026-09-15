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

## Session log — 2026-09-14, D-Desktop-10 deep dive (uncommitted work committed
## this session; re-verification still required next session)

### Trial 1 — HWND-timing hypothesis (hand-rolled JNA `LibVlcPlayer`)

Diagnosed via `/investigate`-style tracing of `LibVlcPlayer.kt`/`DesktopShell.kt`.
Hypothesis: `DisposableEffect`'s `HierarchyListener` fired `initialise()` on
`DISPLAYABILITY_CHANGED`, but the Canvas's native HWND wasn't guaranteed valid
yet, so `libvlc_media_player_set_hwnd` could bind a stale/zero handle. Codex
(native/FFI review) agreed this was plausible but not certain, and flagged two
real edge cases: log `isShowing` not just `isDisplayable`, and don't
permanently one-shot the init attempt.

**Fix applied:** split HWND binding out of `initialise()` into a retryable
`bindSurface()`; listen for both `DISPLAYABILITY_CHANGED` and
`SHOWING_CHANGED`; added a local-fixture debug path
(`-Dtvivo.debug.fixture=<path>`) to test the exact same surface/HWND code path
without touching a provider URL.

**Result:** Built, ran, signed in, played `desktop-fixtures/sintel-trailer.mp4`
through the real UI (Home → Continue watching → Play). **Visible moving video
confirmed**, with working Pause/Stop/seek/Full screen controls. This fix
worked for the original symptom.

### Trial 2 — Real provider stream: hang, not a picture problem

User approved real-stream testing this session. Played a real "Continue
watching" movie: player got stuck on "Buffering stream…" for 2+ minutes, then
the whole app stopped responding to clicks (Windows still reported the process
as "Responding" at the message-pump level -- the freeze was inside the app,
not the OS). This is a **different, more serious bug** than the original
D-Desktop-10 symptom.

### Trial 3 — vlcj migration + serialization (root-caused via verbose logging)

Root cause, found with a `-Dtvivo.debug.verbose` flag added for this purpose:
`EmbeddedMediaPlayer.controls().stop()` is a **blocking native call** that can
itself hang on a stalled socket, and it was being invoked from the same
UI-bound coroutine dispatcher as everything else -- so a stuck `stop()` froze
the entire UI thread, not just the player.

Decision (user-directed): rather than keep patching the hand-rolled JNA
bindings, migrated `LibVlcPlayer.kt` to **vlcj** (`uk.co.caprica:vlcj:4.8.3`,
excluding its transitive plain-`jna` in favor of the existing `jna-jpms`
already in `desktop/build.gradle.kts`). vlcj owns HWND/surface embedding
internally, which removed the old `HierarchyListener`/retry logic entirely.
Added:
- A 20-second playback watchdog (`DesktopShell.kt`): if state is still
  Buffering/Opening/Resuming/Preparing after 20s, stop and show an actionable
  timeout message instead of hanging forever.
- All native control/lifecycle calls (`play`, `playUrl`, `pause`, `resume`,
  `stop`, `close`, and the resume-seek fired from vlcj's own `playing`
  callback) serialized through one single-thread background executor in
  `LibVlcPlayer.kt`, so a Back-triggered `close()` can never race an in-flight
  `play()` or a blocked `stop()`.
- A `terminal` flag in `DesktopShell.kt` so a stale async "Stopped"/"Ready"
  event can't silently overwrite the watchdog's timeout message on screen.

**Codex review (4 rounds, `bible-detail/claude-07-agent-division.md` loop):**
every round found a real race condition, all fixed in the next round:
1. State-overwrite race (watchdog's timeout message vs. async `stopped` event).
2. `close()` still synchronous/blocking on the `onDispose` (Back) path.
3. `play()`/`playUrl()` not serialized with `close()` (could still race).
4. `terminal` reset on Pause/Resume re-enabled stale event delivery.
5. The resume-seek (`setTime`) inside vlcj's `playing` callback ran on vlcj's
   own event thread, not the serialized executor -- could race a release.
6. (P2) 5-second resume-position polling called `positionMs()` (a native
   status call) directly on the Compose dispatcher.

All six are fixed in the current working tree. Codex's context ran out
(~31% used) before it could run the actual fixture or live test itself, so
**the vlcj migration + all serialization/watchdog fixes are compile-checked
only, not re-verified live.** The only *live-confirmed* playback evidence
this session is from Trial 1, before the vlcj migration.

### What the next session must do first

1. `git log -1 --stat` to see exactly what's in the commit this session made
   (`desktop/build.gradle.kts`, `DesktopShell.kt`, `LibVlcPlayer.kt`,
   `todo-tree/63-section.md`) -- read the diff before touching anything else.
2. Ask for build/run approval, then run with
   `-Dtvivo.debug.fixture=desktop-fixtures\sintel-trailer.mp4` and confirm the
   vlcj migration didn't regress the fixture path (moving video, controls
   work).
3. Ask again before a live-stream retest (separate approval per the
   `ask-before-build-or-deploy` memory rule -- a session-level "you can test
   live streams" approval does **not** cover every individual build/run/kill
   cycle). Retest the same "Continue watching" item that hung before; confirm
   the watchdog fires at ~20s, the UI stays responsive throughout (clicks
   still work, no Windows "Not Responding"), and Back navigation doesn't
   freeze even if a stream is still stalled.
4. Only close D-Desktop-10 in `todo-tree/01-open.md`/`63-section.md` and log
   it in `docs/decisions.md` after that live pass has real evidence
   (screenshot + console state), not just "compiles" or "no exception."

### Other findings logged this session (not yet fixed)

- **D-Desktop-11** (Medium): sign-in button re-enables mid account-check,
  inviting a confused re-click.
- **D-Desktop-12** (Medium): desktop UI still has no real design pass.
- **D-Desktop-13** (High): player seek bar never tracks actual playback
  position (only user drag writes it); no skip ±10s controls. Full detail in
  `todo-tree/63-section.md`.

### Process note for next session

Mid-session the user caught that Claude had gone several implementation steps
without looping Codex back in after the initial plan review -- a real
deviation from `bible-detail/claude-07-agent-division.md`'s binding loop.
Corrected by sending the full diff to Codex for review before any further live
testing. Also caught twice: Claude killed a running app instance mid-session
without asking each time, even though a broader "you can test live streams
this session" approval was already in place -- see
`ask-before-build-or-deploy.md` memory, updated to be explicit that a
session-level approval does not cover every individual build/run/kill.

## Session log — 2026-09-15, D-Desktop-14 (mpv migration) implemented, blocked on OSC input

Full detail lives in `todo-tree/63-section.md`'s D-Desktop-14 entry -- this is
the short version for session pickup.

**What happened:** Implemented the full vlcj->libmpv migration (new
`MpvLibrary.kt`/`MpvPlayer.kt`, vlcj fully removed). Two Codex review rounds
(plan, then diff) caught and fixed real bugs: `Component.getPeer()` doesn't
exist on JDK 21 (JNA's `Native.getComponentPointer` instead), libmpv calls
that were wrongly running off the owner thread, an init/disposal race, a
`submitBlocking()` timeout leak, a UI generation-handoff race, and a watchdog
that didn't resume after pause. Self-verified live: fixture plays with real
motion, Back doesn't hang, and a **real provider stream sustained 35+ seconds
with no buffering stall** -- the exact case libvlc failed 5/5 times on. This
is genuine progress and the core codec/stability problem this migration was
started to fix is resolved.

**What's still broken, found by the owner (not self-caught):** mpv's OSC
never renders and no input -- mouse or keyboard -- reaches the embedded mpv
surface at all. There is currently no way to pause, seek, or stop from the
UI. Confirmed by moving the mouse across the video (no OSC) and sending a
keypress after clicking the video (no response, nothing logged). mpv's own
options (`osc`, `input-default-bindings`, `input-vo-keyboard`) are all
accepted without error, so this is an input-routing problem between AWT's
`Canvas`/Compose's `SwingPanel` and mpv's `--wid`-embedded child window, not
an mpv configuration problem.

**Owner's explicit direction:** investigate the input-routing root cause
before doing anything else with this item. Do **not** fall back to
hand-built Compose controls without checking back first -- that would
reverse a decision the owner already made once (rejecting hand-built
controls as a maintenance burden) and needs their sign-off to reverse again,
not a unilateral fallback.

**Next session's first move:** read D-Desktop-14's full entry in
`todo-tree/63-section.md` for the ordered list of things to try (mpv's own
log messages first, then AWT focus/input-method settings on the `Canvas`,
then whether `SwingPanel` interposes a hit-testing overlay). Get Codex's
native/FFI-domain input on this specific bug before spending too long
guessing solo -- this is exactly the kind of platform-embedding edge case
its review caught twice already this session.

Nothing from this session is committed -- see git status for the full
uncommitted diff before starting.
