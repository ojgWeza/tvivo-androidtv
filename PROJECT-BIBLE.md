# Tvivo Project Bible

> **Constitutional.** This file and `D:\HCode\DEV-BIBLE.md` are binding for every session, on every task, without exception. If a task conflicts with a documented rule, surface the conflict rather than quietly deviating.

---

## §0 Session Protocol

Read in this order:

1. **`D:\HCode\DEV-BIBLE.md`** — global rules for all D:\HCode projects (constitutional)
2. **This file: `PROJECT-BIBLE.md`** — project-specific rules and state (§1-7 below)
3. **`PRODUCT-BIBLE.md`** — product spec, users, design principles, commitments
4. **Memory files** in `C:\Users\Dell\.claude\projects\D--HCode-Tvivo\memory\`:
   - `MEMORY.md` (auto-loaded index)
   - Relevant topic files (see memory index for what exists)
5. **Work queue:** `TODO.md` (open items only) when picking a task

### Session-start self-check (Claude/Codex both)

1. Read `D:\HCode\DEV-BIBLE.md`, `D:\HCode\bible-detail\todo-and-bible-maintenance.md`, this file
2. Read `TODO.md` to identify open work
3. Read memory index to check for carry-over notes
4. Delegate to Codex through the installed skill or background `codex exec` (see §6)
5. Pick work from `TODO.md` directly; do not wait for the user to restate

---

## §1 Build State

**What this is:** An Android app (native Kotlin, one codebase for TV, phone and tablet) that connects to an Xtream Codes IPTV panel and lets the user browse and play Live TV, Movies, and Series. Built by Hoda as a personal project, separate from her Health Insights / Medica Cloud Care work.

**Scope change 2026-10-07:** the app is being taken to a **public Google Play release for TV + phone + tablet**, adopting WinUI's mature identity and its My Tvivo shelf model, with an English/Arabic UI and an optional "Tvivo Plus" (Play Billing). The authoritative plan is **`docs/android-production-plan.md`** (phases 0–7, §12 for monetization, setup QR, pairing and the free/Plus split). Where older text below says "Android TV only" or "TV-only", this scope change governs. The Phase 5 "Hardening" item in §3 is superseded by that plan's phases.

### Current state (as of 2026-09-16)

**Phases 0-4 are built and working against the real panel** (since 2026-09-08), plus an Account screen (`ui/settings/`) carrying sign out, switch account, refresh everything, exit, and the subscription expiry. The app authenticates, browses movies, live channels and series by category, plays movies, and shows a season/episode picker per show.

- **Catalog scale:** 48,780 VOD rows, 6,424 live channels, 13,279 series shows synced and cached
- **Test coverage:** 158 unit tests passing
- **Playback paths never exercised:** Live and episode playback cannot be automated tested (max_connections = 1)

### Critical architectural decisions (2026-09-10)

**Activating a card does not play anything.** Every content type opens a pre-run page (`ui/detail/`) carrying poster, title, description, Play (focused on arrival), and a favorite heart. `ContentGrid`'s callback is `onActivate`, not `onPlay`. The long-press menu keeps a direct Play as the shortcut. A movie`s description comes from `get_vod_info`, fetched lazily and cached — this panel sends no `plot` on `get_vod_streams` (verified over a forced re-sync of all 48,780 rows).

**The rail leads with three synthetic folders** the panel does not publish — `RECENTLY ADDED` (100), `CONTINUE WATCHING` (50), and `FAVOURITES` in that order, above the panel`s categories. They are `CategoryEntity` rows with `__`-prefixed ids; see `docs/architecture.md`.

**Room DB is v7 (in development; v5 is the last shipped version), on real migrations.** Never restore `fallbackToDestructiveMigration` — it drops critical user data including `favourites`, `resume_positions`, and watched state. v5 added `rating` to `vod_streams` and `series`; nothing back-fills it, so ratings appear per category as each one re-syncs. v6 (2026-10-07) adds profiles (`profile`, `profile_allow`), `watched`, `visits`, `shelf_visits`, `recently_played` and a `profileId` on `favourites`/`resume_positions`; because SQLite cannot widen a primary key in place, `MIGRATION_5_6` rebuilds those two user tables by a transactional copy-and-swap, so it is verified by tests from v3, v4 and v5 and by an independent review before release. v7 adds nullable detail metadata with `MIGRATION_6_7`, preserving all user tables (see `docs/decisions.md`).

### Focus handling (complex, both restore paths required)

Every focusable surface has two distinct restore paths and **both are required:**
- Re-entering the grid from the rail: `focusProperties { enter }`
- Returning from the pre-run page: `pendingFocusItemId` and must *request focus*, not merely scroll

`focusRestorer()` is **not** a substitute — on Compose 1.6.8 alongside an explicit `focusGroup()` it leaves nothing focused, which on a remote means the app stops responding. The reasoning is in `docs/architecture.md` under paging.

### Testing login screen

**To test the login screen, use Account → "Sign in to a different account" and enter a deliberately wrong account.** That path does **not** wipe anything: it routes to Login and keeps the current credentials until a new sign-in is *accepted*. Only the separate `Sign out` button calls `store.wipe()`. Clearing app data destroys credentials that cannot be recovered — the Tink keyset is not exportable.

### Running the app

Use `tools/emulator.sh`, never bare `emulator.exe`. It stops the Gradle daemons first and passes the two flags this machine requires (details in §4 below).

---

## §2 Non-negotiable Constraints

**Stack choice:**
- **Native Kotlin**, not Flutter — chosen specifically for Android TV D-pad focus handling and leanback support. Do not suggest switching.
- **Media3 ExoPlayer** for playback
- **Room** for local caching with a 24h TTL (per-category), plus a manual refresh button that bypasses the TTL. There is a **full-catalog sync tier** on top of the TTL that must never block the UI. See `docs/architecture.md` for exact caching logic.

**Streaming:**
- Streams are `.mkv`, `.mp4`, `.ts` direct files (not HLS manifests) for movie/series/live — progressive download, not adaptive streaming
- All playback URLs are constructed client-side from `stream_id`/`episode_id` + `container_extension` (or `ext` for live) — see the URL pattern table in `docs/xtream-api-reference.md`
- Never assume a `direct_source` field is populated; it`s typically empty on this panel

**Neutrality, setup QR and Plus (2026-10-07):**
- Tvivo stays a **neutral player**: no provider name, host or logo in code, assets, listing, screenshots or marketing; no white-label builds or provider portal for unlicensed providers. Store screenshots and the Play reviewer demo come from a fixture catalog, never the real panel.
- **Setup QR/code** is an open documented format; always show an editable confirm screen before saving or contacting a server; treat it as a secret (store straight into Tink, never log, never export in diagnostics); an unsigned QR carries plain text only, no images.
- **Phone-to-TV pairing:** the TV shows a one-time 4-digit code (about 5 minutes, single use, attempt-limited); credentials are encrypted to the TV's per-session key; same Wi-Fi first, no server. An encrypted-only relay is built only if same-Wi-Fi proves unreliable.
- **Plus gate:** every Plus feature checks a single `PlusEntitlement` interface (Play Billing now, signed partner token later). The free path must work offline and without Play Services. Never offer to sell Plus in the app by any route except Play Billing. If Plus lapses nothing is deleted; existing profiles keep working and only creation/editing locks.
- **No ads, no analytics SDK.** Crash diagnostics stay local with user-initiated export unless an explicit decision changes it.

**Visual:**
- **Card sizes are an image-pipeline input, not styling.** `POSTER(220×330 px)` and `CHANNEL(220×124 px)`. Posters are downsampled to card size before caching, so changing either means re-encoding the whole cache.

**Configuration:**
- **`max_connections` is an account property, never an app rule.** It is `1` on the development account; other panels differ. The UI reports the value read from `server_info` and must never state a stream limit as a product fact.
- **Provider-agnostic.** No panel hostname, port, provider name, or account detail is ever hardcoded or committed — this repo is public. The server and port are user-entered at runtime and read back from `server_info`, so the same build works against any Xtream Codes panel (and later any M3U/list source) the user points it at. Docs use placeholder hosts only.

**Security & network:**
- **Credentials** (server, port, username, password) must be **encrypted at rest** and never in plaintext, never committed. Current implementation: DataStore + Tink. `EncryptedSharedPreferences` is deprecated, does synchronous crypto on the main thread, and has keyset-corruption crashes on OEMs most Android TV boxes run.
- **Cleartext HTTP must be explicitly permitted** via `network_security_config.xml`, or the app cannot reach the panel on `targetSdk 28+`. Never disable certificate validation to work around a bad cert — permit cleartext, keep system trust anchors.

**Assets:**
- **No third-party brand marks in shipped assets.** Screen stock imagery for logos before use; see `docs/design/img/CREDITS.md`.

---

## §3 Build Order & Design Pass

### Build phases (0-5)

**Phase 0:** Skeleton + install loop (TV manifest, network security config, `adb connect` workflow, stable debug signing key, one-time `dumpsys media.codec` probe, verify whether `get_vod_streams` works with no `category_id`)

**Phase 1:** Auth screen (one server field parsing `host:port`, user, pass; validate via `player_api.php`) then the Home screen (Live / Movies / Series)

**Phase 2 (DONE):** Movies vertical slice end-to-end (rail → grid → player). This is the pattern every other content type follows. Four things must land here, not in Phase 5, because everything copies them:
- `enablePlaceholders = true` with stable keys
- Focus restoration by item ID
- Focus frame plus last-active state
- Long-press context menu

**Phase 3 (DONE):** Live TV with 16:9 channel card (not poster card) and `ext` not `container_extension`

**Phase 4 (DONE):** Series — one extra layer (category → shows → `get_series_info` → season/episode picker → play). Episode ids are **strings**; `duration_secs` is wrong on this panel (read `duration`); empty `get_series_info` must never wipe a cached season. Adds `ui/series/`: a show is not playable, so activating one opens the season/episode picker and `MainActivity` routes on content type. Browse UI stays typed to `BrowseItem`, not to any entity.

**Phase 5 (SUPERSEDED 2026-10-07):** Hardening — RTL, empty/loading/error states, `RefreshWorker`, physical TV. Replaced by phases 0–7 in `docs/android-production-plan.md` (Unblock and decide, Design system and adaptive shell, Data and policy ports, Player, My Tvivo and browse, Settings/observability/handset polish, Release engineering, Hardening and rollout). Hardening items carry over into those phases.

### Design pass (2026-09-08, decided and unbuilt)

Sits alongside Phase 5, not inside it.

**UI work (D-1..D-12):** Login layout, type scale, colour roles, rail width, overlaid card titles, Home icon row, tiles, splash, app mark, guarded sign-out

**Features (D-13..D-17):** Category filter, item filter, read-only Subscription screen

**Priority order:** Land D-4 (type scale) and D-5 (Material colour roles) first — they touch nearly every screen, and building anything else before them means building it twice.

---

## §4 Testing & Emulator Environment

### Testing strategy

**Emulator-first.** Create the AVD at the same API level as the physical TV (`adb shell getprop ro.build.version.sdk`).

**Test types:**
- Local unit tests: JUnit + kotlinx-coroutines-test + Turbine (repositories), Room in-memory DB (DAO + transaction behavior), MockWebServer (API contract and season-keyed-object parsing), Robolectric where framework classes are unavoidable. Run with `./gradlew test`
- Instrumented: Compose D-pad focus traversal on the Android TV emulator
- No automated test may open a stream — `max_connections` is 1
- Physical-TV validation happens once, after Phase 5. Accepted risk; the live `.ts` path carries the most exposure

### Critical testing principle

**Unit tests are necessary but nowhere near sufficient here.** Every defect found so far — the login focus trap that made the app unusable on a remote, the password leaking into the IME suggestion strip, the crushed Home tile, the missing back stack — passed a green build and 158 green unit tests. All were found by driving the emulator over `adb` and looking at a screenshot.

**Budget for emulator driving on every UI change, and treat "it compiles and launches" as saying nothing about whether the screen is usable.**

Full coverage map and edge cases: `~/.gstack/projects/ojgWeza-tvivo-androidtv/Dell-main-eng-review-test-plan-*.md`

### Emulator environment notes

Recorded because each cost real time to diagnose and will recur.

**Media volume:** Defaults to 3/15. Playback is inaudible and looks like an app bug. Confirmed 2026-09-07: the stream was AAC, decoded cleanly by `c2.android.aac.decoder`, no decoder errors — the volume was simply low. Raise with 14× `adb shell input keyevent 24`.

**Keyboard setting conflict:** `hw.keyboard=yes` breaks Esc-as-Back. The host keyboard becomes a real HID device, so Esc arrives as `KEYCODE_ESCAPE` (111) instead of `KEYCODE_BACK` (4). Fix is environmental: Extended Controls → Settings → General → "Send keyboard shortcuts to" = Emulator controls. **Do not add a `KEYCODE_ESCAPE` handler to app code** — no real TV remote sends that key.

**Typing into the guest:** Needs `forwardShortcutsToDevice` registry setting, stored outside the repo and AVD at `HKCU\Software\Android Open Source Project\Emulator\set`, value `forwardShortcutsToDevice` (REG_SZ `true`/`false`). Absent by default, so a fresh emulator profile silently loses it and the host keyboard stops typing. Restore with:
```powershell
Set-ItemProperty -Path 'HKCU:\Software\Android Open Source Project\Emulator\set' -Name forwardShortcutsToDevice -Value true -Type String
```
Takes effect only on emulator restart. **This directly conflicts with the Esc-as-Back setting above** — `true` sends keys to device (host keyboard types, Esc is `KEYCODE_ESCAPE`, Back breaks); `false` keeps emulator shortcuts (Esc is Back, host keyboard does not type). Pick per task; they cannot both be satisfied.

**Credential input:** Prefer pasting over typing. `clipboardSharing` is already `true` in that same registry key, and `adb shell input text '<value>'` works regardless of either setting. Neither needs the host keyboard.

**GPU:** `-gpu swiftshader_indirect` is required. The host GPU path paints a black window while the guest renders correctly.

**Memory:** `-memory 1536` and stop the Gradle daemons first. 16 GB total does not fit the emulator plus Gradle (1536m) and Kotlin (768m) daemons. Emulator gets OOM-killed mid-session otherwise. `tools/emulator.sh` does all of the above.

**Binary corruption:** `adb shell` corrupts binary on Windows (newline translation). Use `adb exec-out` to pull databases or screenshots.

**Touchscreen:** Android TV AVDs have no touchscreen (`hw.screen=no-touch`). Mouse clicks do nothing by design; `input tap` never works. Drive with `keyevent` or arrow keys.

**Device matrix (2026-10-07, phone/tablet in scope):** TV AVD API 34 (1080p, no-touch), TV AVD API 28–30, phone AVD (about 360 dp), tablet AVD (landscape), foldable AVD, physical Xiaomi Mi 10, and a low-RAM (≤ 2 GB) TV box if obtainable. Touch AVDs accept `input tap`; the no-touch rule above applies to TV AVDs only. Every phase exit runs a release (R8) build smoke on each form factor, since minification regressions only appear in release. Playback checks use the local mock Xtream provider in `tools/mock-provider/` (run instructions: `tools/mock-provider/README.md`; start with `node tools/mock-provider/server.mjs`). Its media is generated locally with ffmpeg by `tools/mock-provider/generate-media.mjs`. Automated tests never open streams. The real panel is reserved for one-at-a-time short checks on the TV emulator during hardening.

---

## §5 Conventions

- **Technical content in this repo is always in English**, regardless of the language used to discuss the project elsewhere.
- **Keep docs actionable and non-redundant.** Update `docs/architecture.md` and `docs/xtream-api-reference.md` in place as implementation reveals new details, rather than letting this file or the docs drift out of sync.
- **LG webOS is out of scope** until the Android POC is working end-to-end. Don't introduce cross-platform abstractions "just in case" — they're premature here.

### Considered and not taken (recorded to prevent re-proposals)

- **Idle dim / screensaver:** A static rail on an OLED panel risks burn-in after ~5 min idle. Small and self-contained; judged not worth tracking yet.
- **Manual refresh control placement:** Now built into the browse header.
- **Voice search:** Most Android TV remotes have a microphone, and search is the only place outside login asking for typing. Declined as a whole integration for one field; the rail's category filter already keeps most navigation typing-free.
- **`KEYCODE_ESCAPE` handling in the player:** An emulator config artifact (see §4), not a product requirement.

---

## §6 Claude/Codex Division of Labor

**Binding.** Codex is reached through the installed Codex skill/plugin or plain background `codex exec` (prompt from a handoff file, `< /dev/null`). There are no panes to manage and nothing to clear: every run starts fresh, so each handoff must be self-contained.

### Roles (fixed, do not renegotiate per-task)

**Codex plans and implements.**
- Writes the plan for any substantial item (read-only pass) and implements approved slices
- Default model `gpt-6-sol`, effort `high`; the owner may name a cheaper tier (e.g. `gpt-6-luna`) for routine slices
- Each slice ships with tests and a written report (files, decisions, exact test counts, what is runtime-only, manual test script)

**Claude orchestrates, reviews and verifies.**
- Writes the handoff (scope, do-not-touch list, acceptance criteria that name observable evidence)
- Has the plan reviewed independently (a stronger model reviews before implementation starts), folds in the corrections, then dispatches slices in order
- Compiles and runs the unit-test gate after each slice (standing go-ahead); reads the report and spot-checks risky claims instead of re-verifying everything
- Never claims "fixed" or "running" without evidence: a log line, a test count, a screenshot, or a crash log. Anything not yet seen on a device is listed as PENDING
- Owns closing the item: check it off `TODO.md`, add the closure note to `docs/decisions.md`, tell the user what changed

**The user approves.**
- Neither agent pushes, deploys or opens a PR without the user seeing the diff first
- Emulator/device installs and deploys need the user's go-ahead (compile and JVM tests are pre-approved)

### The loop, per item

1. Claude writes the handoff and acceptance criteria (what to observe, not only what to build)
2. Codex produces the plan; an independent review checks it against the real code and logs
3. Claude revises the plan from the review, then dispatches slices to Codex one at a time
4. Claude runs the gate (`:shared-core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` for Android) and reads the slice report
5. The user tests the build; their findings become the next fixes handoff. A fix is only closed when the symptom is gone on the device or the cause is proven in a log

### Evidence rules

- A green build is not a fixed feature. Prefer the crash log (`adb logcat -b crash`) and a reproduction over reasoning from source alone
- UI checks dump to a file and extract only the strings that matter; never print a full hierarchy to the console
- Read a tight window around cited file:line, not whole files; capture each screenshot once, after the screen settles

### Escalation

If Codex's plan or the review surfaces a scope/architecture disagreement (not a straightforward correction), **stop and ask the user** rather than overruling it. If a slice fails its tests, it goes back to Codex with the exact failing output; Claude fixes directly only small, obviously mechanical breakages (e.g. a missing parameter) and says so.

---

## §7 Lessons Learned

### 2026-09-16 (Session: Complete Documentation Restructuring)

**Decision:** Consolidated 17 scattered detail files (8 `claude-*.md` + 9 `product-*.md`) into this unified `PROJECT-BIBLE.md`, following the EMR project pattern.

**Why:** The old structure had 3 conflicting entry points, undefined "relevant detail files" rule, invisible shared files, and ~170 lines of mandatory loading per session with guesswork.

**Result:** Single unified bible (§0-7), crystal-clear session protocol, ~209 lines upfront but zero ambiguity afterward. Eliminated need to maintain 17 separate files.

### 2026-09-14 (D-Desktop-10-REGR, plan-review-evidence workflow)

**Decision:** Adopted a Claude/Codex workflow with plan review before execution and test evidence requirements.

**Why:** Prior "fixed twice" incident on D-Desktop-10 showed that build-success ≠ feature-works; need structured plan+review+evidence.

**Result:** QA-10 closed cleanly; evidence rules now live in §6.

---

## §8 Related Documentation

For implementation details, architecture, and decisions:
- **`docs/android-production-plan.md`** — Android production release plan: identity, form factors, features, phases, Play compliance, monetization (§12)
- **`docs/architecture.md`** — system shape, caching logic, focus restoration
- **`docs/decisions.md`** — rationale for build choices, archived closure notes
- **`docs/ui-scope.md`** — layout system, type scale, card dimensions, state table
- **`docs/design/comps.html`** — approved visual reference (1920×1080, real metrics)
- **`docs/xtream-api-reference.md`** — URL patterns, endpoint contracts
- **`docs/design/img/CREDITS.md`** — asset sourcing and licensing

For product context (optional, if separate file created):
- **`PRODUCT-BIBLE.md`** (if created) — users, positioning, capabilities, product principles
