# TODOs

Open work, with enough context to pick up cold. Items here were considered and
consciously deferred, or found by testing and not yet fixed — not a backlog of
ideas.

Everything that was *not* deferred is folded into `docs/ui-scope.md`,
`docs/architecture.md` and `docs/decisions.md`.

**Status as of 2026-09-07:** Phases 0, 1 and 2 are built and running against the
real panel on the API 34 Android TV emulator. 66 unit tests pass. The full
catalog synced (48,754 rows) and playback works end to end. Phases 3-5 are not
started. The defects below were found by hands-on testing after Phase 2.

---

# Part 1 — Open defects (QA pass, 2026-09-07)

Full report with evidence and screenshots:
`.gstack/qa-reports/qa-report-tvivo-2026-09-07.md`. Health score 70/100.

Every one of these is a UI or interaction defect. None were catchable by the
unit suite, and all of them passed "compiles and launches" — the same pattern as
the three login bugs found the same way earlier that day.

## Q-1 — Home screen: third tile crushed, wrong colours, unreadable labels
**Severity: Critical. Fixed in code (`a407bad`), not yet verified on-emulator.**
`ui/home/HomeScreen.kt`

"Live TV" and "Movies" render full size; "Series" is squeezed to a narrow sliver
with its label broken one letter per line. This is the first screen after login.

Three defects in one screen, all needing separate fixes:
- **Overflow.** Three tiles fixed at 320×180 dp plus 32 dp gaps and 96 dp side
  padding exceed the available width at this density, so `Row` compresses the
  last child. Needs weight-based or responsive sizing, not fixed `.size()`.
- **Wrong surface colour.** Tiles use the default `tv-material3` `Card`
  container colour instead of `Palette.Elevated`.
- **Contrast failure.** `Palette.Ink` on that near-white card is barely legible.
  The screen bypasses the WCAG-measured colour system entirely.

## Q-2 — No in-app back stack; Back exits the app from Browse
**Severity: High. Fixed in code (`a407bad`), not yet verified on-emulator.**
`MainActivity.kt`

Back on the Browse screen leaves the app to the launcher. There is no way from
Movies back to the Live/Movies/Series chooser without relaunching.

Structural, not cosmetic: `MainActivity` holds its route in a plain
`mutableStateOf` with no `BackHandler`, so Back falls through to finishing the
Activity. **Every screen Phases 3-5 add inherits this**, so fix it before Live TV.

## Q-3 — Focus is not identifiable on buttons and Home tiles
**Severity: High. Fixed in code (`a407bad`), not yet verified on-emulator.**
`ui/home/HomeScreen.kt`, `auth/LoginScreen.kt`

Hoisted into `ui/common/TvFocusFrame.kt` (`Modifier.tvFocusFrame()`), reusing
the grid's static accent-border pattern, and applied to Home tiles and the
Login buttons.

On a D-pad device, "where am I" is the only navigational state the user has. The
focused Home tile shows only a faint grey border; the login screen's
`Show password` / `Sign in` buttons give no clear focused state.

The grid cards implement the static accent frame from `ui-scope.md` correctly —
buttons and tiles do not. The focus contract is applied inconsistently by
component type. Consider hoisting it into one shared modifier so a third
component type cannot diverge again.

## Q-4 — Player has no visible way back
**Severity: Medium. Fixed in code (`a407bad`), not yet verified on-emulator.**
`ui/player/PlayerActivity.kt`

Back *works* (verified: `KEYCODE_BACK` moves `PlayerActivity` → `MainActivity`).
The defect is discoverability — nothing on screen communicates how to leave, so
the exit depends on the user already knowing about the remote's Back button.

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

## Q-6 — Show password sits away from the field it controls
**Severity: Medium.** Design change, not a defect.

User asked for the toggle to sit adjacent to the password field, and for the row
beneath the form to carry `Clear` and `Sign in`. Belongs in `ui-scope.md` before
implementation.

## Q-7 — Action buttons hidden by the IME while typing the password
**Severity: Medium.** `auth/LoginScreen.kt`

Partially addressed (`imePadding`, scrollable column, error moved above the
buttons, keyboard dismissed on submit), but the button row can still sit behind
the IME at that field position. The tension is a 440 dp column against a
bottom-anchored IME covering roughly the lower half of the screen.

## Q-8 — Movie title block consumes excessive vertical space
**Severity: Low. User asked to defer.**

Titles render below the poster on up to two lines, so long Arabic titles push
the grid rhythm around.

---

# Part 2 — Missing test coverage

## T-T1 — Room DAO and transaction tests
**`CLAUDE.md` asks for these and they do not exist.**

66 unit tests cover `ServerAddress`, `StreamUrlBuilder`, `NameNormalizer`,
`VodStreamParser`, `ErrorMapper` and `CachedFetch`. Missing: in-memory Room
tests for DAO behaviour and, more importantly, **transaction** behaviour —
`replaceCategory`'s delete-then-insert, and the generation flip in
`CatalogSyncer`. Those are exactly the paths where a silent bug loses a user's
whole catalog.

Needs Robolectric, so slower to write than the pure-logic tests.

## T-T2 — Instrumented D-pad focus traversal tests
Compose focus tests on the emulator, per `CLAUDE.md`. Q-1 through Q-3 are all
focus/layout defects that shipped despite a green build — this is the class of
test that would catch them.

---

# Part 3 — Remaining build phases

## Phase 3 — Live TV
Copies the Phase 2 movies pattern with three documented differences:
- **`CHANNEL(220×124 px)` 16:9 card**, not the poster card. Fit and letterbox on
  a neutral tile, never crop — cropping a channel logo to 2:3 destroys it.
- **`ext`**, not `container_extension`.
- **No seek** — progressive live streams cannot seek, so hide the scrub bar.

**Do Q-2 first.** Live TV would otherwise inherit the missing back stack.

**Unverified precondition:** whether `get_live_streams` answers with no
`category_id`. Confirmed for `get_vod_streams` only. If it rejects, live falls
back to per-category fetches without affecting movies.

## Phase 4 — Series
One extra layer: category → shows (`get_series`, **not** `get_series_streams`)
→ `get_series_info` → season/episode picker → play. Episodes arrive as an object
keyed by season number **as a string** — the parser must handle that shape, and
it needs a MockWebServer test. `get_series_info` is fetched lazily per show, with
its own TTL. Same unverified `category_id` question as Phase 3.

## Phase 5 — Hardening
Manual refresh, RTL verification, empty/loading/error states, `RefreshWorker`,
on-device diagnostic log. Physical-TV validation happens once here — the live
`.ts` path carries the most exposure under that choice.

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
