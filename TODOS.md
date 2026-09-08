# TODOs

Open work, with enough context to pick up cold. Items here were considered and
consciously deferred, or found by testing and not yet fixed — not a backlog of
ideas.

Everything that was *not* deferred is folded into `docs/ui-scope.md`,
`docs/architecture.md` and `docs/decisions.md`.

**Status as of 2026-09-08:** Phases 0-4 are built and running against the real
panel on the API 34 Android TV emulator, plus an Account screen that was not in
the original plan. **132 unit tests pass.** VOD (48,761 rows), live (6,425
channels) and series (13,264 shows) all sync in full; movie playback works end to
end and the season/episode picker was driven on-emulator.

Every defect except Q-4, Q-5 and Q-8 is fixed and verified on-emulator. Q-4 and
Q-5 both need an open stream, and `max_connections` is 1. Phase 5 (hardening) is
next.

**The emulator has no credentials on it right now.** App data was cleared during
the Phase 4 session, which destroyed the stored credential set — the Tink keyset
is not exportable, so nothing could be restored. Sign in again on the emulator
before any further QA. This is exactly the failure the warning below is about.

## Suggested order for the next session

1. **T-T2 (instrumented focus tests).** Now the highest-value gap. Five of the
   defects found on this project were focus/layout, all behind green builds, and
   Phase 4 added two more of the same class (initial focus on the detail screen,
   and the season rail's RIGHT target) that only a screenshot caught. Q-9 is the
   sharpest case: the first fix compiled, read correctly, and did nothing.
2. **Get the physical TV in early, ahead of Phase 5.** Live `.ts` playback, the
   `/series/` episode path, `max_connections` behaviour and remote key-repeat
   (T-D2b) are all unverifiable on the emulator, and both live and episode
   playback are shipped-but-never-executed code. This retires more risk than
   further emulator work.
3. **Phase 5 — hardening.** RTL verification, empty/loading/error states,
   `RefreshWorker`, on-device diagnostic log.

**T-T1 is done.** `CatalogDaoTest` (16 tests) covers `replaceCategory`, the
generation flip, account scoping and the per-type table separation;
`CatalogSyncerTest` (11) drives the full-catalog tier over MockWebServer against
in-memory Room, including the zero-row guard for all three content types.

`DESIGN.md` (T-D1) can wait — the shared `tvFocusFrame` and `BrowseItem` /
`CardShape` now enforce most of what it would have said. Multi-account (T-A1) is
speculative until a second panel actually exists.

---

# Part 1 — Open defects

Ten defects have been found on this project. **Every one of them passed a green
build and a green unit suite**, and every one was found by driving the emulator
over `adb` and looking at a screenshot. Budget for that on every UI change.

Seven are fixed and verified (Q-1, Q-2, Q-3, Q-6, Q-7, Q-9, and the two Home
nits found while verifying them). Phase 4 found three more the same way and all
three are fixed: the detail screen opened with focus on nothing, the picker
showed "1 min" for 40-minute episodes because this panel's `duration_secs` is
wrong, and episode titles carried the `HD` token the grid strips. What is left
is below. The durable lessons
from the fixed ones live in `docs/ui-scope.md` and `docs/decisions.md`, not
here — this file is for what is still open.

## Q-4 — Player has no visible way back
**Severity: Medium. Fixed in code (`a407bad`); still unverified on-emulator.**
`ui/player/PlayerActivity.kt`

Verifying the hint means opening a stream, and `max_connections` is 1, so this
stays code-reviewed only until the Phase 5 physical-TV session.

Back *works* (verified: `KEYCODE_BACK` moves `PlayerActivity` → `MainActivity`).
The defect is discoverability — nothing on screen communicated how to leave, so
the exit depended on the user already knowing about the remote's Back button.
A corner hint now rides the controller's visibility.

Distinct from Q-2: the mechanism is fine here, the affordance is missing.

## Q-5 — Player reports English audio on an Arabic film
**Severity: Medium. NOT REPRODUCED.**

Verifying requires an open stream and `max_connections` is 1, so this was not
chased. Two candidates to distinguish first:
- The file's single AAC track carries an `eng` language tag from the encoder,
  which is common on scene releases regardless of the actual audio. Media3 would
  then be reporting honest metadata and the tag itself is wrong — the fix is to
  show "Unknown" rather than trust a lone tag.
- Media3 is falling back to a default label.

Check `Player.getCurrentTracks()` language tags against the file before touching
any code.

## Q-8 — Movie title block consumes excessive vertical space
**Severity: Low. User asked to defer.**

Titles render below the poster on up to two lines, so long Arabic titles push
the grid rhythm around.

---

# Part 1b — Platform constraints, not defects

Recorded so they are not re-investigated as bugs.

## Q-10 — The TV IME owns the D-pad, so in-form navigation is keyboard-only
**Severity: Low. Not a defect — a platform constraint, recorded so it is not
re-investigated as one.**

While the TV keyboard is up, **every** arrow press goes to the keyboard, not to
the app. `dpadFieldNavigation` never sees them, and no amount of
`focusProperties` changes that. Confirmed on-emulator: pressing RIGHT from the
password field moved the highlight around the on-screen keyboard, not to the
adjacent `Show` button.

Consequences, both acceptable:
- Moving between fields with the IME open is the keyboard's own `Next` / `Done`
  keys, which the form already wires up through `imeAction`.
- `Show`, `Clear` and `Sign in` are reachable once the IME is dismissed with
  Back. This is the standard Android TV model, not something to work around.

**Do not "fix" this by intercepting keys harder.** The earlier login focus trap
came from fighting the same platform behaviour.

---
# Part 2 — Missing test coverage

## T-T1 — Room DAO and transaction tests — **DONE**

`CatalogDaoTest` and `CatalogSyncerTest` (Robolectric + in-memory Room, the
latter with MockWebServer). They cover `replaceCategory`'s delete-then-insert,
the generation flip, account scoping, the per-content-type table separation, and
the zero-row guard for VOD, live and series. `SeriesRepositoryTest` adds the
per-show `get_series_info` TTL and its own empty-result guard.

## T-T2 — Instrumented D-pad focus traversal tests
Compose focus tests on the emulator, per `CLAUDE.md`. Q-1, Q-2, Q-3 and Q-9 are
all focus/layout defects that shipped despite a green build — this is the class
of test that would catch them. Q-9 raises the value further: it was invisible in
code review twice over, since the first fix compiled, read correctly, and did
nothing.

---

# Part 3 — Remaining build phases
## Phases 0-3 + Account screen — **DONE**

Full detail is in git (`96051c8`, `a75931e`) and the docs. What a new session
needs to know:

- **The browse screen is generic.** The grid renders `BrowseItem` with
  `CardShape` as the only per-type input, and one `BrowseViewModel` picks a
  `CatalogSource`. `StreamListParser` holds the streaming JSON reader;
  `VodStreamParser` / `LiveStreamParser` are field mappings over it.
  **Phase 4 adds a `CatalogSource` factory, not a screen.**
- **Each content type gets its own table.** `stream_id` is unique only *within*
  a type, so a shared `(accountId, streamId)` key would let a channel and a film
  overwrite each other. Series needs the same.
- **A zero-row full-catalog response must never flip the generation.** A panel
  that rejects the no-`category_id` call answers with an object, which the
  parser reports as zero rows — indistinguishable from an empty catalog, and
  flipping on it deletes everything cached. Both paths record `partial` instead.
  This was latent in the VOD path and only found while building live.
- `get_live_streams` **does** answer with no `category_id` (6,425 channels in one
  call). `get_series` is the last unverified endpoint.
- Live playback is shipped but **never executed** — `max_connections` is 1.

## Phase 4 — Series — **DONE**

Built as predicted: one `CatalogSource` factory, a `series` table, and the
season-keyed-object parsing. No second browse screen. What a new session needs:

- **`get_series` answers with no `category_id`** — 13,264 shows in one call.
  That was the last unverified endpoint; the full-catalog tier now covers all
  three content types, and all three run through **one** `syncCatalog` body in
  `CatalogSyncer` rather than three copies of the zero-row guard.
- **A show is not playable.** `series_id` addresses no stream endpoint, so
  activating a show opens `SeriesDetailScreen` (season rail + episode list,
  deliberately the browse screen's layout) instead of the player. `MainActivity`
  routes on content type; the grid stays type-agnostic.
- **Episodes are their own table**, keyed `(accountId, seriesId, episodeId)` with
  `episodeId` a **string** — it is what goes in the playback URL, and nothing is
  gained by round-tripping it through Int. No generation column: episodes are
  fetched one show at a time, so plain delete-then-insert is right.
- `replaceEpisodes` **refuses an empty list**. A rejected `get_series_info`
  parses to zero episodes, and wiping a cached season on that makes an
  already-cached show unplayable offline. Same reasoning as the catalog guard.
- Episode freshness reuses `CachedFetch` under a distinct content type
  (`series_info`) with the show id in the category slot, so show 770 and
  category 770 cannot make each other look fresh.
- **`duration_secs` is not trustworthy on this panel** — a 60-episode drama
  reported 9–190 "seconds" per episode. `duration` (`HH:MM:SS`) is read first.
- Episode titles go through the same `NameNormalizer` display pass as the grid;
  without it the picker reads `HD` on every row.

## Phase 5 — Hardening
RTL verification, empty/loading/error states, `RefreshWorker`, on-device
diagnostic log. Physical-TV validation happens once here — the live `.ts` path
carries the most exposure under that choice.

**Manual refresh is done**, at both scopes: per-category in the browse header,
and `Refresh everything` on the Account screen (every category plus both
catalogs, TTL ignored).
## Account screen — done, and it is how you test login

`ui/settings/` carries signed-in user, server, status, expiry (highlighted
inside 7 days), `active/max` connections, and the actions: refresh everything,
sign in to a different account, sign out, exit.

**To reach the login screen, use Account → "Sign in to a different account".**
It keeps the current account signed in until a new one is accepted, and Back
returns to Home. Clearing app data instead destroys credentials that **cannot be
recovered** — the Tink keyset is not exportable, so a backup of the credential
blob would not decrypt.

## T-A1 — Multiple saved accounts (follow-up)

Today the store holds **one** credential set: switching accounts replaces it, so
coming back means re-typing. The cached catalog is already account-scoped by
`accountId`, so the data layer needs nothing — what is missing is a list of
credential sets in `CredentialsStore` plus a "current" pointer, and a picker.

Worth doing if more than one panel is genuinely in use; not before.

---

# Part 4 — Deferred design decisions

## T-D1 — Finish the design system (DESIGN.md)

**What:** Run `/design-consultation` and produce a `DESIGN.md` covering what is
still missing: motion spec, icon set, and a product wordmark.

**Why:** `/plan-design-review` (2026-09-07) rated design-system alignment 0/10
because there was nothing to align to. Both outside reviewers independently
flagged the same gap.

**Already closed:** colour and contrast — a dark teal surface ramp with a
Claude-orange accent, every pair measured against WCAG. See "Colour" in
`docs/ui-scope.md`.

**Now more urgent than when filed.** Q-1 and Q-3 are both cases of real screens
drifting from the colour and focus system, which is the exact failure mode a
design system exists to prevent. There are now real screens to design against,
which was the stated reason for waiting.

**Suggested timing:** after the Q-1/Q-3 fixes, before Phase 5.

## T-D2 — Decide list behaviour: key-repeat, jump affordance

**T-D2b — Key-repeat and fast-scroll.** Undefined, and the full-catalog sync
makes it matter: holding DOWN in `ALL` traverses 9,750 rows. Needs a decision on
repeat acceleration, and on whether focus movement stays 1:1 with key events or
switches to page-jumps past a threshold.

**T-D2c — Jump affordance in `ALL`.** The accepted cost recorded in
`decisions.md`: with no jump affordance the end of a 9,750-row list is
unreachable in practice. Revisit if `ALL` proves to be a dead end in use.

**Depends on:** both are best judged on hardware, so realistically they land with
the Phase 5 physical-TV session.

## T-D3 — Newest-first sort toggle for category grids

**What:** a second sort option, `added` descending, alongside the alphabetical
default, reachable per category.

**Why:** alphabetical fixes scanability but loses "what's new in this category".
Unlike `ALL`, a single category is small enough that either ordering is usable.

**Context:** `RECENTLY ADDED` already exists as a separate bounded virtual rail
(30 items, catalog-wide). This is per-category, inside a grid.

**Unblocked now** — Phase 2 shipped the grid and the long-press context menu to
hang a sort control off. Still needs a decision on whether the choice persists
per category, globally, or for the session only.

---

# Part 5 — Environment notes (not app defects)

Recorded because each cost real time to diagnose and will recur.

- **Emulator media volume defaults to 3/15.** Playback is inaudible and looks
  like an app bug. Confirmed 2026-09-07: the stream was AAC, decoded cleanly by
  `c2.android.aac.decoder`, no decoder errors — the volume was simply low.
  Raise with 14× `adb shell input keyevent 24`.
- **`hw.keyboard=yes` breaks Esc-as-Back.** The host keyboard becomes a real HID
  device, so Esc arrives as `KEYCODE_ESCAPE` (111) instead of `KEYCODE_BACK` (4)
  and the app ignores it. Fix is environmental: Extended Controls → Settings →
  General → "Send keyboard shortcuts to" = Emulator controls. **Do not add a
  `KEYCODE_ESCAPE` handler to app code** — no real TV remote sends that key.
- **`-gpu swiftshader_indirect` is required.** The host GPU path paints a black
  window while the guest renders correctly.
- **`-memory 1536`, and stop the Gradle daemons first.** 16 GB total does not fit
  the emulator plus the Gradle (1536m) and Kotlin (768m) daemons. The emulator
  gets OOM-killed mid-session otherwise. App launch went 34s → 1.4s after this.
  `tools/emulator.sh` does all of the above.
- **`adb shell` corrupts binary on Windows** (newline translation). Use
  `adb exec-out` to pull databases or screenshots.
- **Android TV AVDs have no touchscreen** (`hw.screen=no-touch`). Mouse clicks
  do nothing by design; `input tap` never works. Drive with `keyevent` or arrow
  keys.

---

# Considered and not taken

Recorded so they are not re-proposed as new ideas:

- **Idle dim / screensaver.** A static rail on an OLED panel risks burn-in after
  ~5 min idle. Small and self-contained; judged not worth tracking yet.
- **Manual refresh control placement.** Now built into the browse header.
- **Voice search.** Most Android TV remotes have a microphone, and search is the
  only place outside login that asks for typing. Declined as a whole integration
  for one field; the rail's category filter already keeps most navigation
  typing-free.
- **`KEYCODE_ESCAPE` handling in the player.** See Part 5 — an emulator config
  artefact, not a product requirement.
