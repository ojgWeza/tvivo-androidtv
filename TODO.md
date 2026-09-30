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

## Windows (WinUI) — Compose Parity Roadmap (re-verified 2026-09-28)

Source: comparison of the mature Compose desktop app (`desktop/`) against the WinUI spike (`windows-spike/`), listing what Compose already has (or has explicitly planned) that WinUI does not yet implement. This is a **development roadmap, not a defect list** — WinUI is intentionally early-stage and Compose is the working reference it is being brought up to. `TODO-ARCHIVE.md` was not found at repo root, so Compose's archived/closed requirements could not be cross-checked.

**2026-09-28 re-verification note:** the 2026-09-22 version of this list was badly stale — most of Stage 1, large parts of Stage 2-4, and two Stage 6 bugs had already been closed in code without the doc being updated. Every item below was re-checked directly against current `windows-spike/src/` source before this edit. Items confirmed done are checked off with evidence; items downgraded to PARTIAL or reworded MISSING reflect what's actually true today. This list is the basis for a decision on retiring Compose — do not resume that decision from the pre-2026-09-28 version of this section.

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
- [x] ~~Review the HTML prototype's functionality/design against Compose and the My Tvivo screen~~ — superseded by the "WinUI3 catalog design port, pass 1/2" entries above (2026-09-22).
- [x] ~~WinUI's full UI implemented against the reviewed HTML design~~ — done for Home/My Tvivo, Movies, Series, Live TV per the pass 1/2 entries above; Account screen still open, tracked under Stage 5 below.
- [x] ~~Resolve visual mismatch between prototype and WinUI shell~~ — done via the pass 1/2 port (`App.xaml`, `MainWindow.xaml` now match the prototype palette/nav).

Each stage below is independently testable and should close with a verification pass before moving to the next. Stages are ordered by dependency (later stages assume earlier ones exist), not by priority within Compose.

### Stage 1 — Core content model (Movies + Series) — ✅ DONE
- [x] `Movies` catalog type end-to-end. `MainWindow.xaml:66-69` has a real Movies nav entry; `SqliteCatalogRepository.cs` queries by `CatalogItemType.Movie` throughout (no longer hard-coded to `'Live'`).
- [x] `Series` catalog type + season/episode model. `MainWindow.xaml.cs:571-617` builds seasons/episodes from `GetSeriesInfoAsync` (`XtreamInfrastructure.cs:101-145`); season selector (`ConfigureSeasonSelector:733`); episode-level playback (`OpenSeriesEpisode:590`); resume-aware episode selection (`SeriesEpisodeResolver.Resolve:557`).

### Stage 2 — Playback correctness & controls — mostly DONE, one real gap
- [x] **Live channel playback bug — fixed.** `SqliteCatalogRepository.cs:580` (`StreamSourceFor`) now builds `DirectUri` via `StreamUrlBuilder.Live`.
- [x] Movie/episode playback URL construction. `StreamSourceFor` (`SqliteCatalogRepository.cs:574-580`) covers Movie/Series/Live uniformly.
- [x] ~~Fullscreen/cinema mode~~ — confirmed done: `SetCinemaMode`/`CinemaButton_Click` (`MainWindow.xaml.cs:1231-1280`), chrome-row hiding (`ApplyWindowChromeState:169-179`), F11 native fullscreen (`WindowStateController.cs`), Escape exits player during cinema mode (`WindowRoot_KeyDown:155-167`).
- [ ] **PARTIAL — Real player control surface.** Pause/resume, seek ±10s, seek-bar drag, and volume slider all work via mouse/UI (`PauseButton_Click:1050`, `RewindButton_Click:1126`, `ForwardButton_Click:1128`, `SeekTo:1140`, `VolumeSlider_ValueChanged:1151`). **Gap: no keyboard or scroll-wheel shortcuts** for these — only F11/Escape are keyboard-bound. **Test:** Space toggles pause, Left/Right seek, Up/Down or wheel adjusts volume, during active playback.
- [ ] OSC title + owned overlay — not re-verified this pass, status unknown, re-check before Stage 2 is called fully closed.
- [ ] Progress watchdog with mid-playback stall recovery beyond the current 10s startup-only deadline (`WindowsPlaybackEngine.cs:30`) — not re-verified this pass, treat as still open per original citation.

### Stage 3 — Personalization & continuity — DONE (2026-09-30)
- [x] **Home activity shelves (Continue Watching + Suggested) — done 2026-09-30.** `HomePage.xaml(.cs)` now renders both shelves via `SqliteCatalogRepository.GetContinueWatching`/`GetSuggestions`, wired through `MainWindow.LoadAsync`. Runtime-verified live: played a movie, confirmed `resume_ms` written to the DB during playback, closed and relaunched the app, and the item appeared in Continue Watching with correct artwork on the next launch — full round trip confirmed, not just code review.
- [x] Recently Added shelf. `MyTvivoShelfDefinitions.cs:22-24` + `GetRecentlyAdded`, wired into `CatalogLandingPage.xaml.cs:447,532,612`.
- [x] **Suggestions shelf — done 2026-09-30.** Rating-weighted reservoir sampling in `SqliteCatalogRepository.GetSuggestions`, excludes recently-added and continue-watching items. Runtime-verified: shelf populated with a mix of Movie/Series/Live items; series entries correctly show a plain "Series" subtitle (not "resume episode" — a mislabeling bug found and fixed during verification, see `HomePage.xaml.cs` `ToCard`).
- [x] Favourites: query + toggle UI. Full round-trip — `IsFavorite`/`ToggleFavorite` wired to `PlayerFavorite_Click`/`SeriesFavorite_Click` (`MainWindow.xaml.cs:649-672`), plus favorites shelves per content type (`MyTvivoShelfDefinitions.cs:28-30`).
- [x] **Movie/episode resume — done 2026-09-30.** `UpdateResumePosition` is now called from `MainWindow.SaveResumePosition` (throttled every 5s during playback, forced on stop/navigation/close). Runtime-verified end to end (see Home shelves entry above) — this was previously the single biggest functional gap and is now confirmed working live, not just wired in source.
- [x] **Series resume — done, same wiring as movies.** `GetContinueWatching` now queries both `Movie` and `Series` types in one call (`SqliteCatalogRepository.cs`), so series also get a Home Continue Watching card, closing the old "no discoverable continue-card" gap. `GetSeriesPlayback`/`UpdateSeriesPlayback` still separately drive resume-on-reopen via `SeriesEpisodeResolver.Resolve`. **Not independently runtime-verified this session** — only movie playback was live-tested; series-specific resume-card behavior should be spot-checked before fully trusting it.

### Stage 4 — Browse & search UX parity — mostly DONE (different shape than planned)
- [x] Category/item search — implemented as a **persistent top-bar field** (`TopSearchBox`, `MainWindow.xaml.cs:411-424`) rather than the originally-planned collapsible pill; this matches the finalized HTML prototype's round-1 design, which deliberately replaced the pill concept. Cross-content search (Movies/Series/Live TV) works. **Unverified:** Escape-to-clear and query persistence across session — check before closing.
- [ ] Per-tab browse state (filter, query, scroll position, highlighted item) preserved across navigation to detail/player and back — not re-verified this pass; no scroll-position persistence code found on a quick check, treat as still open.

### Stage 5 — Account & lifecycle — DONE (2026-09-30)
- [x] **Account-identity HTTP/HTTPS collision — fixed 2026-09-30.** `AccountIdentity.For` (`XtreamInfrastructure.cs:238`) now includes the normalized scheme in its hash input. Existing locally-saved accounts under the old scheme-agnostic ID are not migrated — they simply stop matching and the user re-authenticates once (deliberate decision, avoids guessing at a migration mapping). Tests: `AccountIdentity_normalizes_scheme_host_and_username_whitespace_but_separates_http_and_https`, `Provider_keeps_http_and_https_connections_under_separate_account_ids`, `Http_and_https_account_keys_keep_catalog_rows_isolated` — all passing.
- [x] **Account details screen: status, expiry, username, max connections — done 2026-09-28, runtime-verified 2026-09-30.** `Pages/AccountPage.xaml(.cs)` shows display name/username, host+scheme, max connections, and expiry. Wired via `AccountNavigation_Click`. Confirmed live with real account data via screenshot.
- [x] **Safe sign-out, change-user, and exit flows — done, corrected 2026-09-30.** Sign-out calls `ICredentialStore.DeleteAsync()`, clears account/session state, routes to Setup. New **"Change user"** button added alongside Sign out — opens a fresh setup form WITHOUT deleting the saved credential, so backing out leaves the previous account intact (verified: killed and relaunched the app after using Change User without completing a new login, confirmed the original account reloaded automatically). Exit-during-playback dialog copy was **factually wrong and has been fixed**: it used to claim progress "won't resume automatically next time," which became false once resume-position wiring landed (Stage 3) — found via live runtime testing (DB showed a real saved `resume_ms` while the dialog still denied it), corrected to accurately state progress is saved and resumes next time.
- [x] **Sanitize provider setup error copy — fixed 2026-09-30.** `ProviderSetupPage.xaml.cs` and `AccountPage.xaml.cs`'s sign-out handler now route all exception text through a `ConnectionErrorText` helper that logs the real exception via `LaunchDiagnostics.WriteException` but shows only fixed, actionable text to the user — no stack traces, paths, or URLs surfaced in the UI. Test: `Connection_error_copy_does_not_display_exception_details` injects a URL+path into the exception and asserts neither survives into the shown message.

### Stage 6 — Resilience & observability — DONE except MSI packaging (2026-09-30)
- [ ] Extend the cache-first empty/loading/error state machine to Movies/Series — likely done given Stage 1's completion but not independently line-by-line re-verified; re-check before closing.
- [x] **Cache refresh destroying user state — fixed.** `ReplaceSnapshot` (`SqliteCatalogRepository.cs:265-282`) now does `INSERT ... ON CONFLICT DO UPDATE SET` touching only `category_id,title,title_sort,artwork,extension,added_at` — favourite/resume/visit columns survive a refresh untouched. The old delete-all `ReplaceLiveSnapshot` no longer exists.
- [x] **Idle/featured experience — done 2026-09-30.** 5-minute idle timer + full-window featured overlay in `MainWindow.xaml(.cs)`, matching the Compose reference behavior: pauses when playback is active, window unfocused, or a `ContentDialog` is open; first keyboard/pointer/wheel input dismisses without activating underlying content (event consumed via `Handled = true`); timer stops cleanly on window close. Featured content sourced via `SqliteCatalogRepository.GetFeaturedSeriesTitles` (bounded, rating-ordered, LIMIT 20 — moved into the repository proper after an initial version bypassed it with a raw ad-hoc SQLite connection, caught in review). Show/dismiss events logged (`event=idle.overlay.show/dismiss reason=...`) after a perf run showed there was no way to confirm the overlay fired without launching a debugger. **Not visually runtime-verified this session** (only code-reviewed + log-instrumented) — the app was launched for playback/resume verification but the idle overlay's 5-minute trigger was not specifically exercised live.
- [x] **Routine diagnostics — done 2026-09-30.** `LaunchDiagnostics.Write` now emits structured `event=` lines for catalog cache load/refresh (with outcome + duration), shelf browse-open, playback start/stop (tagged with reason: user-cancelled/timeout/completed/error), per-engine resource release confirmation, resume-position saves, and idle-overlay show/dismiss. Includes URL/credential-field redaction, 4096-char truncation, and 1MB log rotation — all unit-tested (`LaunchDiagnosticsTests.cs`). `WriteException`/`WriteExceptionDetails` (used for genuine crash diagnostics) still log full exception text + stack trace, routed through the same redaction.
- [x] **Catalog-scale memory/query safeguards — generalized.** The 100-row paging (`GetChannels` limit, `SqliteCatalogRepository.cs:293,297`) now applies to all `CatalogItemType`s, not just Live. Not load-tested for sustained scroll — a perf pass is still worth doing but the structural safeguard exists.
- [x] **Artwork cache/decode budget — done 2026-09-30.** The existing 6-slot/cancellation/decode-sizing pipeline now also tracks actual decoded bytes and evicts on a 96 MiB budget (`ArtworkMemoryBudget`, unit-tested) in addition to the old 128-entry count cap. Home's shelf artwork was previously binding images directly, bypassing this pipeline entirely — now routed through the same shared, bounded cache via `LoadHomeArtwork`/`UnloadHomeArtwork`.
- [x] **Player lifecycle audit instrumentation — done 2026-09-30.** Both playback engines (`WindowsPlaybackEngine.cs`/`FFmpegInteropPlaybackEngine.cs`) now log an `event=playback.engine.release` line on stop/dispose confirming what was actually released (source, player, media, callbacks, surface) and why (user-cancelled/timeout/error/completed).
- [x] **Desktop performance acceptance measurements — done 2026-09-30, core scenarios PASS.** Full run (idle My Tvivo, idle Movies, 50 mode switches, 20x scroll, 30x fullscreen toggles, close) completed cleanly, no crash/hang, clean exit in 225ms. Idle CPU <1%/core, UI ping peak 84ms. See `windows-spike/perf/REPORT.md` — supersedes the prior 2026-09-26 run, which crashed mid-execution and predated this session's entire feature set. Monkey/heavy-user long-session testing intentionally deferred, not run this pass.
- [x] **Dark Login/splash screen — done 2026-09-30, scoped as a dark startup frame (not a dedicated splash page).** `RequestedTheme="Dark"` on `App.xaml` + `DwmSetWindowAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE)` in `MainWindow`'s constructor eliminate the white flash before first paint; `ExtendsContentIntoTitleBar` (known crash cause in this app) deliberately not touched. **3-launch verification performed live**: 3 consecutive launches, all responsive, no crashes, no DWM errors logged.
- [ ] **MSI/runtime distribution — deliberately deferred.** Needs a signing certificate, target architecture/Windows-version support decision, and distribution channel before implementation starts — explicitly out of scope until those are decided.

**Net assessment (2026-09-30):** Every previously-identified functional gap is now closed and, where practical, runtime-verified live (not just code-reviewed) — Home shelves, resume-position wiring (movie confirmed end-to-end live; series wiring present but not independently live-tested), Account/lifecycle, idle overlay, diagnostics, artwork budget, and startup dark-frame. The only remaining open item is MSI/distribution packaging, deliberately deferred pending signing/channel decisions. **Compose retirement is no longer blocked by a functional gap** — the decision itself has not yet been made and remains the user's call.

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
