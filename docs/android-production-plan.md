# Android — production-readiness plan and design

**Status:** Draft for approval. Nothing here is implemented, built, or deployed. **Name:** "Tvivo" is a working name only (postponed 2026-10-07, see TODO Phase 0); anything below that depends on the name (wordmark, `applicationId`, domain, store name, icon) is blocked until it is chosen.
**Date:** 2026-10-07 · **Branch context:** `codex/winui-catalog-design-plan`
**Inputs:** `PRODUCT-BIBLE.md`, `PROJECT-BIBLE.md`, `TODO.md`, `docs/android-parity-audit-2026-10-03.md`, `docs/design/cross-platform-product-design-proposal.md`, `windows-spike/DESIGN-RULES.md`, current `app/` source.

## 0. Decisions already made (owner, 2026-10-07)

| Decision | Choice | Consequence |
|---|---|---|
| Form factors | **TV + phone + tablet, one codebase** | Product Bible's "Android TV only" scope is superseded. Q-14 (phone crash) becomes a real defect, not an "unsupported" answer. Input model is D-pad *and* touch. |
| Release target | **Play Store public release** | Signing, AAB, R8, target-SDK policy, data-safety, IPTV content policy, reviewer access, TV-store requirements. See §7. |
| Home model | **Adopt WinUI's My Tvivo shelves** | Android Home stops being a tile screen; the category rail stops being the entry point. See §3. |

**Bible updates this forces** (do as the first PR of Phase 0, not later): `PRODUCT-BIBLE.md` Platform section and Environment (add Android handset/tablet), `PROJECT-BIBLE.md` §2/§5 ("design space is 960×540 only", "no touchscreen"), and the `TODO.md` Out-of-Scope list.

## 1. Where Android stands today (verified from source)

- **Strong already:** Room-backed catalog (48k VOD / 6.4k live / 13k series), per-category TTL + background full sync, D-pad focus contracts, pre-run detail page, series picker, resume prompt, favourites, continue-watching, Tink-encrypted credentials, in-app diagnostics, 20+ test files.
- **Built for one screen:** the UI assumes 1920×1080 at density 320 (`androidx.tv.material3`, `tv-foundation`, fixed type roles tuned for ten feet, SettingsScreen sized to "540 dp less padding"). The manifest declares leanback *and* a plain launcher, and the player is locked to landscape, but nothing adapts to a handset.
- **Player is the thinnest part:** `PlayerActivity.kt` is 292 lines of programmatic `View` code. A non-seekable `ProgressBar`, fixed ±10 s seek, one 92 % completion rule, no tracks, no play-next, no stall reconnect, no PiP, no MediaSession.
- **Not release-shaped:** `versionName = "0.1.0-poc"`, `versionCode = 1`, `isMinifyEnabled = false`, no release signing config (only the committed `debug.keystore`), `applicationId = com.dev.Tvivo`, `targetSdk = 34`.
- **Verification debt:** ~20 TODO items marked "implemented, emulator verification pending" (Q-4, Q-5, Q-11, Q-21, QA-3…QA-9, U-13, U-14). Several can't be verified because `max_connections = 1` blocks automated streams.

## 2. The identity to inherit — WinUI's mature identity, translated

The palette is already shared, so "identity" here is the rest: structure, components, motion, voice. Treat each row as a **semantic role translated into Compose**, not a pixel copy (the cross-platform proposal says the same).

| WinUI identity element | Source | Android translation |
|---|---|---|
| Teal-black surfaces, coral signal | `App.xaml`, `Palette.kt` | Already matches. Add the roles Android lacks: `AccentSoft #E7A084` (focus/hover ring), `Good #8BC8A0` (watched/ok), `Favourite` gold (`AppFavoriteBrush`), `Deep #071114` (top bar). Re-measure contrast for each new pair (Palette.kt rule). |
| **TVIVO** letter-spaced wordmark in the top bar, dark splash | prototype `.brand`, `MainWindow.xaml` | Same wordmark on top bar (TV/tablet) and splash. Product Bible says no wordmark/icon exists. **Decide here:** adopt the WinUI wordmark as the official one, then derive the launcher icon, TV banner and adaptive icon from it. |
| 12 px corner radius on cards, tiles, buttons | `DESIGN-RULES.md` | One `TvivoShapes.card/button = 12.dp`. Replace ad-hoc radii. |
| Orange = selected / now-playing row; gold = favourite | `DESIGN-RULES.md` | Same rule in episode list, track picker, settings. Favourite star never orange. |
| Shelf grammar: pinned personal shelves, then one shelf per category, "open shelf → full grid" | prototype rounds 1–8 | Core of the Android home redesign (§3). |
| Quality pill (`HD/FHD/UHD/4K/SD`), `NEW` pill, inline progress bars | `CatalogLandingPage` | Cards get a quality pill and a progress bar; shelf headers get `NEW`. |
| Transitions never rebuild visible content in place: fade out, swap, fade in | `DESIGN-RULES.md` | One `FadeSwap` helper used for category/sort/mode changes. Honour reduced-motion. |
| Honest data: no fabricated shelves, schedules or metadata; omit empty shelves | cross-platform proposal | Same rule. No EPG UI until a provider endpoint is proven. |
| Sanitized, actionable error copy; cached content stays on failure | Stage 5/6 | `ErrorCopy.kt` already does this; audit all strings against the WinUI voice. |
| Settings = Library / Player / Account tabs | `AccountPage` | Same three groups (§4.5). |

**Product Bible commitments that stay:** English LTR chrome, no RTL mirroring, Arabic titles via `name_display`, provider-agnostic, no hardcoded hosts, no third-party brand marks.

**Type:** WinUI uses Segoe UI at desktop sizes; Android keeps Roboto (+ Noto Naskh fallback, baseline-pinned line boxes — that work is a feature, keep it). Replace the single TV-tuned scale with a **scale per size class** (§3.1) sharing the same six role names so no composable names an `sp`.

## 3. Design

### 3.1 Form-factor model

One Compose UI, three layout classes chosen from `WindowSizeClass` plus a `LocalIsTelevision` flag (UI mode TV *or* no touchscreen + leanback). Input is orthogonal: TV is D-pad-only; handset and tablet are touch-first but **must remain keyboard/D-pad navigable** (Bluetooth remotes, Chromebooks, foldables).

| | **TV** (≥ 960 dp, D-pad) | **Tablet** (≥ 600 dp, touch) | **Phone** (< 600 dp, touch) |
|---|---|---|---|
| Primary nav | Top bar: TVIVO · My Tvivo · Movies · Series · Live TV · Search · Settings (WinUI shell) | Navigation rail | Bottom bar: My Tvivo · Movies · Series · Live · Search; Settings in the My Tvivo header |
| Browse | Shelves → full grid, focus-driven | Shelves → grid, 4–6 columns | Shelves → grid, 2–3 columns, portrait |
| Category switch | Chips inside opened shelf grid (replaces left rail) | Same | Horizontal chip row, sticky |
| Detail | Poster left, text right, Play focused | Same as TV | Backdrop top, scrolling text, sticky Play |
| Player | Overlay controls, D-pad | Overlay controls, touch + gestures | Landscape fullscreen; portrait entry rotates; PiP |
| Min type | 14 sp body / 12 sp floor (ten feet) | 14 / 12 | 14 / 12 (touch target ≥ 48 dp) |
| Login | D-pad fields, preserve typing | Touch IME | Touch IME, paste-friendly, password manager hint off |

The **category rail goes away as a top-level concept** on TV too: the shelf landing is the entry; opening a shelf gives a full-page grid with the same category switcher the prototype uses. The rail's *focus contracts* (enter/restore by item id, `pendingFocusItemId`) are kept and re-applied to shelves and grids. **Spike it first**: this is the riskiest focus rewrite in the plan (see Risks).

Directional focus links may target only nodes rendered in the current state. A filter or category row falls back to Compose's default focus search when its grid/rail target is absent (loading, empty, error, no-match, or a closed panel). Shelf restoration is scoped to the originating shelf and bounded; missing cards clear the pending key. The opened-list grid continues to restore by `pendingFocusItemId`.

### 3.2 Screen designs

**My Tvivo (Home)** — answers "what can I pick up / where do I browse". Top to bottom: title-only header (phone adds a Settings gear); merged Continue watching, Favourites and Recently added shelves across content types; Suggestions (20 items, session-stable, rating-weighted, excludes recent + continue); then most-visited folders. The three core shelves show a hint card when empty; other empty shelves are omitted. Search is a shell destination, and Refresh library is in Settings (phone Home also supports pull-to-refresh). First run: no shelves, three large route cards (Movies / Series / Live TV) and a Refresh action. A failed refresh keeps cached shelves and shows a discreet "Couldn't refresh. Showing saved titles" + Retry.

```
TV / tablet                                      Phone
┌──────────────────────────────────────┐         ┌───────────────┐
│ TVIVO  MyTvivo Movies Series Live  🔍 ⚙│        │ TVIVO       🔍│
├──────────────────────────────────────┤         │ Continue ▸     │
│ Continue Watching            ▸       │         │ ▭▭▭ ▭▭▭ ▭▭▭   │
│ ▭▭▭▭ ▭▭▭▭ ▭▭▭▭ ▭▭▭▭ ▭▭▭▭ (bars)     │         │ Suggestions ▸  │
│ Suggestions                  ▸       │         │ ▭▭▭ ▭▭▭ ▭▭▭   │
│ ▭▭▭▭ ▭▭▭▭ ▭▭▭▭ ▭▭▭▭ ▭▭▭▭             │         │ Recently added │
│ Recently Added · Favourites · Top…   │         ├───────────────┤
└──────────────────────────────────────┘         │ ⌂  🎬  📺  ◉  🔍│
                                                 └───────────────┘
```

**Movies / Series / Live TV landing** — pinned personal shelves (Continue/Last watched, Recently added, Favourites), including empty hints, then a *Sort* control (Most visited default, A–Z, Z–A, Recently added), then one shelf per category with `NEW` pill. Live uses fitted logos on a neutral surface, never cropped into posters. Search opens from the shell tab; the landing has no inline search field. Opened-shelf item Filter sits at the trailing end of the Sort row.

Home and all three type landings use the same 32 dp horizontal / 20 dp top inset and 16 dp section rhythm. Shelf headings wrap without clipping category identity; See all stays at the trailing edge with a 48 dp minimum target and a shelf-specific accessibility name. Poster and channel display sizes remain 110×165 dp and 110×62 dp, with 220×330 px and 220×124 px decode targets. Loading placeholders and empty hints use the matching card height, and refresh/status copy stays in the header above cached shelves. The shared `AccentSoft` focus border is drawn inside its bounds so it remains visible at row edges.

**Opened shelf** — full-page wrapping grid, "← All categories" back, category chips for switching, sort, quality pill, progress bar. Back restores scroll position *and* focused item (already specified for WinUI; Android already restores by item id).

**Detail** — poster, title, quality pill, year/rating/genre/plot/cast **only when the provider sent them** (the WinUI hover preview becomes this page; no hover on touch/TV), Play (focused), Start over when resumable, favourite, watched toggle. Plot/cast never in fixed columns (WinUI rule).

**Series detail** — season selector, episode rows with watched/partial/unstarted state (bar + check), orange selected row, "Next episode" distinguished from "Continue" only when history supports both.

**Player** — picture-first. Overlay (auto-hides after 3 s, wakes on any input): title, info button (plot in an overlay panel, never reflowing the list), seekable time bar, ±10 s, play/pause, audio/subtitle picker, play-next mode chip (Off/Next/Shuffle), episode/related side sheet. Next-item prompt appears by `NextPrompt` rules with Cancel. Touch: tap = toggle overlay, double-tap sides = ±10 s, horizontal drag = scrub, vertical drag = volume/brightness (left/right halves), pinch = fit/fill. TV: D-pad left/right = seek (hold accelerates), up = overlay, OK = pause, Back = hide overlay then exit (existing back hint kept). PiP on phone/tablet.

**Settings** — Library (refresh, last sync, data used, Wi-Fi-only sync), Player (play-next modes per kind, default audio/subtitle language, resume behaviour, minimize→PiP), Account (provider, expiry, max connections reported not asserted, Change user, guarded Sign out, Diagnostics). Diagnostics stays Android's stronger user-facing screen (WinUI removed its own).

**Login** — keep D-pad ergonomics and "never clear a field as a side effect"; add touch-first variant; provider port optional per commit `6d73d0c` (verify `ServerAddress` parsing matches: empty port = default, never in URLs).

**Empty / loading / error** — the Bible state table stands; extend with "not yet indexed" vs "empty" vs "offline" vs "auth failed" per the cross-platform proposal. Never raw exception text (WinUI fixed this in Stage 5; confirm Android parity).

## 4. Feature plan

Legend: **S/M/L** effort · ★ = blocks Play release · 🅓 = needs an owner decision (see §9).

### 4.1 Playback correctness and control (highest user-visible gap)

| # | Feature | Source of truth | Size |
|---|---|---|---|
| P1 | Fixed-tail completion (episode 15 s; movie ≤ 4 min and ≤ 5 %) replacing the single 92 % rule; persist a **watched** flag (not just row deletion) 🅓 | `EpisodeCompletion.cs` | M |
| P2 | Mark watched / unwatched action on episodes and movies | `MarkEpisodeWatched` | S |
| P3 | Play-next modes Off/Next/Shuffle, separate per kind, persisted 🅓 | `PlaybackMode.cs`, `NextPlaybackSelector.cs` | M |
| P4 | Next-item prompt with lead window, Cancel, seek-out/re-entry; advance only on real engine end | `NextPrompt.cs` | M |
| P5 | Audio/subtitle picker; subtitle Off; language preference (also targets Q-5 "English audio on Arabic film") 🅓 | `TrackSelection.cs` → Media3 `TrackSelectionParameters` | M |
| P6 | Seekable time bar; hold-to-scrub acceleration with a safe short press | `HeldSeekAccelerator.cs` | S |
| P7 | Mid-playback stall reconnect (one attempt at the stalled position, then Retry) | `PlaybackUiTimer_Tick` logic | M |
| P8 | Player rewritten in Compose over `PlayerView`/`SurfaceView` with the control model above | — | L |
| P9 | `MediaSession` + notification, audio focus, headset/remote media keys, "becoming noisy" pause | — | M ★ |
| P10 | Picture-in-picture (phone/tablet), orientation: sensor-landscape in player, free elsewhere | — | M |
| P11 | Release the stream before starting the next (Q-11), PiP/background teardown, network-change handling | `max_connections` rule | M ★ |
| P12 | Live: channel up/down, last-channel, buffering UX tuned for `.ts` progressive | — | S |

### 4.2 My Tvivo and browse parity

| # | Feature | Size |
|---|---|---|
| B1 | Shelf-based landing for Home / Movies / Series / Live (new `ShelfScreen`, reusing `BrowseViewModel` paging) 🅓 | L |
| B2 | Pinned personal shelves + per-category shelves + "open shelf → grid" | L |
| B3 | Recently played / visit counts / **most-visited ordering** (new `visits` table; sort control) | M |
| B4 | Suggestions shelf (20, rating-weighted reservoir sampling, session-stable, excludes recent + continue) — **sample, don't materialize the catalog** (WinUI/Compose both had to fix this) | M |
| B5 | `NEW` badge: per-shelf last-visit timestamp, defaults to "now" on first run so nothing floods | M |
| B6 | Quality pill parsed from the title suffix (do it in `NameNormalizer`, test with the existing normalizer tests) | S |
| B7 | Card/episode progress bars from stored resume/duration/finished | M |
| B8 | Sort control (Most visited default, A–Z, Z–A, Recently added) | M |
| B9 | Search: persistent field, scope per mode, global Search destination with type filters and "indexed/ready" disclosure | M |
| B10 | Branded artwork fallback (initial/glyph) for missing posters and Live logos (QA-5) | S |
| B11 | Idle featured overlay: **dropped**; it was removed from WinUI too (`docs/decisions.md`), so there is nothing to port. | — |

### 4.3 Handset and tablet

| # | Feature | Size |
|---|---|---|
| H1 | Q-14 (phone crash on launch): owner reports **no longer crashing (2026-10-07)**; the manifest now has `leanback required=false` and a normal `LAUNCHER` entry. Treat as "not reproducing", not "root cause known": re-verify on the Mi 10 with the current build and a clean install, record the build and result, then close. The fixed 1920×1080 geometry still makes the phone layout unusable until Phase 1. | S ★ |
| H2 | Window-size-class layout system, `LocalIsTelevision`, per-class type and spacing | L |
| H3 | Touch navigation (bottom bar / rail), touch targets ≥ 48 dp, scroll + fling behaviour for grids | M |
| H4 | Touch player gestures, PiP, rotation, immersive mode, cutout handling | M |
| H5 | Long-press context menu on touch (the D-pad long-press menu already exists) | S |
| H6 | Metered-network behaviour: warn before streaming on mobile data; Wi-Fi-only catalog sync option; per-session byte counter (WinUI added a counter) | M |
| H7 | Predictive back, edge-to-edge, per-app language not needed (English only) | S |
| H8 | Foldables/large screens: resizable, multi-window safe (don't assume landscape), configChanges audit | M |

### 4.4 Resilience and data (carry WinUI's latest hardening)

| # | Feature | Size |
|---|---|---|
| R1 | 429 retry with backoff and quiet refresh (WinUI `5f3ad85`) in `XtreamApiClient`/`CachedFetch` | S |
| R2 | gzip catalog requests + byte counter shown in Settings → Library | S |
| R3 | Account identity includes scheme (shared-core `AccountIdentity` already shared — verify http/https separation test exists) | S |
| R4 | Room migrations v5 → v6+ for `visits`, `watched`, `shelf_visits`, `recently_played`; **never destructive** (Bible §1); add `MigrationTestHelper` tests | M ★ |
| R5 | Playback preferences in DataStore (modes, languages, wifi-only) | S |
| R6 | Account-scoped user data: favourites/resume/visits keyed by account identity so switching never mixes state | M |

### 4.5 Settings, diagnostics, observability

| # | Feature | Size |
|---|---|---|
| S1 | Settings with Library / Player / Account tabs (touch tabs, D-pad rail on TV) | M |
| S2 | Routine structured events (cache load/refresh with outcome+duration, playback start/stop with reason, stall, engine release, resume saves) — resolves T-D4; URL/credential redaction, size-capped rotation | M |
| S3 | Crash reporting decision 🅓: keep local `CrashDiagnostics` + user-initiated export (no SDK, simplest data-safety story) *or* add Crashlytics/Sentry (needs consent UI and data-safety disclosure). **Recommendation: local-only for 1.0.** | S |
| S4 | About screen: version, build, licences (OSS notices are required for Compose/Media3/Coil/etc.) | S ★ |

### 4.6 Deliberately not in scope

EPG (blocked on a provider endpoint — the WinUI check got a 404; revisit with `get_short_epg`), per-person profiles, watch-history stats page, casting/DLNA, voice search, custom subtitle import, light theme, second playback engine on Android (Media3 only; WinUI's engine choice is Windows-specific), cloud sync (needs a Tvivo service; the proposal marks it unverified), WinUI window-mini/cinema/pointer behaviours.

## 5. Architecture

1. **Shared logic, not shared UI.** Port the deterministic policies from `windows-spike/src/Tvivo.Core/` into the existing `shared-core` Kotlin module (it already holds `AccountIdentity`, `StreamUrlBuilder`, `AppError`): `EpisodeCompletion`, `PlaybackMode`, `NextPlaybackSelector`, `NextPrompt`, `HeldSeekAccelerator`, `TrackSelection`, progress presentation, shelf/sort/suggestion rules. The Compose Desktop client is an experiment slated for retirement, so don't design for it.
2. **Golden vectors to prevent drift.** Export each C# policy's test cases as JSON fixtures (`shared-core/src/test/resources/parity/*.json`) and run the same vectors from both test suites. This is the cheapest way to keep two native apps behaviourally identical, and it turns "parity" from a claim into a test.
3. **UI layer.** Keep Compose. Add `ui/adaptive/` (size class, `LocalIsTelevision`, spacing/type per class), `ui/shelf/` (shelf row, shelf screen, sort bar), `ui/player/` (Compose overlay), `ui/components/` (Card, Pill, ProgressBar, FadeSwap, SettingsTab). Decide in Phase 1 whether to drop `androidx.tv.material3` for plain Material 3 with an own focus frame: `TvFocusFrame` and `DpadField` already exist, and TV-only widgets are a prime Q-14 suspect. Keep `tv-foundation` only if lazy-grid focus restoration still needs it.
4. **Data.** New Room entities and DAO methods only through numbered migrations; `CODEMAP.md` regeneration via the pre-commit hook; no change to catalog TTL/sync tiers.
5. **Player.** `PlayerController` (Media3 + MediaSession + audio focus + stall watchdog) separated from `PlayerScreen` (Compose). One owner of the stream so release is deterministic (Q-11).
6. **Mock provider.** A local Xtream fixture server (MockWebServer-based for unit/instrumented tests; a small standalone Node/Kotlin script for emulator and Play reviewer demos) that serves a catalog and a short generated video with controllable `max_connections`, stalls, 429s and tracks. This unblocks the "no automated test may open a stream" rule and gives the Play review a safe credential set (§7). Check `desktop-fixtures/` and `windows-spike` Gate-9 fixtures for reusable media before writing new ones.

## 6. Quality bar and test strategy

- **Per phase exit gate** = four gates green (unit, lint, build, instrumented/D-pad) **plus** driven-on-emulator evidence (screenshot or dumped UI string), per Bible §4's principle that green builds say nothing about usability. Codex tests against written acceptance criteria and never marks its own work done (Bible §6).
- **Device matrix:** TV AVD (API 34, 1080p, no-touch), TV AVD API 28–30 (older boxes), phone AVD (small, 360 dp), tablet AVD (landscape 1280×800), foldable AVD, **physical Mi 10** (Q-14 reproducer), at least one low-RAM (≤ 2 GB) TV box if obtainable. Emulator rules from §4 of the bible apply (swiftshader, 1536 MB, stop Gradle daemons, never clear app data).
- **Accessibility:** TalkBack labels on every control, 48 dp touch targets, font scale 1.3×, contrast re-measured for new palette roles, focus order = reading order, reduced-motion honoured, `semantics` on progress bars (watched/partial/unstarted announced).
- **Performance budgets** (set now, measure in Phase 7): cold start to first shelf from cache ≤ 2.0 s phone / ≤ 3.0 s TV; scroll jank < 5 % frames over 60 s on the 48k grid; steady-state memory bounded by an artwork budget (match WinUI's 96 MiB decoded cap, scaled for 1–2 GB TV boxes); no growth across 20 mode switches. Ship a **Baseline Profile** and a Macrobenchmark module.
- **Safety nets that already caught real bugs:** the WinUI notes record a packaged-identity crash, a cache refresh wiping user state, and an HTTP/HTTPS collision. Add Android equivalents as explicit tests: refresh never deletes favourites/resume/visits, account switch never mixes state, upgrade from the current v5 database keeps data.

## 7. Release engineering and Play Store compliance

**Do before the first upload — `applicationId` is permanent.** `com.dev.Tvivo` reads as a placeholder. Choose the final ID, app name and developer account now; changing it later means a new listing and lost installs. 🅓

| Area | Work | ★ |
|---|---|---|
| Build | R8 on (`isMinifyEnabled`, `isShrinkResources`) with keep rules for Room, Retrofit/Gson DTOs, Tink, Media3; verify a release build on every form factor, since minification regressions only show in release | ★ |
| Signing | Generate an upload key stored **outside the repo** (the committed `debug.keystore` stays debug-only); enrol in Play App Signing; document the recovery procedure in `docs/`. Never commit keystore paths or passwords | ★ |
| Versioning | `versionName` aligned with WinUI (`1.x`), `versionCode` derived monotonic scheme; remove "-poc" | ★ |
| Format | Android App Bundle; split by ABI/density; verify APK size; `minSdk 24` kept unless a dependency forces more | ★ |
| Target SDK | Raise to the level Play requires at submission time (verify the current deadline then; do not assume 34 still passes) and handle new behaviour changes (foreground-service types for playback, edge-to-edge, predictive back, notification permission) | ★ |
| TV store | Leanback launcher + 320×180 banner, D-pad navigable, `touchscreen required=false` (present), TV screenshots 1920×1080, TV app quality checklist | ★ |
| Phone/tablet | Adaptive icon (foreground/background/monochrome), splash API, large-screen quality tier (resizable, no fixed orientation outside the player), phone/tablet/7"/10" screenshots | ★ |
| Content policy | An IPTV *player* is allowed only if it ships no content and facilitates no infringement. Keep the repo's rule: no provider name, host or channel list bundled; store listing must say "bring your own provider"; no third-party logos in screenshots or art | ★ |
| Reviewer access | Play reviewers need a working login. Provide a **demo mode / fixture provider** with openly licensed media (CC BY or CC0, with attribution; a hosted demo with public credentials needs bandwidth limits, otherwise prefer an offline in-app demo source) (and credentials in the Play Console "App access" form). Never submit real provider credentials | ★ |
| Screenshots/video | Produced from the fixture catalog with licensed or generated artwork, **not** the real panel (copyrighted posters would breach policy) | ★ |
| Privacy | Privacy policy URL; Data safety form: credentials stored encrypted on device only, no analytics, no ads, no data shared; state clearly if crash reporting is added (S3) | ★ |
| Network | Cleartext HTTP is required by many panels; keep the scoped permission, document the justification, keep system trust anchors, and **never disable certificate validation**. Add a visible hint when a provider uses `http://` | ★ |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE`; add `POST_NOTIFICATIONS` (media notification) and a media-playback foreground service type only if background audio is kept | ★ |
| Licences | OSS notices screen (S4) | ★ |
| Rollout | Internal testing → closed track (Play requires closed-testing for new personal accounts: verify the tester/duration rule at the time) → staged production (5 → 20 → 50 → 100 %), with Android vitals watched (crash and ANR rate) | ★ |
| Support | Support email, store listing copy in English, in-app "Export diagnostics" for bug reports | — |

## 8. Phased roadmap

Order follows dependency. The Bible's lesson holds: type scale and colour roles first, because they touch every screen. Delivery uses the existing pipeline: **Codex (sol) plans → Opus reviews → Codex (luna) implements in slices → Codex tests with evidence → Claude closes out**. No build, install or deploy happens without presenting the change set and getting an explicit go-ahead (standing rule). No JVM daemons while the emulator runs.

| Phase | Goal | Contents | Exit gate |
|---|---|---|---|
| **0 · Unblock & decide** | Know what's broken; settle decisions | H1 (re-verify Q-14 no longer crashes on the Mi 10 and close it); update Product/Project Bible scope; settle §9 decisions; final `applicationId`/name/wordmark; build the mock provider (§5.6); triage the ~20 "pending verification" TODO items against it and close or re-open each with evidence | Q-14 verified closed on the Mi 10 (or its trace captured if it recurs); all 🅓 decisions recorded in `docs/decisions.md`; mock provider streams a short video on the emulator |
| **1 · Design system & adaptive shell** | Identity and form-factor foundation | Tokens (colour roles, shapes, spacing, per-class type), components (Card, Pill, ProgressBar, FadeSwap, Tabs), `LocalIsTelevision` + size classes, top bar / rail / bottom bar shells, wordmark + splash + icons + TV banner; decide `tv-material` vs Material 3; **focus-model spike for shelves on TV** | App launches and navigates on TV, tablet and phone AVDs without crash; screenshots of the three shells approved by the owner; focus spike proves enter/return restore on a shelf grid |
| **2 · Data & policy ports** | Behaviour parity without UI | Room migrations (R4–R6), shared-core ports with golden vectors (P1, P3, P4, P5, P6 policies, B3–B5, B8 logic), R1/R2 resilience, preference storage | Vectors pass in C# *and* Kotlin; migration test from a real v5 DB keeps favourites/resume; unit and DAO tests green |
| **3 · Player** | Production-grade playback | P8 Compose player, P1–P7 wired, P9–P12, H4 gestures + PiP; fixture-driven acceptance (stall, 429, multi-track, end-of-stream autoplay) | Every player acceptance criterion observed on the mock provider on TV + phone; Q-4/Q-5/Q-11/U-14 closed with evidence; one connection released before the next |
| **4 · My Tvivo & browse** | Shelf landing and browse parity | B1–B10, series episode state (B7), detail redesign, global search | A tester who didn't build it finds, plays and resumes a title on all three form factors unaided (Product Bible success test); focus contracts re-verified |
| **5 · Settings, observability, handset polish** | Complete the product | S1–S4, H3/H5–H8, login touch variant, empty/error state audit, accessibility pass | TalkBack pass on the main flows; font scale 1.3× has no clipped text; diagnostics events appear for a healthy session |
| **6 · Release engineering** | Shippable artefacts | §7 in full; Baseline Profile + Macrobenchmark; R8 release verified; listing assets; privacy policy; reviewer demo | Signed release AAB passes install, upgrade-from-previous, and full smoke on every AVD + physical device; Play pre-launch report clean |
| **7 · Hardening & rollout** | Real devices, real panel (authorised) | One authorised real-panel session on the single connection (live, movie, episode, seek, stall, switch); soak test; perf budgets measured; internal → closed → staged production | Budgets met; crash-free ≥ 99.5 % and ANR rate under the Play bad-behaviour thresholds during the closed track; owner signs off |

Rough size: Phase 0 S · 1 L · 2 M · 3 L · 4 L · 5 M · 6 M · 7 M. Phases 2 and 1 can overlap after Phase 1's tokens and `LocalIsTelevision` exist; Phase 3 depends on 2; Phase 4 depends on 1 and 2; Phase 6 paperwork (policy, listing, signing) can start as early as Phase 1.

## 9. Decisions owed to the owner

1. **Completion/watched rule (P1):** port WinUI's fixed-tail rules (recommended, since the two apps then agree on what "watched" means) or keep Android's 92 %.
2. **Play-next defaults (P3):** WinUI defaults are episodes **Next**, movies **Shuffle**. Recommend the same defaults for parity, but on a handset a surprise movie auto-start burns mobile data. Option: movies default **Off** on phone only.
3. **Track-language defaults (P5):** default audio and subtitle language; explicit subtitle-off policy.
4. **Shelf landing (B1):** confirmed. Resolved 2026-10-07: a category side panel, hidden until revealed (TV: D-pad Left at the grid edge or a Categories button; touch: edge swipe or button), as a fast jump in addition to shelves.
5. **Idle featured overlay (B11):** resolved 2026-10-07: dropped (also removed from WinUI).
6. **Crash reporting (S3):** local-only for 1.0 (recommended) or an SDK with consent.
7. **Identity:** `applicationId`, store name, adopt the WinUI wordmark as official, icon concept.
8. **Background audio / foreground service:** needed for PiP/background listening? Recommend PiP only; it avoids a foreground-service declaration for 1.0.
9. **Compose Desktop retirement:** affects whether any code is ever shared with it. Plan assumes retired.

## 10. Risks

| Risk | Mitigation |
|---|---|
| Shelf focus model on TV is a rewrite of the most fragile area (`focusRestorer` already proved unsafe on Compose 1.6.8) | Phase 1 spike before committing; keep both restore paths; instrumented focus tests per shelf |
| Q-14's cause is deeper than a flag (TV-only library, fixed geometry) | Phase 0 diagnosis gate; Phase 1 decision on `tv-material` |
| Only one stream connection, so live verification is scarce | Mock provider for everything; one authorised real-panel session in Phase 7 |
| R8 breaks Room/Gson/Tink only in release | Release-build smoke at every phase exit, not just Phase 6 |
| Play rejects an IPTV app or its screenshots | Content policy and fixture assets first (§7); no real catalog art anywhere in listing |
| Tink keyset loss on OEM devices bricks login (the keyset is not recoverable) | Graceful "re-enter your details" path on keyset failure; never offer clear-data guidance; keep `allowBackup=false` |
| Scope creep from "parity" | Anything outside §4 needs a written decision; §4.6 is the declined list |
| Low-RAM TV boxes | Artwork memory budget, paging stays bounded, test on an API 28–30 AVD and a real low-RAM device |

## 11. First actions after approval

1. Confirm Q-14 stays fixed on the Mi 10 with the current build. If it ever recurs, capture `adb logcat -c`, launch, `adb logcat -d AndroidRuntime:E *:S` before theorizing.
2. Update both bibles and `TODO.md` with the new scope, converting this plan's tables into trackable items.
3. Settle the nine decisions in §9, record them in `docs/decisions.md`.
4. Hand Phase 0 and Phase 1 to Codex (sol) for a detailed plan, then Opus review, per the pipeline.

## 12. Monetization and setup QR (decided 2026-10-07, supersedes provider white-label ideas)

**Principle: Tvivo stays a neutral player.** No provider names, hosts or logos in the app, listing, screenshots or marketing; no provider portal, accounts or promotion; terms of use say users are responsible for the rights to what they watch; a working takedown/contact address. "Users are responsible" is not a shield by itself: what protects Tvivo is that the feature set is generic and the formats are open.

### 12.1 Revenue
| Source | Decision |
|---|---|
| Ads | **None.** (Kids may watch; no third-party ad content.) |
| **Tvivo Plus via Google Play Billing** | Main paid route. Optional, cheap, one-time and/or yearly, local pricing for Egypt. Core playback, parental controls and languages stay free. What is free and what is Plus is fixed in §12.8. |
| Supporter / pay-what-you-like | Optional Play product, no features attached. |
| Licensed B2B (outside Egypt, or later) | Hotels, hospitals, ISPs/telcos, channel owners, kids-content publishers who hold rights. They may get a **signed Plus entitlement** (below) paid outside Play. **Never issued to unlicensed providers**; contract includes a rights warranty. |

Check before launch: Play payments policy on features unlocked by an organisation's contract outside the app, and Egypt-specific billing options (carrier billing, local cards). Never sell the unlock in-app by any route except Play Billing, and never tell users in-app to "pay your provider for Plus".

### 12.2 Setup QR / setup code (free, open format, Phase 2)
Payload (versioned, documented in `docs/setup-code-format.md` so anyone can generate it): `v`, `name` (subscription label), `server` (host, optional port, optional scheme), `user`, `pass`. Optional `ent` (signed entitlement, §12.3). Encoded as a `tvivo://setup/v1?...` URI or base64url JSON; keep it small enough for a reliable QR.

Rules:
- **Always show a confirm screen** ("Add this source?") with the name and host, editable, before anything is saved or any request is made. A QR must never silently point the app at a server or sign in.
- Validate and sanitize: length caps, strip control and bidi-override characters, port range, no auto-play, no network call until confirmed.
- **The QR is a secret** (it contains a password): store straight into the Tink store, never log it, never put it in diagnostics export, warn at scan time.
- Name is **plain text with a generic icon**. No images from unsigned QR (impersonation and decoding risk). The app shows the label quietly (source name), exactly as if the user had typed it; a user-typed source and a scanned source are indistinguishable.
- Scanner: Google code scanner API (no camera permission). **TV has no camera**: sources reach the TV through phone-to-TV pairing (§12.6), not typing.
- Generator: a **generic** static, offline, open-source page or script. No accounts, no hosting relationship, no provider-specific features.

### 12.3 Signed entitlement (Phase 5, only when a licensed partner exists)
Token inside the QR: opaque partner id, display name, small logo, tier, expiry, optional activation cap, signed (Ed25519) with a private key **kept offline, only the owner issues**. App verifies offline against an embedded public key; invalid or absent = ignored, free version. Verified token unlocks Plus and shows a small "Provided by <name>" mark. Leaks handled with expiry, activation cap and a small revocation list fetched occasionally (needs a tiny server). Do not build this before a licensed partner is real.

### 12.4 Product and engineering consequences
- **Saved sources:** a user can save several sources with their own names and switch between them (this is also the "combo box"); no bundled provider list.
- **Brand-pack / flavor layer:** not built now. Revisit only for a licensed customer.
- **Languages stay free.** Arabic UI would reopen the Product Bible commitment (English LTR chrome, no mirroring); decide separately.
- **Play Console:** data-safety form unchanged (credentials stored encrypted on device only); add Play Billing and the Plus products; listing says "bring your own source".
- **Plan impact:** Phase 2 adds QR/setup-code import + saved sources; Phase 5 adds Plus billing, the Plus gate and (optionally) the entitlement verifier; Phase 6 adds the billing/listing items above.

### 12.5 New decisions owed
10. Resolved: Plus v1 feature list in §12.8. Still open: final prices (§12.7 are hypotheses).
11. Resolved: phone-to-TV pairing (§12.6). Still open: whether the optional encrypted relay is ever built, decided after real-user testing of same-Wi-Fi pairing.
12. Resolved: Arabic reopened, free, with RTL mirroring (§12.8). Product Bible text still to be updated.

### 12.6 Phone-to-TV pairing (decided 2026-10-07; for non-technical and senior users)
Direction: **the TV shows the code, the phone enters it.**
1. TV: "Add a source" shows a 4-digit one-time code and the TV name.
2. Phone (same Tvivo app): **Add to TV** → scan the provider/share QR (or pick a saved source) → choose the TV from a discovered list → enter the 4 digits.
3. TV: confirm screen "Add this source? <name, host>", one large Yes. Nothing is saved without it.

Security: the code only proves the phone user can see the TV. Credentials are **encrypted to that TV's key** (TV generates a keypair per pairing session) and are never readable by a relay. Code expires in about 5 minutes, is single use and attempt-limited; the TV rejects anything unsolicited. The 4 digits are a rendezvous check, not the secret.

Transport, in order:
1. **Same Wi-Fi, no server** (Android NSD/mDNS discovery). Zero cost, no custody of credentials.
2. Fallbacks: manual entry on the TV; an **optional encrypted-only relay** (stores ciphertext ≤ 5 minutes, deletes on pickup, rate-limited, a few dollars a month), built only if same-Wi-Fi proves unreliable (guest or isolated networks).

No server is needed to create the free QR. It is generated **inside the app** ("Share this source as QR", with a "contains your password" warning) or by the generic offline page. A server appears only for the optional relay and, later, entitlement revocation (§12.3).

### 12.7 Suggested Play pricing (hypotheses to test, not data)
Per-country pricing; verify the current EGP rate and Play minimum prices before fixing numbers.

| Product | Egypt (approx.) | Gulf / Europe / US |
|---|---|---|
| Plus, one-time | $2–3 (set in EGP) | $9.99 |
| Plus, yearly | ~$1 | $3.99 |
| Supporter tip | $1 / $3 / $5 | $2 / $5 / $10 |

No monthly plan. 7-day free trial on yearly. Conversion is expected to be low single digits; larger revenue is expected from licensed partners abroad (§12.1).

### 12.8 Free vs Plus split (decided 2026-10-07)
**Rule:** if a first-time user would feel cheated by its absence, it is free. Plus is a thank-you with conveniences for heavy and household use; it never gates anything needed to find, resume and watch content. The owner needs income but wants free users to be genuinely happy; no nags, no degraded free experience.

**Free, always:**
- All playback (movies, series, live), seek, resume, **Continue Watching in the app and on the Android TV home screen (Watch Next row)**, favourites, recently added.
- Basic audio and subtitle selection, sleep timer, play-next modes, PiP, mark watched.
- Setup QR import, "Share as QR", phone-to-TV pairing, manual entry, **at least two saved sources**.
- Parental PIN and category hiding (single profile).
- Languages (English and Arabic), diagnostics export, search, sort, catalog refresh.

**Plus v1 (Play Billing):**
1. **Profiles, including the parent-curated Kids profile** (headline Plus feature, see "Kids profile" below): separate favourites, history and resume per household member.
2. Unlimited saved sources (beyond two).
3. Backup and restore of sources, favourites and resume (to a file).
4. Themes and accent colours.
5. Advanced subtitles: size, colour, delay (plus audio delay).
6. Home customization (reorder/hide shelves) and custom favourite lists.

**Later candidates, not v1:** watch-history/stats page, data-saver quality caps, cloud sync (needs a server), EPG guide (needs provider support), external subtitle files. Casting is declined.

**Kids profile (Plus, decided 2026-10-07):**
- **Allowlist, not blocklist:** the profile shows only what a parent picked (titles, whole series, whole categories), grouped into named parent-made folders (e.g. "Cartoons"). Default-deny, because provider catalogs are huge and unmoderated.
- **Picking:** from the parent profile, **Add to Kids** in the long-press menu, on the detail page and on a category; works with D-pad and touch.
- **Kids home:** only the picked folders plus Continue Watching limited to kids' items. No global search (or search only within picked items), no Recently Added, no suggestions, no Settings, Account, Sign out or source switching. Back never leaves the profile.
- **Leaving** the profile needs the parent PIN (the PIN itself stays free).
- **Data:** `profile` table (id, name, kind) and `profile_allow` (profile id, account identity, content type, item or category id); favourites/resume/visits already carry the profile id from Phase 2. A pick resolves by stable provider item id; a title the provider removes silently disappears, a picked series shows new episodes.
- **Free stays free:** the single-profile parental PIN and adult-category hiding.
- **If Plus lapses:** existing profiles, kids included, **keep working**; only creating new profiles and editing the allowlist lock until renewal (supersedes the "hidden" wording above for profiles).
- **Wording and policy:** the app never claims "safe for kids" (parent chooses from a provider's library). Tvivo is not marketed as a children's app; verify Play's Families policy before writing the listing.

**Support prompt:** one polite, dismissible-forever "Enjoying Tvivo? Plus helps keep it going" message after about 30 days of use. Never repeated after dismissal, never blocking, never shown during playback.

**Engineering notes:** gate by a single `PlusEntitlement` interface (Play Billing now, signed partner token later per §12.3) so no feature checks billing directly; the free path must work offline and when Play Billing is unavailable (no Play Services, sideload); if a Plus subscription lapses, nothing is deleted. Extra profiles and sources go read-only-hidden, not removed, and return on re-subscribe. Phase placement: Plus gate and items 1–6 in Phase 5, with data-model hooks (profile id on favourites/resume/visits) added in Phase 2 so the migration is done once.

**Arabic (reopened 2026-10-07, free):** language setting (English, Arabic); Arabic mirrors the whole layout and English stays LTR; mixed-direction titles stay handled at the data layer. Layout code uses start/end, never left/right, from Phase 1; D-pad focus order must mirror under RTL. Requires updating the Product Bible's "English LTR, no mirroring" commitment. WinUI is English-only, so Android leads on languages.
