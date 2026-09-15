# Open items — index

Every item below is still open (unverified, undecided, or unbuilt). No dates, no
session narrative — closed items and their lessons live in `docs/decisions.md`
("Archived todo-tree closures") and `docs/ui-scope.md`. Full detail for large,
multi-item plans stays in its own file (`61-section.md`, `62-section.md`,
`63-section.md`); everything else is inlined here directly rather than split
into one file per item, per `D:\HCode\bible-detail\todo-and-bible-maintenance.md`
— a 2-3 line defect does not earn its own file when the index line would say the
same thing. Session-end duty: edit this file directly and confirm the closed
item's story is in `docs/decisions.md` before removing it.

**To QA the login screen without destroying credentials:** Account → "Sign in
to a different account" with a deliberately wrong account. Only `Sign out`
calls `store.wipe()`. Do not clear app data.

## Current priority — Desktop (active work)

- **D-Desktop-15 (owner request, 2026-09-15) — App should launch full screen.**
  Not started. Currently opens windowed; owner wants the desktop app to start
  full screen on launch, same spirit as the TV app's fixed 10-foot assumption.
  Check how `fullScreen`/`onFullScreenChange` state already works for the
  player (`DesktopShell.kt`) and whether the same window-level full-screen API
  can be applied to the app's own top-level window at startup, not just the
  player surface. Consider whether a windowed/restore path should still exist
  (e.g. Escape or a title-bar control) — ask the owner if unclear rather than
  assuming full screen means "no way out."
- **D-Desktop-16 (owner request, 2026-09-15) — Home screen is empty/unintuitive.**
  Not started. Owner: "make the main screen more intelligent and intuitive
  instead of holding empty sections and being not usable." Needs a proper
  audit before building — likely candidates: sections that render even with
  no data (empty "Continue watching"/"Continue with Live TV" blocks per the
  current `HomeScreen.kt`), unclear next-action guidance for a
  first-run/empty-library state, and general information-hierarchy/usability
  review. Owner explicitly invited "all other recommendations" for a more
  user-friendly, optimized main screen — treat this as an open design
  question, not a fixed spec. Consider running `/design-review` or
  `/plan-design-review` on the current Home screen before implementing,
  since this is exactly the kind of design-quality question those skills
  are for, and check `docs/design/design artifacts/desktop-journey-states.html`
  for the original intended Home layout before redesigning from scratch.
- **D-Desktop-17 (owner request, 2026-09-15) — Player should open truly full
  screen.** Not started. Owner: "player should start as full screen holding
  the full screen really" — read as dissatisfaction with the current player
  full-screen behavior specifically (`63-section.md`'s D-Desktop-9 already
  tracks a related but distinct defect: full screen currently maximizes the
  *whole app* rather than giving the player its own native-viewport full-
  screen state with a non-overlapping control dock). Likely the same
  underlying fix as D-Desktop-9 plus making full screen the *default* entry
  state when opening the player, rather than an opt-in toggle. Do these
  together rather than separately — implementing one without the other risks
  contradicting behavior (e.g. player opens windowed then immediately jumps
  full screen, or full-screen-by-default fights D-Desktop-9's windowed/full
  toggle logic).

- `63-section.md` — D-Desktop-1..14: D-Desktop-10's concurrency/freeze
  regression is fixed and verified (2026-09-15). **D-Desktop-14 (High, mpv
  migration) closed 2026-09-15** — playback (35+s real-stream stability),
  input forwarding, and OSC visibility are all implemented, Codex-reviewed,
  and owner-verified live with real mouse/keyboard on the fixture. Also
  open: full-screen, title layout, episode-picker, and rating defects. Read
  `handoff.md` alongside this.

## Android — open defects and pending verification

- **Q-4 — Player back-exit hint.** Fixed in code (`a407bad`); back-navigation
  itself is verified (`KEYCODE_BACK` moves `PlayerActivity` → `MainActivity`).
  The defect was discoverability — nothing on screen said how to leave — and a
  corner hint now rides the controller's visibility. Stays code-reviewed-only
  until the Phase 5 physical-TV session (needs an open stream, `max_connections`
  is 1). Distinct from Q-2: mechanism was fine, affordance was missing.
- **Q-5 — Player reports English audio on an Arabic film.** Not reproduced.
  Needs an open stream to chase, so untouched. Two candidates: the file's AAC
  track carries an `eng` tag from the encoder regardless of actual audio (fix:
  show "Unknown" rather than trust a lone tag), or Media3 falls back to a
  default label. Check `Player.getCurrentTracks()` language tags against the
  file before touching code.
- **Q-8 — Movie title block eats vertical space.** Severity low, user asked to
  defer. Titles render below the poster on up to two lines, pushing grid rhythm
  around for long Arabic titles.
- **Q-14 — Sideloaded APK crashes on launch on a phone, every attempt, no
  logcat captured yet.** This is the blocker — get a trace before guessing:
  `adb logcat -c`, launch, then `adb logcat -d AndroidRuntime:E *:S`. Phone is
  not a target platform (`PRODUCT.md` scopes to Android TV), so a legitimate
  outcome is "unsupported, should fail with a clear message" — but only after
  reading the trace. Candidates to check the trace against, not diagnoses:
  `androidx.tv.material3` is a TV surface library with no guarantee on a
  handset; layouts are built to a fixed 1920×1080 10-foot geometry
  (`docs/ui-scope.md`). Cross-project lesson (PrayerQiblaApp, Xiaomi Mi 10): a
  similar post-install crash was `WorkDatabase` initialization failing under
  R8 shrinking before Flutter started — Tvivo already has `isMinifyEnabled =
  false` and now also `isShrinkResources = false`, and initializes WorkManager
  only after the crash handler is installed, so a comparable failure should
  now surface in on-device Diagnostics on next launch. N-1 (below) is what
  makes that possible; it unblocks this item.
- **Q-21 — Expanded item filter overlaps the category title.** Fixed
  2026-09-10, not yet driven on-emulator. Cause: header `SpaceBetween` row of
  title/actions — expanded filter took 320dp more than the row had, title
  didn't shrink. Fix: title `Column` gets `Modifier.weight(1f)` so actions
  measure first, and the title itself hides while the filter is open (leaving
  only the `N of M` count, the part actually changing). Not a functional
  defect — filter and count both work; needs a screenshot with a long category
  name and the filter open before it's called done.
- **Q-24 — Every text field was a horizontal dead end.** Rail-filter path
  fixed and verified on-emulator 2026-09-13 (RIGHT from the rail filter with
  IME closed now moves focus into the grid). Cause was `dpadFieldNavigation`
  only ever opening a *vertical* escape; fix added a left/right escape
  consumed only on a successful move, plus an explicit `right =
  gridFocusRequester` on the rail field. **Still open:** the expanded
  item-filter path (header's `Clear filter` pill reachability, Left → rail /
  Right → Refresh / Down → grid / Back-to-close on that field) remains part of
  the pending Browse verification batch.
- **Q-25 — Browse screen opens with nothing focused.** Fixed, unverified
  on-emulator. Root cause behind the Q-24 report: `uiautomator dump` showed
  zero `focused="true"` nodes on open, so the first D-pad press ran an
  origin-less 2D search. Fix, three parts: (1) rail requests focus once
  categories arrive via the `LazyColumn`'s `focusGroup`; (2) UP from the first
  row only opens the filter (declared on row 0 only, so UP from row 40 doesn't
  jump to it); (3) the field reads `Search` at rest, not `Filter categories`.
  Worth a test, not just a fix — "does this screen have a focus owner on open"
  is one assertion per screen (T-T2, not yet written).
- **Q-27 — Back from episode picker skipped the pre-run page.** Fixed.
  `Route.SeriesDetail`'s Back now returns to `Route.ItemDetail` instead of
  going straight to the shows grid.
- **Q-28 — Login/phone layout, touch, and keyboard insets.** Implemented,
  handset verification pending (Mi 10, `umi_eea`, 1080×2340@440dpi or
  equivalent). Built: compact non-TV devices get an available-width vertical
  form with IME-aware scrolling; TV keeps its fixed D-pad layout;
  `MainActivity` retains in-progress login/account-switch state across the
  portrait/landscape configuration change; Browse measures its content column
  from width remaining after the fixed rail; `PlayerActivity` sets
  `FLAG_KEEP_SCREEN_ON` during playback; manual refresh resets focus to the
  same safe default as entry; `DiagnosticLog` persists a bounded 200-entry
  credential-safe log with Export/Clear. **Durable rule:** pointer press/release
  proves only that input arrived, not that a `Card` callback or navigation
  occurred — diagnose the whole chain (press → release → activation →
  `onSelect` → route → state mutation → destination composition) and find the
  first missing link before blaming orientation/coordinates. Green
  unit/build checks validate none of handset touch, IME, focus, rotation, or
  visible navigation. **Still open:** Home tiles/Live/Movies/Series nav and the
  diagnostic event chain (Live+Movies re-driven end to end, Series already
  passing, last verified 2026-09-12); Login/IME on a handset (use Account →
  "Sign in to a different account" with a wrong account — never Sign out);
  rightmost browse column clipping, sleep prevention, category-refresh focus
  reset (implemented, unverified); repeated image-load
  `IllegalStateException` warnings on Mi 10 without a crash (investigate
  separately if it affects visible artwork); player full-screen usable-window
  bounds, Episodes-overlay-in-player, compact Back button (not built); series
  detail's ambiguous episode-list play affordance should become a labelled
  `Episodes`/`View episodes` action; a Home Settings screen is unscoped, no
  priority set.
- **U-13 — Continue Watching resume-point deletion bug.** Fixed, not fully
  verified (needs an open stream). `PlaybackStateRepository.savePosition`
  collapsed two cases into one `remove`: the 60s floor is right for filtering
  accidental opens, but it was also deleting resume points from a forty-minute
  watch when the *next* visit happened to be short. Too-short now declines to
  write instead of removing; only `finished` removes. The three resume rows on
  the device (3-6% watched) are exactly what the old code would have
  destroyed next visit, and they survived the v5 migration intact.
- **U-14 — Player seek.** Written (`SEEK_INCREMENT_MS` = 10s, symmetric,
  intercepted in `onKeyDown` so it doesn't depend on `DefaultTimeBar` focus),
  unverifiable without an open stream (`max_connections` is 1). Lands with
  Q-4/Q-5 and the physical-TV session.
- **QA-3 — Arabic description paragraphs resolve LTR.** Implemented, emulator
  verification pending. Glyph order and bidi-isolation of embedded Latin runs
  are correct; paragraph *direction* is not, so a short final line hangs left
  instead of flush right. Reproduces on the movie pre-run page and the
  episode-picker header — same class as the title-truncation fix, in body text.
- **QA-4 — Focused grid card clips at the bottom edge.** Implemented, emulator
  verification pending. Scrolling down keeps the focused card as the last
  visible row with its lower portion cut off; the row above the viewport
  renders as a bare title strip with no poster. Worse on an overscanning TV.
- **QA-5 — Live channel cards with no logo render as bare rectangles.**
  Implemented, emulator verification pending. 13 of 15 cards on the Live TV
  landing had no artwork and no fallback (no initial, no generic glyph). May be
  upstream absence; the empty state is unhandled either way. Same gap on the
  live pre-run page.
- **QA-6 — Card title contrast and mid-word breaks.** Implemented, emulator
  verification pending. Two-line titles grow upward into the poster while
  one-line titles sit below it (inconsistent overlay card to card); scrim isn't
  strong enough over saturated art; titles break mid-word (`BTS.The.Retur` /
  `n.2026`).
- **QA-7 — Rail's "last-active" tint is nearly invisible.** Implemented,
  emulator verification pending. The two-state contract (focus frame +
  last-active) is built, but the tint is only a few percent lighter than the
  rail background — hard to tell which category the grid belongs to when
  focus is elsewhere. Same on the episode picker's season list.
- **QA-8 — Entering Series focused the header search and opened the IME.**
  Not reproduced — Series and Live both focused the rail cleanly across
  repeated attempts. `BrowseScreen` already has a comment about this class of
  bug (RIGHT out of the rail opening search instead of crossing to the grid).
  Best hypothesis: a race where the rail has no categories yet, so the first
  D-pad press runs an origin-less 2D search and the header field wins. **If it
  recurs, note whether the catalog was mid-sync.**
- **QA-9 — UP from the first grid row targets the rail filter, not
  item-search.** Implemented, emulator verification pending. From the first
  row of the Movies grid, pressing UP should transfer focus to the right-pane
  item-search control, not open the left `Filter categories` field/IME.
- **QA-11 — Playback connection not released before the next stream starts.**
  Implemented, emulator verification pending. Severity high: leaving a movie
  stream and starting Live showed "Another device may be using this account"
  with no other device active; force-stopping released the stale session.
  `PlayerActivity` now snapshots the VOD resume position, then detaches, stops
  and releases Media3 *before* writing that snapshot to Room — removes the
  suspected teardown gap where the player stayed alive during a synchronous DB
  write with no explicit stop. Verify by leaving a movie and immediately
  starting Live in one approved emulator session.
- **T-D4 — Should Diagnostics record routine events, not just failures?** Not
  a defect. Every `DiagnosticLog` call site today is app start, a sync
  lifecycle event, or a failure, so a healthy session leaves the screen nearly
  empty (see Q-22 in `docs/decisions.md`) — defensible, since it's a
  diagnostics screen not an activity log, but it can't answer "what did the
  app just do," only "what went wrong." Adding category selection and
  `get_vod_info`/`get_series_info` fetch events would answer both, at the cost
  of noise. Decide before Phase 5 closes.

## Part 1b — Platform constraints, not defects

Recorded so they are not re-investigated as bugs.

- **T-D2 — List key-repeat, fast-scroll, and jump affordance in `ALL`.**
  Undefined, and the full-catalog sync makes it matter: holding DOWN in `ALL`
  traverses 9,750 rows. T-D2b (key-repeat/fast-scroll acceleration, and
  whether focus stays 1:1 with key events or switches to page-jumps past a
  threshold) and T-D2c (jump affordance — the accepted cost recorded in
  `docs/decisions.md` is that without one, the end of a 9,750-row list is
  unreachable in practice; revisit if `ALL` proves a dead end in use) are both
  best judged on hardware — land with the Phase 5 physical-TV session.

## Part 2c — filed feature backlog, not yet scheduled

Ordered by **cost against what they unblock**: N-1..N-3 are cheap and
something else is waiting on each; N-4/N-5 are cheap and self-contained; N-6+
are real features. Nothing here is built unless noted. File the decision in
`docs/decisions.md` or `docs/ui-scope.md` when one of these is taken.

- **N-1 — Unhandled-exception hook into `DiagnosticLog`.** Implemented
  2026-09-11 as `TvivoApplication` + `CrashDiagnostics`; 151 unit tests pass;
  device verification pending. Unblocks Q-14: no logcat has ever been captured
  from the crashing phone, so this turns "reproducible but untraceable" into
  "open Diagnostics and read it," on any device including the physical TV
  later. Writes synchronously on the crashing thread (a coroutine launch would
  not finish) — records only exception types and bounded stack frames, never
  messages, syncs the file before delegating to Android's prior handler, then
  consumes/deletes it on next launch. Ties into T-D4.
- **N-2 — Define what the user sees when `max_connections` is exhausted.**
  Implemented, physical-TV verification pending. `max_connections` is `1` on
  this account, so a second concurrent stream (another device, or the app's
  own previous player not yet torn down) is the **first playback failure a
  real user will hit**, not an edge case — currently surfaces as whatever
  opaque Media3 error the stream open produces. Needs the `ErrorCopy` pattern
  and a decision on whether to read `active_connections` from `server_info`
  before opening a stream to pre-empt it. **The copy must report, never
  assert a limit** — this screen is the one most tempted to say "you can only
  watch one thing at a time," which `CLAUDE.md` forbids. Gates the
  physical-TV session, the only place this can be provoked on purpose.
- **N-3 — Cached-catalog-but-no-network path.** Implemented, emulator
  verification pending. The app assumes the panel is reachable; a router
  outage or the TV waking before network is up is an ordinary state, not a
  failure, and the catalog is fully cached locally so browsing *should* work.
  `CachedFetch` distinguishes fresh from stale; what's missing is a UI
  contract over "stale and cannot refresh." Blocks Phase 5's error-state work
  from being complete — fold in rather than build separately.
- **N-4 — "This category did not sync" is indistinguishable from "empty".**
  Implemented, emulator verification pending. The zero-row guard records
  `partial` but nothing reads it, so a category that failed to sync and one
  that's genuinely empty render identically, and a refresh looks like it did
  nothing. Surface `partial` as a distinct state with a retry affordance the
  empty state doesn't need. Depends on N-3's state table — do them together.
- **N-5 — Resume position must survive process death.** Local coverage
  implemented, hardware verification pending. `resume_positions` and
  `favourites` are the only user data the app holds; backing out of the
  player is tested, the player being *killed* (TV reclaiming memory, user
  pressing Home mid-film) is not. If the write only happens on clean teardown,
  the row is lost silently. Check the write path first (`onPause` vs `onStop`
  vs teardown) — may already be correct. Related: U-13 was a data-loss bug in
  this same table, reason enough not to assume.
- **N-6 — Episode-level Continue Watching, and "next episode".** Not started,
  depends on N-5 (don't start if the resume write itself is lossy).
  `CONTINUE WATCHING` reads `resume_positions`, but whether a part-watched
  *episode* lands there is undefined; resuming the same episode is right
  mid-episode, offering the *next* one is right after a finished episode. A
  series row should show the show, not the episode, as its title. Sub-60s
  replay handling already has precedent in `5148343`.
- **N-8 — One-click diagnostic reporting from the TV.** Depends on N-1. A
  file export isn't useful on a TV (no file manager/email client/second
  device path); replace with a remote-first **Report a problem** action:
  consent screen (default focus Cancel) stating exactly what's sent → send a
  strictly allowlisted report over HTTPS → short reference code like
  `TV-4K7M2` on acceptance, no keyboard/copying/second device needed → if
  offline, retain and retry via WorkManager with the queued state visible.
  Send to a small app-owned endpoint (may email the developer, may later file
  a sanitized GitHub issue) — **never create issues directly from the APK**
  (an embedded token in a public package is extractable), and never post
  detailed diagnostics to a public issue, only a coarse summary plus the
  private reference. Build the payload from an explicit allowlist (app/build
  version, Android API + TV model, timestamp, current screen, normalized
  failure category, recent redacted diagnostic events, coarse
  cached/partial/sync/playback outcomes) rather than collecting broadly and
  redacting after. Never send credentials, panel host/port, request/playback
  URLs, account/content ids, titles, category names, search text, exact
  resume positions, or raw exception messages. Normal cert validation,
  payload/retention limits, server-side rate limiting, a visible **Delete
  local diagnostics** action, and document the data sent before enabling.
- **N-9 — Profile and optimize Room retrieval for instant-feeling browse.**
  Not started. The local catalog is large enough that DB latency is product
  behavior. Budgets to define before touching the schema: cached
  category/header data visible within 100ms, first grid page within 200ms,
  filter results within 300ms after the existing debounce, no main-thread
  disk I/O or focus loss while paging invalidates. Measure worst cases on the
  real DB (large category, 48k-row VOD, Recently Added, Continue Watching,
  Favourites, filtered query, BACK at a deep grid position) with `EXPLAIN
  QUERY PLAN`, query timings, PagingSource invalidations, allocation/GC
  pressure, sync/read contention; add benchmark fixtures preserving the real
  order of magnitude without provider data. Optimize only from observed
  plans — composite covering indexes against actual WHERE/ORDER BY, filtering
  joins/lookups used by virtual folders, bounded projections, transaction
  size/WAL behavior during generation flips, duplicate-scan count queries.
  Every added index must justify its sync/storage cost. Do not disable
  placeholders or stable keys — deep focus restoration depends on them. Treat
  budgets as regression gates (Macrobenchmark/instrumented on emulator, final
  confirmation on physical TV after deployment approval).
- **N-10 — Handset touch activation path needs form-factor flexibility.**
  Deferred until another non-TV form factor is actively supported. The
  2026-09-12 handset Home tile fix extracts `detectTapGestures` for non-TV
  devices specifically and assumes all non-TV devices behave the same;
  tablets may need a different touch/D-pad/focus contract. **Rule:** do not
  generalize the handset touch workaround to other non-TV devices without
  device evidence — verify touch input, focus traversal, and card activation
  independently on each new form factor before merging. Current scope:
  phone-only.
- **N-11 — Diagnostic log verbosity on production.** Production-facing
  decision, not code. `DiagnosticLog` now has a structured schema with call
  sites at every navigation/sync/failure point, queryable and safe to export
  (redaction enforced in the logger, not at call sites). A healthy 45-minute
  emulator session produces only 4 lines (app start + 3 TTL skips) — correct
  and expected. On a real TV the log is persistent and bounded (200 entries);
  measure actual verbosity on hardware during Phase 5. If routine events
  (category selection, image loading, non-error sync progress) are worth
  recording, decide that explicitly as part of T-D4 rather than retroactively
  adding call sites.
- **N-12 — Database size profiling and optimization.** Measurement first,
  optimization only from observed data. Local catalog: 48,780 VOD rows, 6,425
  live channels, 13,279 series shows plus per-show episode lists (some 100+
  episodes); Room caches poster images downsampled to 220×330/220×124px. No
  manifest size constraint or user-visible storage warning yet. Before Phase
  5 closes: measure real DB footprint on the real panel (total `.db` size,
  per-table sizes, image cache size, whether TTL+generation logic leaves dead
  rows/orphaned blobs), add a `diagnostics/DbMetrics` query surfaced on the
  Diagnostics screen. Check rows retained past TTL (generation flips should
  clean them — verify the migration), orphaned image files on loader failure,
  index bloat. If real-hardware size exceeds a reasonable threshold (e.g.
  >500MB), decide user-triggered cleanup, a reduced full-catalog sync window,
  or incremental/differential sync instead of replace-all.
- `53-section.md` — N-13 (RAM/heap profiling on the physical TV), N-14
  (Play-distributed 30-day demo entitlement — full backend + APK + launch-gate
  spec, not started, requires an app-owned backend before client work), N-16
  (Android player viewport/chrome quality — carries the desktop POC's
  black-frame lesson back to Android TV; worth revisiting once the desktop
  player-surface fix, D-Desktop-10, lands, since the root cause may be
  shared). None started; kept as its own file since N-14 alone is a full
  backend design, not a 2-line item.

## Design backlog

- **D-9, D-10, D-11 — never built.** The rest of the original D-1..D-12 UI
  batch is built (see `docs/decisions.md`, "Archived todo-tree closures").
  **D-9:** Home tiles get photographs (520×300, `docs/design/img/`) under a
  scrim (`ui/home/HomeScreen.kt`, `res/drawable*`). **D-10:** splash screen —
  mark, wordmark, real progress bar (`res/`, `MainActivity.kt`). **D-11:** app
  mark + 320×180 TV banner from `docs/design/img/*.svg` (`res/drawable/`,
  manifest `android:banner`). D-9/D-11 depend on T-D1 (real icon/design
  system) landing first for their artwork.
- **T-D1 — Finish the design system (`DESIGN.md`).** Run
  `/design-consultation` and produce a `DESIGN.md` covering what's still
  missing: motion spec, icon set, product wordmark. `/plan-design-review`
  (2026-09-07) rated design-system alignment 0/10 because there was nothing to
  align to — both outside reviewers independently flagged the same gap.
  Already closed: colour/contrast (dark teal surface ramp, Claude-orange
  accent, every pair WCAG-measured — see "Colour" in `docs/ui-scope.md`). Now
  more urgent than when filed: Q-1 and Q-3 (see `docs/decisions.md`) were both
  real screens drifting from the colour/focus system, the exact failure mode
  a design system exists to prevent. Suggested timing: after Q-1/Q-3, before
  Phase 5.
- **T-D3 — Newest-first sort toggle per category grid.** See
  `61-section.md` for full detail.
- **T-A1 — Multiple saved accounts.** Speculative, not worth doing until more
  than one panel is genuinely in use. Today the store holds one credential
  set; switching replaces it, so returning means re-typing. The cached
  catalog is already account-scoped by `accountId`, so the data layer needs
  nothing — missing piece is a list of credential sets in `CredentialsStore`
  plus a "current" pointer, and a picker UI.

## Phase 5 — Hardening (umbrella, in progress)

RTL verification, empty/loading/error states, `RefreshWorker`, on-device
diagnostic log. Physical-TV validation happens once here — the live `.ts`
path carries the most exposure under that choice. **Manual refresh is done**,
at both scopes: per-category in the browse header, and `Refresh everything` on
the Account screen (every category plus both catalogs, TTL ignored). Fold
N-3/N-4 into this as the error-state work rather than building the state
table twice.

## Player enhancement plan — approved for planning

- `62-section.md` — P-1..P-6: real-device player baseline, then remote-first
  controls, series in-player navigation, tracks/speed/fit/completion,
  lifecycle hardening, and handset player presentation. P-1 (hardware
  baseline) blocks P-2..P-6.

## Suggested order for the next Android session

1. Get the physical TV in — live `.ts`, `/series/` episode playback,
   `max_connections` behaviour, and remote key-repeat are all unverifiable on
   the emulator and currently shipped-but-never-executed.
2. P-1 — player baseline on hardware, gates `62-section.md`'s P-2..P-6.
3. Phase 5 hardening, folding N-3/N-4 in as the error-state work rather than
   building the state table twice.
4. N-1 first among the Part 2c items (small, unblocks Q-14); N-2 before the
   physical-TV session (the only place an exhausted `max_connections` can be
   provoked on purpose).

Desktop work (above) takes priority over this list per the current handoff.
