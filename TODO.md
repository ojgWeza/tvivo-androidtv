# Tvivo TODO

Open work only, grouped by area. No dates, no session narrative.

See `TODO-ARCHIVE.md` for closed items and `docs/decisions.md` for rationale.

**Session-end duty:** Move closed items to `TODO-ARCHIVE.md` (verbatim, with full writeup), replace the line here with a one-line pointer, and confirm the closure note already exists in `docs/decisions.md`.

---

## Desktop (Priority — active batch)

### D-Desktop-16 (partially complete; revise `D-DESKTOP-16-PLAN.md` before implementation)

Remaining work across suggestions, recently-added ordering, fullscreen/OSC, and search UI.

- [ ] **Req 3: Recently Added verification** — Implementation and repository coverage are present: `added_at DESC + id ASC`, 500 limit, index, and a zero fallback. Verify the desktop UI against a populated catalog.
- [ ] **Req 1: Suggestions verification** — Implemented as a 20-title, session-stable, rating-weighted shelf; mouse drag horizontally reveals hidden cards without changing order. Verify the interaction and shelf density in the desktop UI.
- [ ] **Req 6: Search UI verification** — Implemented as a contained collapsible pill. Verify expansion, clear-then-collapse, Escape, saved state, and filtered results in the desktop UI.
- [ ] **Req 4: Fullscreen & OSC Title verification** — Implementation is present: fullscreen hides the header and `force-media-title` is set on the mpv owner thread before `loadfile`. Verify with a local fixture for every content type.

### D-Desktop-20 (new visual/navigation batch)

- [ ] **Main-screen Exit control** — Add an intentional, reachable Exit button from the main screen, with behavior and confirmation appropriate to desktop use.
- [ ] **Login visual alignment** — Redesign Login to use the same dark-theme layout, colour roles, typography, and component language as the rest of the desktop app.
- [ ] **Dark splash screen** — Replace the white startup/splash screen with a themed dark presentation consistent with the application palette.

### D-Desktop-21 — Tvivo-owned player controls (planned)

- [ ] **Replace libmpv OSC** — Implement `D-DESKTOP-21-OWNED-PLAYER-OVERLAY-PLAN.md`: disable mpv's OSC and ship a fully Tvivo-owned Compose overlay controller. Begin with a libmpv render-API feasibility spike because the current heavyweight `wid` Canvas cannot safely accept Compose controls above it. Fullscreen/cinema mode is blocked on fixture verification because prior mpv-property attempts could not resize the Compose parent.
- [ ] **D-Desktop-21 spike gate remains blocked** — The fixture-only two-window host and Win32 `WM_NCHITTEST` bridge were added without changing `MpvPlayer.kt` or `DesktopShell.kt`. A local MKV reached `Playing` for over 60 seconds with observed working-set/private-byte samples, but input/alignment/DPI/z-order evidence, five lifecycle cycles, graceful teardown, and a measured `wid` baseline comparison were not completed. Do not begin production overlay work until the remaining 0.1.3 acceptance evidence is captured.

### Desktop distribution & performance

- [ ] **Windows distribution (MSI)** — Configure Compose native Windows packaging for a signed-ready MSI installer: bundled compatible JRE, application icon, product/version/vendor metadata, Start Menu and uninstall integration, and the libmpv runtime archive. Produce an optional portable EXE only if a verified use case remains after the MSI path works.
- [ ] **Release documentation correction** — Update `desktop/README.md` to describe the current libmpv-based player/runtime accurately; it still refers to removed LibVLC code.
- [ ] **Artwork cache and decode budget** — Replace full-resolution `URL.readBytes()` image loading with cancellable, card-size-downsampled artwork; retain a bounded memory cache and bounded disk cache so scrolling does not repeatedly download/decode images or grow the heap without limit.
- [ ] **Suggestion sampling at catalog scale** — Replace the full-catalog quadratic `weightedShuffle()` with deterministic-testable weighted sampling without replacement that selects only the required shelf size (20), without materializing/shuffling every catalog item.
- [ ] **Catalog browse memory/query budget** — Profile the largest provider categories and introduce bounded/paged retrieval where needed; preserve complete discoverability while avoiding a full catalog/result set and artwork data accumulating in RAM.
- [ ] **Player lifecycle audit** — Verify that leaving playback immediately releases mpv native resources, video surfaces, callbacks, and retained frames; add focused tests/diagnostics that never open a provider stream.
- [ ] **Desktop performance acceptance budget** — Define and record baseline/target measurements for idle Home, 60-second large-catalog scrolling, and local-fixture playback: working set, private bytes, CPU, network/image-cache behavior, and post-navigation recovery. Verify no sustained memory growth across repeat cycles.

**Old regressions (superseded by D-Desktop-16):**
- D-Desktop-15-REGR, D-Desktop-16-REGR, D-Desktop-17-REGR, D-Desktop-18-REGR, D-Desktop-19-REGR (scope rolled into Req 4,6,5)
- D-Desktop-16f, D-Desktop-17 (handled by Req 4)

---

## Android TV — Focus & Navigation

- [ ] **Q-24 (Browse item-filter path):** Expanded item-filter header navigation (`Clear filter` pill reachability, Left/Right/Down escape, Back-to-close) unverified on-emulator. Focus test (T-T2) not yet written.
- [ ] **Q-25 (Browse entry focus):** "Does this screen have a focus owner on open" — assertion needed. Fix code-reviewed, test pending.
- [ ] **QA-8 (Series entry focus):** Entering Series focused header search and opened IME (not reproduced). Best hypothesis: race where rail has no categories on entry. If recurs, note whether catalog was mid-sync.
- [ ] **QA-9 (Grid UP focus):** UP from first grid row should target item-search, not category-filter. Implemented, emulator verification pending.

---

## Android TV — Playback & Player

- [ ] **Q-4:** Player back-exit hint. Fixed, unverified (needs open stream; `max_connections` = 1). Back-navigation verified, affordance tested.
- [ ] **Q-5:** Player reports English audio on Arabic film. Not reproduced; needs open stream. Candidates: AAC track carries `eng` tag from encoder, or Media3 defaults to English.
- [ ] **Q-11 (Playback connection release):** High severity. Player released connection before starting next stream — could see "Another device using this account" falsely. Fix code-reviewed, emulator session verification pending (leave movie, immediately start Live).
- [ ] **U-14 (Player seek):** Written (`SEEK_INCREMENT_MS` = 10s, symmetric, intercepted before `DefaultTimeBar` focus), unverifiable without open stream. Lands with Q-4/Q-5.

---

## Android TV — UI & Rendering

- [ ] **Q-8:** Movie title block eats vertical space. Low severity, user requested defer. Titles render below poster on up to two lines, pushing grid rhythm for long Arabic titles.
- [ ] **Q-21 (Item filter overlap):** Fixed, not yet driven on-emulator. Expanded filter overlaps category title; fix: title `Column` gets `Modifier.weight(1f)` so actions measure first. Screenshot needed with long category name + filter open.
- [ ] **QA-3 (Arabic text direction):** Implemented, emulator verification pending. Paragraph *direction* wrong; short final line hangs left instead of flush right (movie pre-run page, episode-picker header).
- [ ] **QA-4 (Focused card clipping):** Implemented, emulator verification pending. Scrolling down clips bottom of focused card; row above viewport renders as bare title strip.
- [ ] **QA-5 (Live channel missing logos):** Implemented, emulator verification pending. 13 of 15 Live cards had no artwork; fallback unimplemented (no initial, no glyph).
- [ ] **QA-6 (Card title contrast & breaks):** Implemented, emulator verification pending. Two-line titles overlay inconsistently; scrim too weak over saturated art; titles break mid-word.
- [ ] **QA-7 (Rail last-active tint):** Implemented, emulator verification pending. Two-state contract built but tint nearly invisible (few percent lighter than rail background).

---

## Android TV — Focus Restoration & Input

- [ ] **Browse column clipping, sleep prevention, category-refresh focus reset:** Implemented, unverified. Part of Q-28 handset verification batch.
- [ ] **Home tiles/Live/Movies/Series navigation & diagnostic event chain:** Unverified on handset (Mi 10). Live+Movies re-driven end-to-end; Series passing as of 2026-09-12; last verified under Q-28.
- [ ] **Login/IME on handset:** Unverified. Use Account → "Sign in to a different account" with wrong account — never Sign out.

---

## Android TV — Streaming & Resilience

- [ ] **Q-14 (APK crash on phone):** High blocker. Sideloaded APK crashes on launch, every attempt, no logcat captured. Phone is not target (scope is Android TV), but must get trace before determining if "unsupported, should fail clearly" is the answer. Get `adb logcat -c`, launch, `adb logcat -d AndroidRuntime:E *:S`. Candidates: `androidx.tv.material3` TV-only; layout geometry fixed to 1920×1080. See 2026-09-14 cross-project lesson (PrayerQiblaApp, Xiaomi Mi 10).
- [ ] **Repeated image-load `IllegalStateException` on Mi 10:** No crash, investigate separately if it affects visible artwork.

---

## Android TV — Data Persistence

- [ ] **U-13 (Resume-point deletion bug):** Fixed, not fully verified (needs open stream). `PlaybackStateRepository.savePosition` collapsed two cases; 60s floor for filtering accidental opens was also deleting resume points on next visit. Now declines to write instead of removing; only `finished` removes.

---

## Android TV — Diagnostics & Logging

- [ ] **T-D4 (Routine event logging):** Not a defect. `DiagnosticLog` today records start, sync lifecycle, failures only — nearly empty on healthy session. Adding category selection and `get_vod_info`/`get_series_info` events would add visibility at cost of verbosity.

---

## Windows (WinUI spike) — Code Review Findings (Codex, 2026-09-22)

Full-branch review of `codex/winui-catalog-design-plan` (includes the `docs/design/winui-media-prototype/` design prototype). Source review only — no build/tests/browser run.

- [ ] **Catalog channel activation cannot play provider channels (high).** `XtreamInfrastructure.cs:122` builds `StreamSource` without a `DirectUri`; the click path in `MainWindow.xaml.cs:85` rejects any source lacking one. `StreamUrlBuilder.Live` exists but is never called on this path — selecting a real catalog channel reports "no direct stream URI" instead of playing.
- [ ] **Account identity collides across HTTP/HTTPS (high).** `AccountIdentity.For` (`XtreamInfrastructure.cs:139`) hashes host, port, and username but omits scheme. The same host/port/username configured once as `http://` and once as `https://` collapse to one identity, so cached catalog rows and the provider's in-memory connection map can overwrite/reuse each other's state.
- [ ] **Design prototype navigation dead ends.** `docs/design/winui-media-prototype/app.js:1` — Account is a non-clickable `<span>` (no nav target); Search and Live TV fall through to Home; Account buttons have no handlers; player episode rows do nothing. Spotlight's "Open movie" and "Play episode" both route to the same mock player, always stuck on "Season 2, Episode 4." `series.includes(item)` can mislabel a movie as a series since the `movies`/`series` arrays share titles.
- [ ] **Prototype vs. shipped WinUI shell are visually two different products.** Prototype uses teal/orange top-nav (`docs/design/winui-media-prototype/styles.css:1`); shipped shell uses blue-accent left-nav (`App.xaml:10`, `MainWindow.xaml:7`). May be intentional design exploration, but the relationship isn't documented — confirm intent before more prototype investment.
- [ ] **Prototype files are single-line minified blobs.** `app.js`, `index.html`, `styles.css` under `docs/design/winui-media-prototype/` are each one unformatted line — hostile to diffing/review going forward.
- [ ] **Raw exception text surfaced to UI.** `ProviderSetupPage.xaml.cs:63` shows raw exception messages on setup errors; replace with sanitized, actionable copy (exception text can leak implementation/request details).
- Catalog cache-first loading (`CatalogLandingPage.xaml.cs:136`, groups/paging off the UI dispatcher) reviewed as sound; responsiveness thresholds in the design plan remain acceptance targets, not measured results.

---

## Windows (WinUI) — Compose Parity Roadmap (Codex, 2026-09-22)

Source: comparison of the mature Compose desktop app (`desktop/`) against the WinUI spike (`windows-spike/`), listing what Compose already has (or has explicitly planned) that WinUI does not yet implement. This is a **development roadmap, not a defect list** — WinUI is intentionally early-stage and Compose is the working reference it is being brought up to. `TODO-ARCHIVE.md` was not found at repo root, so Compose's archived/closed requirements could not be cross-checked.

**Gate — do not start Stage 1 until:**
- [x] **The HTML design prototype (`docs/design/winui-media-prototype/`) is completed first**, as the foundation the WinUI app will be built from. Completed by Codex 2026-09-22: added Movies, Series (with season tabs + episode list + resume indicator), Live TV (channel groups), Account & Settings (subscription status/expiry/max connections, sign-out, exit), and a branded dark splash screen (1.1s auto-transition to Home), matching the palette/typography/card treatment established on My Tvivo. Verified live in-browser by Claude on a local static server — all five screens render and navigate correctly. Files reformatted from single-line minified to readable multi-line. Minor mock-data inconsistency noted (Live TV "All channels" count of 10 vs. per-group counts of 6 each) — cosmetic, not blocking.
- [x] **Design revision round 1 (2026-09-22, user feedback → Codex):** Series gained 3 folder shelves (Arabic Drama, Crime Thrillers, Sci-Fi & Fantasy — genre/collection groupings of shows, distinct from specific-show shelves). All horizontal card rows now use drag-to-scroll (hold shelf heading, drag left/right) and Left/Right keyboard scrolling with no visible scrollbar, across Movies/Series/Live TV. Search moved from a dedicated screen into a persistent top-bar field that filters in place — matches shelf/folder titles (showing the full folder) or item titles within a folder (showing only matches) — same behavior on Movies, Series, and Live TV, including within an already-open folder/series-detail/channel-group. Live TV reworked from a flat channel grid into a "Last watched" shelf + 3-5 most-used channel-group shelves on open, with the remaining groups revealed on scroll. One bug (folder-title match showing 0 items instead of full folder) found and fixed same round; all behaviors re-verified live in-browser by Claude after the fix. **Known pre-existing issue, out of scope for this round:** clicking any Movie or Live TV item opens the same generic mock player showing unrelated "Season 2 · Episode 4" / episode-list UI — carry into the next design pass.
- [x] **Design revision round 2 (2026-09-22, user feedback → Codex):** Round 1's drag/keyboard scrolling didn't actually work in practice — drag was bound only to the shelf heading text (not the cards/row), and shelves were never reachable by Tab so arrow-key scrolling never activated. Fixed: drag now starts from anywhere in a shelf's row including cards (6-8px threshold still distinguishes drag from click), each row is a proper `tabindex="0"` stop with a visible focus ring, and Left/Right arrows scroll the focused row. Series and Movies restructured to match Live TV's pattern: personal shelves (Continue watching/Recently added/Favorites) followed by a separate shelf for **every** category/genre, not just 3. Opening any category (Movies/Series/Live TV) now decollapses into a full-page wrapping grid (multi-row, no horizontal scroll) with a "← All categories" back control, instead of staying a single horizontal strip — search continues to filter correctly inside this expanded view. One regression found and fixed same round: dragging directly on a card triggered native text selection instead of scrolling (missing `preventDefault`/`user-select: none`) — fixed and re-verified. All behaviors (drag-on-card, Tab-to-shelf + arrow scroll, full category lists on Movies/Series, full-grid category expansion, in-place search) re-tested live in-browser by Claude after fixes.
- [x] **Design revision round 3 (2026-09-22, user feedback → Codex):** Added a "Sort categories" control (Most visited / Alphabetical (A-Z) / Recently added) on Movies, Series, and Live TV, positioned after the fixed personal shelves and before the category shelf list. The three personal shelves (Continue watching or "Last watched" on Live TV, Recently added, Favorites) always stay pinned at the top in that fixed order and are never affected by sorting. Default order for category shelves is most-visited-first on all three screens. Sort choice is independent per screen (Movies/Series/Live TV each have their own control and state) and persists through search/category-open/re-render within that screen for the session. Verified live in-browser by Claude: default is "Most visited" on all three screens, switching Movies to "Alphabetical (A-Z)" correctly reordered the category shelves (Action & Adventure → Arabic Cinema → Comedy...) while the three personal shelves above stayed in place, and Series/Live TV each carried their own independent "Most visited" default and control.
- [x] **Design revision round 4 (2026-09-22, user feedback → Codex):** Extended the sort control from 3 to 5 options: Most visited (default), Alphabetical (A-Z), Alphabetical (Z-A), Recently added, Recently updated. "Recently added" (newest content added to a folder) and "Recently updated" (most recent change to the folder itself, e.g. reorder/edit/removal) are driven by two distinct mock stats per category, not aliased to the same value. Same placement/per-screen-independent behavior as round 3, still never affects the three pinned personal shelves. Verified live in-browser by Claude on Movies: all 5 options present in the correct order; A-Z → Z-A produced a correctly reversed order; "Recently added" and "Recently updated" produced genuinely different shelf orderings, confirming the two stats aren't the same field under two labels.
- [x] **Design revision round 5 (2026-09-22, user feedback → Codex):** Left/Right arrow keys previously only scrolled a shelf's pixel position, without moving which card was focused/selected. Fixed to proper roving-tabindex card navigation (`app.js` keydown handler, ~line 299): resolves the focused card via `document.activeElement`, moves focus to the adjacent card in the same `.row`/`.catalog-grid` container by list index, calls `scrollIntoView({block:'nearest', inline:'nearest'})` so the newly-focused card comes into view without a visible scrollbar, is boundary-safe (no wraparound at the first/last card), and gives a sensible starting point if the row itself has focus (Right focuses the first card, Left focuses the last). Applies inside the expanded full-page category grid too. **Verification status: CONFIRMED live in Chrome 2026-09-22** — Claude-in-Chrome reconnected; arrow-key focus moves card-by-card, boundary-safe (stays on first/last card, no wraparound).
- [x] **Design revision round 6 (2026-09-22, user feedback → Codex):** Player screen (Movies/Series/Live TV all route through the same mock player) had a large empty gap below the video/title area even though the video area could occupy much more space. Fixed in `styles.css` ~line 135: `.player` now sets `min-height: calc(100vh - 190px)`; the video column (`.player > div`) uses `grid-template-rows: minmax(0, 1fr) auto auto` so the video area expands to fill available height while title/metadata keep their natural size directly beneath it with no gap; `.episodes` side panel (series playback) stretches to match the full column height via grid default stretch instead of being a short fixed box; the `max-width: 900px` breakpoint keeps the existing single-column stack with an explicit height fallback (`min-height: max(280px, calc(100vh - 120px))`). **Verification status: CONFIRMED live in Chrome 2026-09-22 (via Codex at 1440×900)** — `.player` computed min-height 778px matches `calc(100vh - 122px)` (round 7's tightened value superseded round 6's 190px), video column 988px vs. side panel 378px, no empty gap below video.
- [x] **Design revision round 7 (2026-09-22, user feedback → Codex):** Three fixes to the Player screen. (1) Title-replace bug: clicking a second episode was appending its name onto the existing title instead of replacing it — fixed via `updatePlayerMetadata()` using `textContent =` assignment (`app.js:232-234`), confirmed no `+=`/concatenation remains. (2) Layout still wasted space: tightened further — `.player` min-height now `calc(100vh - 122px)` (was 190px), `main:has(.player)` drops the page's normal 1440px max-width and reduces padding specifically on the player screen, video column widened to `2.35fr` vs `.9fr` for the side panel. (3) Wrong side panel for non-series content: Movies/Live TV playback no longer shows a fake Season/Episode list — `player()` now branches on `contentType` (`app.js:237-253`); only `series-episode` gets the episode list, Movies/Live TV get a new "More from [folder]" panel (`folderItemsFor()`, `app.js:221-229`) populated from the real originating shelf (threaded end-to-end: card → `data-origin-folder` → `activateCard` → `player()`), filtering out the currently-playing title, falling back to "Recently added" when no origin folder is known. Side-list items remain clickable/playable. **Verification status: CONFIRMED live in Chrome 2026-09-22** — title-replace tested by playing two different episodes in sequence (no concatenation); movie playback confirmed showing "More from Continue watching" panel, not a fake episode list; real series episode confirmed showing a genuine Season/Episode panel (via Codex UIA) — the two content-type paths are correctly distinct.
- [x] **Design revision round 8 (2026-09-22, user feedback → Codex):** Series was missing a "Favorites" shelf that Movies already had — added to `seriesShelves` and `seriesBrowse()` now shows the top 3 fixed shelves (Continue watching, Recently added, Favorites) instead of 2. My Tvivo's home page previously showed static placeholder shelves ("Movie folder 1"/"Series folder 1", just the first 7 items of the flat movies/series arrays) — replaced with a `topFolders()` helper that sorts `movieCategories`/`seriesCategories` by `categoryStats[...].visits` and shows the real top-3-most-used movie folders and top-3-most-used series folders. Verified live in Chrome: Series now lists Favorites; My Tvivo now shows Crime & Mystery/Action & Adventure/Science Fiction (movies) and Crime Thrillers/Arabic Drama/Sci-Fi & Fantasy (series), matching the visit-count data.
- [x] **WinUI3 catalog design port, pass 1 (2026-09-22, Codex):** Ported the finalized HTML prototype (rounds 1-8) into the real WinUI3 app. `App.xaml`: prototype teal/orange palette, shared brushes, card/button/shelf styles, typography. `MainWindow.xaml(.cs)`: replaced the old left-nav shell with a prototype-style top bar (TVIVO, My Tvivo/Movies/Series/Live TV, search, Account). `Pages/CatalogLandingPage.xaml(.cs)`: shelf browser with mode switcher, spotlight hero, horizontal shelf rows, shelf-title → full category grid, search, sort — preserving the existing cache-first refresh/generation logic. Movies/Series show honest empty state (not hardcoded mock content) since the WinUI data layer only indexed Live channels at port time. Did not touch the player screen (separate follow-up). Build clean (`dotnet build -p:Platform=x64`, 0 warnings/errors).
- [x] **WinUI3 catalog bug-fix pass (2026-09-22, Codex, live UIA-verified):** Four bugs found via build+run visual QA, root-caused, and fixed: (1) **Wrong data** — Movies/Series/Live TV were all reading the same Live-only repository calls; added `CatalogItemType` plumbing through the repository/refresh layer so each mode queries its own data path (confirmed distinct Movies=48,864/Series=14,125/Live=6,382 item counts via UIA). (2) **Stale mode-switch race** — fire-and-forget nav/search handlers left a window where title and body content mismatched during a slow refresh; serialized with a request-generation token so a superseding mode request shows loading chrome immediately instead of stale content. (3) **Shelf visual clipping** — `.player`-unrelated bug: the shelf `ScrollViewer` was squeezed to 32-85px by a hard `MinHeight=270` spotlight above it; reworked row sizing so the shelf scroller gets a proper star-sized viewport. (4) **gstack "Open with" dialog** — traced to gstack's extensionless Bash shebang scripts being invoked via PowerShell `&` instead of Bash explicitly, which makes Windows route them through file-association UI; no in-repo PowerShell launcher found to fix, documented the Bash-invocation guidance instead. Added a repository isolation test (`Catalog_queries_are_isolated_by_item_type`); `dotnet build -p:Platform=x64` clean, `dotnet test` 30/30.
- [x] **WinUI3 malformed-title fix, ported from Compose (2026-09-23, Codex):** Compose desktop had previously fixed empty-bracket title artifacts (e.g. `"( ) HD"`, `"()"`) via a `cleanTitle()` regex applied both on DB read and via a one-time schema migration (commit `a5c19de`). WinUI had the same bug and no shared cross-platform title cleaner existed (Android's `NameNormalizer` and desktop's `cleanTitle()` are both platform-local). Ported the same regex/whitespace logic into `SqliteCatalogRepository.cs`, applied on write (new snapshots) and on read (existing DB rows); bumped schema to v9 with a new transactional v8 migration that retroactively cleans already-cached titles (falls back to "Untitled" if cleanup leaves nothing). Documented the cross-platform parity rule in `docs/decisions.md:955` so the next platform doesn't rediscover this. `dotnet build -p:Platform=x64` clean, infrastructure tests 32/32 passing. Live UIA/screenshot verification intentionally skipped this round — would have touched the real persisted provider cache with no known malformed title in the seeded data to check against; migration/unit tests cover the actual cleanup logic instead.
- [ ] Review the HTML prototype's **functionality** against the Compose parity points below, and its **design** (visual language, component rules) against the established My Tvivo screen for consistency.
- [ ] WinUI's full UI is then implemented against that completed, reviewed HTML design, covering Home/My Tvivo, Movies, Series, Live TV, Player, and Account screens.
- [ ] The visual mismatch between the prototype and the current WinUI shell (`App.xaml`, `MainWindow.xaml`) is resolved — one shell, one palette, one nav model.

Each stage below is independently testable and should close with a verification pass before moving to the next. Stages are ordered by dependency (later stages assume earlier ones exist), not by priority within Compose.

### Stage 1 — Core content model (Movies + Series)
Foundational: shelves, favourites, resume, and search below all assume Movies/Series exist as first-class catalogs.
- [ ] Add `Movies` catalog type end-to-end: `ICatalogProvider` contract, SQLite schema/query (currently hard-coded `type='Live'` in `SqliteCatalogRepository.cs:49`), browse page, and WinUI nav entry. **Test:** a synced provider with movie content shows a Movies tab with a populated, paged grid.
- [ ] Add `Series` catalog type + season/episode model: series list, season screen, episode list, and episode-level playback launch (mirrors `DesktopShell.kt:204`, `DesktopCatalogRepository.kt:292`). **Test:** selecting a series shows seasons, selecting a season shows episodes, selecting an episode starts playback of that specific episode.

### Stage 2 — Playback correctness & controls
Blocked on Stage 1 only for movie/episode playback entry points; the Live TV playback bug is independent and can be fixed immediately.
- [ ] **Fix Live channel playback (pre-existing bug, no dependency):** wire `StreamUrlBuilder.Live` into the channel-activation path so `StreamSource` gets a `DirectUri` (`XtreamInfrastructure.cs:122`, `MainWindow.xaml:85`). **Test:** selecting any real Live channel from a synced catalog starts playback instead of reporting "no direct stream URI."
- [ ] Add movie/episode playback URL construction (parallel to `DesktopCatalogRepository.kt:314`) once Movies/Series exist. **Test:** selecting a movie or episode builds a correct provider URL and starts playback.
- [ ] Build a real player control surface: pause, seek, volume, keyboard/mouse/wheel input (parallel to `DesktopShell.kt:477`; WinUI currently only has Play/Stop at `MainWindow.xaml:56`). **Test:** each control (pause/resume, seek forward/back, volume up/down) works during an active local-fixture playback session.
- [ ] Add fullscreen/cinema mode: header hiding, Escape/F toggling (parallel to `DesktopShell.kt:599`). **Test:** entering/exiting fullscreen via keyboard hides/restores chrome without breaking playback.
- [ ] Add OSC title + owned overlay (parallel to `DesktopShell.kt:452`). **Test:** playing content shows the correct title in the on-screen overlay, not just a diagnostic surface.
- [ ] Add a progress watchdog with actionable timeout recovery beyond the current 10s startup-only deadline (`WindowsPlaybackEngine.cs:30`, parallel to `DesktopShell.kt:572`). **Test:** a stream that stalls mid-playback (simulate via fixture) surfaces a recoverable error instead of hanging indefinitely.

### Stage 3 — Personalization & continuity
Depends on Stage 1 (Movies/Series must exist for their shelves/resume to have content).
- [ ] Home activity shelves: Continue Live/Movies/Series + Suggested (parallel to `DesktopShell.kt:263`; WinUI Home is currently only a provider-setup prompt at `Pages/HomePage.xaml:5`). **Test:** Home shows a Continue Watching row after playback of each content type, and a populated Suggested shelf.
- [ ] Recently Added shelf using existing `added_at` column (`added_at DESC`, capped list, parallel to `DesktopCatalogRepository.kt:120`; this closes TODO Req 3). **Test:** newly synced items appear at the top of a Recently Added shelf, oldest items fall off past the cap.
- [ ] Suggestions shelf: session-stable, rating-weighted sampling (parallel to `DesktopCatalogRepository.kt:162`; closes the WinUI half of TODO.md Req 1). **Test:** the shelf's item set and order stay stable across re-renders within a session, and reshuffles on next session.
- [ ] Favourites: query + toggle UI on top of the existing `favourite` column (`SqliteCatalogRepository.cs:27`, parallel to `DesktopCatalogRepository.kt:123`). **Test:** toggling favourite on an item persists across app restart and surfaces in a Favourites view.
- [ ] Movie/episode resume: persisted position, seek-on-resume, periodic writes (~5s) and lifecycle-exit persistence (parallel to `DesktopShell.kt:470`, `:562`, `:598`). **Test:** stopping playback partway through and reopening the item resumes within a few seconds of the stop point.
- [ ] Series resume: episode-level resume storage, one continuation card per series (parallel to `DesktopCatalogRepository.kt:247`, `:279`). **Test:** stopping mid-episode and returning to the series shows one "continue" entry pointing at that exact episode/position.

### Stage 4 — Browse & search UX parity
- [ ] Category/item search: collapsible search pill, clear/collapse, Escape handling, saved search state, cross-content search (parallel to `DesktopShell.kt:322`, `:345`; WinUI today has group selection + live-only text filter at `CatalogLandingPage.xaml:42`). **Test:** opening search, typing, collapsing, and reopening preserves the last query; Escape clears and collapses.
- [ ] Per-tab browse state (filter, query, scroll position, highlighted item) preserved across navigation to detail/player and back (parallel to `DesktopShell.kt:107`). **Test:** scroll partway down a catalog, open an item, go back — scroll position and focused card are unchanged.

### Stage 5 — Account & lifecycle
- [ ] Fix account-identity collision: include URL scheme in `AccountIdentity.For`'s hash, not just host/port/username (`XtreamInfrastructure.cs:139`). **Test:** the same host/port/username configured once as `http://` and once as `https://` produce distinct cached catalog rows and connection-map entries.
- [ ] Account details screen: status, expiry, username, max connections (parallel to `DesktopShell.kt:399`). **Test:** the account screen shows live values matching the provider's actual account-info response.
- [ ] Safe sign-out and exit flows: DPAPI credential wipe on sign-out, exit confirmation (parallel to `Main.kt:54`, `:90`). **Test:** sign-out clears stored credentials and returns to setup; closing the app during active playback prompts for confirmation.
- [ ] Sanitize provider setup error copy instead of surfacing raw exception text (`ProviderSetupPage.xaml.cs:63`, parallel to `Main.kt:119`). **Test:** a deliberately wrong password/host shows a user-facing message with no stack trace or internal exception text.

### Stage 6 — Resilience & observability
- [ ] Extend the cache-first empty/loading/error state machine (currently Live-TV-only, `CatalogLandingPage.xaml.cs:98`) to Movies/Series once Stage 1 lands, matching Compose's richer per-content recovery (`DesktopShell.kt:263`). **Test:** killing network mid-refresh on each catalog type still shows previously cached content, not a blank/error screen.
- [ ] **Fix cache refresh destroying user state:** `ReplaceLiveSnapshot` currently deletes all Live rows before inserting the new snapshot (`SqliteCatalogRepository.cs:36`), which would wipe favourites/resume once those exist. Preserve favourites/resume/timestamps across refresh (parallel to `DesktopCatalogRepository.kt:199`). **Test:** favourite an item, trigger a catalog refresh, confirm the favourite flag survives.
- [ ] Idle/featured experience: 5-minute idle controller + featured overlay (parallel to `DesktopIdleController.kt:12`, `DesktopShell.kt:228`). **Test:** leaving the app idle on Home for 5 minutes triggers the featured overlay; any input dismisses it immediately.
- [ ] Routine diagnostics: catalog event log, resume diagnostics, healthy-session events (not just failure logging) (parallel to `DesktopShell.kt:408`). **Test:** a normal healthy session (sync, browse, play, resume) produces a readable log trail, not just silence-until-failure.
- [ ] Catalog-scale memory/query safeguards for Movies/Series once they exist, matching the existing 100-row Live paging (`SqliteCatalogRepository.cs:58`) and the bounded-retrieval goal at TODO.md:37. **Test:** browsing the largest provider category for each content type does not show unbounded memory growth over a sustained scroll session.

**Compose/desktop TODO items with no WinUI counterpart yet (tracked on the Compose side, carry over once WinUI reaches that stage):** search pill+saved state, fullscreen/OSC title, Tvivo-owned player controls, artwork cache/decode budget, catalog-scale memory/query budgeting, player lifecycle audit instrumentation, desktop performance acceptance measurements, main-screen Exit confirmation, dark login alignment/dark splash, MSI/runtime distribution work.

---

## Phase 5 (Hardening) — Pending

Phase 5 work depends on open QA items closing. Planned scope:
- RTL text layout
- Empty/loading/error states
- `RefreshWorker` improvements
- Physical TV validation (once `max_connections` > 1 or test pool available)

---

---

## Out of Scope (Explicitly Deferred or Declined)

See `PROJECT-BIBLE.md` §5 for rationale. These include:
- Idle dim / screensaver
- Voice search
- Second TV target (LG webOS)
- Favorite folders (separate persistence item with own plan)
- Home Settings screen (unscoped, no priority)
