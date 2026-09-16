# Tvivo TODO

Open work only, grouped by area. No dates, no session narrative.

See `TODO-ARCHIVE.md` for closed items and `docs/decisions.md` for rationale.

**Session-end duty:** Move closed items to `TODO-ARCHIVE.md` (verbatim, with full writeup), replace the line here with a one-line pointer, and confirm the closure note already exists in `docs/decisions.md`.

---

## Desktop (Priority — active batch)

- [ ] **D-Desktop-15-REGR:** Full-screen launch retains Windows title bar/frame. Entry screen launches but window remains windowed; apply full-screen at app launch time.
- [ ] **D-Desktop-16-REGR:** Malformed titles remain (e.g., "( ) HD", "()", "0 HD"). Verify `NameNormalizer` applied to all title sources, not just main grid.
- [ ] **D-Desktop-17-REGR:** Ratings must render as star + number, no fallback. Missing ratings should omit entirely, not fall back to "Movies"/"Series" text.
- [ ] **D-Desktop-18-REGR:** Suggested Movies "See all" link vertically clipped. Needs reserved space or removal (owner prefers removal).
- [ ] **D-Desktop-19-REGR:** Suggested Movies unsorted or wrong order. Apply descending rating sort in `DesktopCatalogRepository.kt`.
- [ ] **D-Desktop-16f:** Home's series `Continue watching` shelf shows at most one resumable episode per series (most recent playback).
- [ ] **D-Desktop-17:** Player should open truly full screen as default state, holding full-screen on open.
- [ ] **Season/category raw-label bypass:** `SeriesInfoParser.kt`'s season-rail label and `CategoryListParser.kt`'s `category_name` render raw provider strings, bypassing `NameNormalizer`. Low priority — investigate only if observed in practice.
- [ ] **D-Desktop-1..14:** See `63-section.md` for detailed list. D-Desktop-14 closed 2026-09-15 (mpv migration); full-screen, title layout, episode-picker, rating defects remain open.

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

## Meta: Documentation Restructuring (Completed 2026-09-16, Phase 3-4 pending)

- [x] **Phase 1 (2026-09-16):** Consolidate 17 detail files → PROJECT-BIBLE.md + simplified CLAUDE.md
- [x] **Phase 2 (2026-09-16):** Consolidate todo-tree/ → unified TODO.md indexed by area
- [ ] **Phase 3 (deferred):** Extract PRODUCT-BIBLE.md from 9 product-*.md files (optional, low priority)
- [ ] **Phase 4 (deferred):** Archive/delete old bible-detail/ and todo-tree/ directories after Phase 3 verification

**Rationale:** Tvivo had 17 scattered detail files with 3 conflicting entry points, undefined "relevant" rule, and ~170 lines of mandatory loading per session with guesswork. Restructured following EMR project pattern: single unified PROJECT-BIBLE (§0-8), clear session protocol, zero ambiguity. All content preserved. See MIGRATION-SUMMARY.md for verification checklist.

---

## Out of Scope (Explicitly Deferred or Declined)

See `PROJECT-BIBLE.md` §5 for rationale. These include:
- Idle dim / screensaver
- Voice search
- Second TV target (LG webOS)
- Favorite folders (separate persistence item with own plan)
- Home Settings screen (unscoped, no priority)
