# TODOs

Open work, with enough context to pick up cold. Items here were considered and
consciously deferred, or found by testing and not yet fixed — not a backlog of
ideas.

Everything that was *not* deferred is folded into `docs/ui-scope.md`,
`docs/architecture.md` and `docs/decisions.md`.

**Status as of 2026-09-08:** Phases 0-4 are built and running against the real
panel on the API 34 Android TV emulator, plus an Account screen that was not in
the original plan. **145 unit tests pass.** VOD (48,761 rows), live (6,425
channels) and series (13,264 shows) all sync in full; movie playback works end to
end and the season/episode picker was driven on-emulator.

Every defect except Q-4, Q-5, Q-8 and Q-11 is fixed and verified on-emulator.
Q-4 and Q-5 both need an open stream, and `max_connections` is 1. Q-11's inset
bug is fixed and verified, but the buttons still fall below the fold — what is
left there is a layout decision. Phase 5 (hardening) is next.

**Credentials were re-entered by hand on 2026-09-08** after the Phase 4 session
cleared app data and destroyed the previous set. Do not clear app data.

**To QA the login screen, use Account → "Sign in to a different account" and
enter a deliberately wrong account.** That path does not wipe anything: it routes
to Login and keeps the current credentials until a new sign-in is *accepted*.
Only the separate `Sign out` button calls `store.wipe()`.

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

## Q-11 — Sign in / Clear sit under the TV keyboard — **PARTIALLY FIXED**

**Severity: High.** Focusing the password field made the `Sign in` and `Clear`
buttons disappear; they returned only once focus reached the button row.

**Root cause:** `Modifier.imePadding()` in `LoginScreen` was a **no-op**.
`android:windowSoftInputMode="adjustResize"` was set in the manifest, but
`WindowCompat.setDecorFitsSystemWindows(window, false)` was never called, so the
framework consumed the IME inset itself and reported zero to Compose. The button
row therefore stayed where it was, under a keyboard that covers roughly the lower
half of a 1080p panel. It reappeared on Tab only because the row's
`onFocusChanged { keyboard?.hide() }` (the Q-7 workaround) dismissed the IME —
that workaround was masking the real bug rather than fixing it.

**Fix:** `WindowCompat.setDecorFitsSystemWindows(window, false)` in
`MainActivity.onCreate`. One line; `imePadding()` then does what it always read
as doing.

**Distinct from Q-10.** Q-10 is about *reachability* (the IME owns the D-pad, so
arrows cannot move focus out) and remains an accepted platform constraint. This
was about *visibility* — the buttons were off-screen, not merely unfocusable.

**Verified on-emulator 2026-09-08, and the fix is not sufficient.** With the
password field focused and the IME up: the form now scrolls (the title scrolls
off) and the `Show` button is visible beside the field, neither of which happened
before — `imePadding()` is demonstrably working now. But `Clear` and `Sign in`
are **still off-screen**, because a `verticalScroll` Column only brings the
*focused* child into view and the button row sits below it. The remaining ~590 px
above the keyboard cannot hold title + subtitle + three fields + error + buttons.

**What is left is a layout change, not an inset fix.** The form is a 440 dp
column on a 1920 px screen, so the entire right half is empty. Moving the button
row beside the fields rather than below them takes it out of the IME's path
entirely. That is a design decision, so it is not made here.

**Not blocking sign-in:** the IME's own Done key submits, which is how this was
tested, and dismissing the IME reveals the row.

**Testing note that cost time:** Account → "Sign in to a different account" does
**not** wipe anything — it only routes to Login and keeps `current.credentials`
until a new sign-in is accepted. Only the separate `Sign out` button calls
`store.wipe()`. Entering a deliberately wrong account is therefore a free way to
QA the login screen, and was used for this. Do not conflate the two paths.

## Q-12 — HD/SD stripping made distinct titles look like duplicates — **FIXED**

**Severity: Medium.** Categories appeared full of duplicated posters. They were
not duplicates: the panel publishes the same show once per quality, and
`NameNormalizer` strips the quality token out of `nameDisplay`, so two genuinely
different rows rendered as the same string. Confirmed by the rail itself, which
carries `RAMADAN EGYPT 2026 SD` (25) and `RAMADAN EGYPT 2026 HD` (43).

**The strip must stay** — it is what makes mixed-direction titles truncate
correctly (see the `name_display` reasoning in `docs/architecture.md`). So the
fix restores the distinction alongside it rather than by undoing it:
`NameNormalizer.qualityOf(raw)` re-derives the stripped token, `BrowseItem`
carries it, and `ContentGrid` draws it as a corner badge (top-**start**; this
panel's watermark sits top-end).

**No schema change.** The raw `name` is already stored on all three entities, so
the badge is derived at map time and cannot drift out of sync with `nameDisplay`.

## Q-13 — Opening any listing re-downloaded the whole catalog — **FIXED**

**Severity: High.** Entering Movies/Live/Series re-ran the full-catalog sync
every time, with the progress line reading "Indexing" over data that was already
complete.

**Root cause:** `BrowseViewModel.init` called `syncer.syncVod()/syncLive()/
syncSeries()` unconditionally, and `CatalogSyncer` had **no freshness check of
its own**. The per-category tier had been TTL-gated since it was written
(`CachedFetch.ensureFresh`, `force = false`); the full-catalog tier never was.
The two tiers simply disagreed, and nothing failed loudly enough to show it.

**Measured on-emulator before the fix:** `complete`/13,264 rows at 07:02:42 →
re-entered the listing 11 minutes later → `indexing` and all 13,264 rows
rewritten. Every entry cost a fresh ~15 MB body.

**Fix:** a `force` parameter plus an `isFresh` gate in `CatalogSyncer`, using the
same 24 h window as `CachedFetch`. Only `complete` counts as fresh — `partial`,
`failed` and an interrupted `indexing` must all re-run — and a backwards clock
cannot pin the catalog fresh forever. `Refresh everything` passes `force = true`.

**`refreshEverything` also now syncs series**, which it never did. That was
latent before and load-bearing after: with the gate in place it is the only way
to re-sync inside the 24 h window, so a series catalog would otherwise have had
no manual escape hatch at all.

**Verified on-emulator after the fix:** entering the listing logged
`series catalog sync: within TTL, keeping cached generation`, `updatedAt` stayed
at 07:13:54, and no rows were rewritten.

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

# Part 2b — The 2026-09-08 design pass: decided and unbuilt

`/impeccable` pass, four revision rounds with the owner. **Every decision below
is settled** — rationale in `docs/decisions.md`, specification in
`docs/ui-scope.md`, visual reference in **`docs/design/comps.html`** (open it in
a browser before touching UI). Nothing here is built yet.

Sequencing: **D-4 and D-5 touch nearly every screen.** Land them first so
everything else is built against the real scale and roles rather than twice.

## UI work

| id | Change | Files | Note |
|---|---|---|---|
| **D-1** | Login: centred 820 px column, server full-width, username+password paired, one action row. Everything focusable above the IME ceiling. | `auth/LoginScreen.kt` | Supersedes the partial Q-11 fix |
| **D-2** | Login error copy to a hard **one line** | `ui/common/ErrorCopy.kt`, `LoginScreen.kt` | Two lines push buttons into the IME |
| **D-3** | Rail 360 dp → **280 dp**; labels wrap to 2 lines, never ellipsised; tooltip only past 2 lines | `ui/browse/CategoryRail.kt` | Truncation recreates Q-12 |
| **D-4** | **Type scale as roles** (`display`/`headline`/`title`/`body`/`label`/`caption`), 12 dp floor. Retires every hand-picked `sp`, including the 10 dp quality badge | new `ui/theme/Type.kt` + ~6 UI files | Wide blast radius |
| **D-5** | Map `Palette` onto **Material colour roles** so contrast variants resolve | `ui/theme/Palette.kt`, theme setup | Wide blast radius |
| **D-6** | **Card titles overlaid** on the poster over a bottom scrim, 2 lines then ellipsise. Live TV keeps titles below (card too short) | `ui/browse/ContentGrid.kt` | **Closes Q-8.** Already in `ui-scope.md`, never implemented |
| **D-7** | **Icon row on Home**: Refresh / Account / Exit as 88 dp pills, label revealed on focus | `ui/home/HomeScreen.kt`, new `ui/common/IconPill.kt` | |
| **D-8** | Browse header uses the **same** icon pill component | `ui/browse/BrowseScreen.kt` | Refresh is a bare text link today |
| **D-9** | **Home tiles get photographs** (520×300, `docs/design/img/`) under a scrim | `ui/home/HomeScreen.kt`, `res/drawable*` | Screen for brand marks |
| **D-10** | **Splash screen** — mark, wordmark, real progress bar | `res/`, `MainActivity.kt` | |
| **D-11** | **App mark + 320×180 TV banner** from `docs/design/img/*.svg` | `res/drawable/`, manifest `android:banner` | |
| **D-12** | `Sign out` below a divider, consequence in the hint, confirm dialog with **default focus on the safe option** | `ui/settings/SettingsScreen.kt` | |

## Feature work

| id | Change | Files |
|---|---|---|
| **D-13** | **Category filter** — persistent bar pinned above the rail, filters category names live | `CategoryRail.kt`, `BrowseViewModel.kt` |
| **D-14** | **Item filter** — grid-header search icon expanding in place into a pill with a clear button; filters the current category while typing, debounced 300 ms on `Dispatchers.IO`; header reports `N of M` | `ContentGrid.kt`, `BrowseViewModel.kt`, DAO query per content type |
| **D-15** | **Subscription** as its own read-only screen behind `Show subscription`; Account keeps only account actions | new `ui/settings/SubscriptionScreen.kt`, `SettingsScreen.kt`, `MainActivity.kt` |
| **D-16** | Subscription copy **reports** `max_connections`, never asserts a limit | `SubscriptionScreen.kt` |
| **D-17** | Move Refresh and Exit **off** Account (they become D-7) | `SettingsScreen.kt` |

## Open questions — answer before or during the build

- **Exit placement.** It becomes one press from the first screen, easy to hit by
  accident on a household remote. Confirm dialog, or last position in the row?
  *Unanswered.*
- **Icon set.** The glyphs in the comps are Unicode placeholders. The mark now
  gives a visual language (one accent stroke, rounded caps) to draw a real set
  against — this is `T-D1`; D-7/D-8 ship with placeholders until it lands.
- **Title overlay coverage.** Overlaying costs the bottom ~15% of the artwork,
  more on two-line titles. Capped at 2 lines + ellipsis, safe here only because
  the quality badge is a separate element.

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
- **Physical-keyboard typing needs `forwardShortcutsToDevice`, and it is stored
  outside the repo and outside the AVD.** It lives in the Windows registry at
  `HKCU\Software\Android Open Source Project\Emulator\set`, value
  `forwardShortcutsToDevice` (REG_SZ `true`/`false`) — the backing store for
  Extended Controls → Settings → General → "Send keyboard shortcuts to". It is
  absent by default, so a fresh emulator profile silently loses it and the host
  keyboard stops typing into the guest. Restore with:
  `Set-ItemProperty -Path 'HKCU:\Software\Android Open Source Project\Emulator\set' -Name forwardShortcutsToDevice -Value true -Type String`
  Takes effect only on emulator restart.
  **This directly conflicts with the Esc-as-Back note above** — `true` sends keys
  to the device (host keyboard types, Esc arrives as `KEYCODE_ESCAPE` and Back
  breaks); `false` keeps emulator shortcuts (Esc is Back, host keyboard does not
  type). Pick per task; they cannot both be satisfied.
- **Prefer pasting over typing for credentials.** `clipboardSharing` is already
  `true` in that same registry key, and `adb shell input text '<value>'` works
  regardless of either setting. Neither needs the host keyboard.
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
