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
