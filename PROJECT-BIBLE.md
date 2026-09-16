# Tvivo Project Bible

> **Constitutional.** This file and `D:\HCode\DEV-BIBLE.md` are binding for every session, on every task, without exception. If a task conflicts with a documented rule, surface the conflict rather than quietly deviating.

---

## §0 Session Protocol

Read in this order:

1. **`D:\HCode\DEV-BIBLE.md`** — global rules for all D:\HCode projects (constitutional)
2. **This file: `PROJECT-BIBLE.md`** — project-specific rules and state (§1-7 below)
3. **Memory files** in `C:\Users\Dell\.claude\projects\D--HCode-Tvivo\memory\`:
   - `MEMORY.md` (auto-loaded index)
   - Relevant topic files (see memory index for what exists)
4. **Work queue:** `TODO.md` (open items only) when picking a task

### Session-start self-check (Claude/Codex both)

1. Read `D:\HCode\DEV-BIBLE.md`, `D:\HCode\bible-detail\todo-and-bible-maintenance.md`, this file
2. Read `TODO.md` to identify open work
3. Read memory index to check for carry-over notes
4. If working via Herdr: check for existing Codex pane before starting a new one (see §6)
5. Pick work from `TODO.md` directly; do not wait for the user to restate

---

## §1 Build State

**What this is:** An Android TV app (POC first, native Kotlin) that connects to an Xtream Codes IPTV panel and lets the user browse and play Live TV, Movies, and Series. Built by Hoda as a personal project, separate from her Health Insights / Medica Cloud Care work.

### Current state (as of 2026-09-16)

**Phases 0-4 are built and working against the real panel** (since 2026-09-08), plus an Account screen (`ui/settings/`) carrying sign out, switch account, refresh everything, exit, and the subscription expiry. The app authenticates, browses movies, live channels and series by category, plays movies, and shows a season/episode picker per show.

- **Catalog scale:** 48,780 VOD rows, 6,424 live channels, 13,279 series shows synced and cached
- **Test coverage:** 158 unit tests passing
- **Playback paths never exercised:** Live and episode playback cannot be automated tested (max_connections = 1)

### Critical architectural decisions (2026-09-10)

**Activating a card does not play anything.** Every content type opens a pre-run page (`ui/detail/`) carrying poster, title, description, Play (focused on arrival), and a favorite heart. `ContentGrid`'s callback is `onActivate`, not `onPlay`. The long-press menu keeps a direct Play as the shortcut. A movie`s description comes from `get_vod_info`, fetched lazily and cached — this panel sends no `plot` on `get_vod_streams` (verified over a forced re-sync of all 48,780 rows).

**The rail leads with three synthetic folders** the panel does not publish — `RECENTLY ADDED` (100), `CONTINUE WATCHING` (50), and `FAVOURITES` in that order, above the panel`s categories. They are `CategoryEntity` rows with `__`-prefixed ids; see `docs/architecture.md`.

**Room DB is v5, on real migrations.** Never restore `fallbackToDestructiveMigration` — it drops `favourites` and `resume_positions`, which are the only user data the app holds and the two tables those folders are built from. v5 adds `rating` to `vod_streams` and `series`; nothing back-fills it, so ratings appear per category as each one re-syncs.

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

**Phase 5 (ACTIVE):** Hardening — RTL, empty/loading/error states, `RefreshWorker`, physical TV

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

## §6 Claude/Codex Division of Labor (Herdr-coordinated)

**Binding.** This workflow makes the process self-sustaining instead of something the user has to re-explain every session.

### Roles (fixed, do not renegotiate per-task)

**Claude plans and executes.**
- Writes the plan and acceptance criteria for any open item before touching code
- Does the implementation
- Owns closing the item out

**Codex reviews and tests, never self-marks its own work done.**
- Adversarially reviews Claude's plan for edge cases before execution
- Codex wrote the original desktop LibVLC/native code, so its own past blind spots there are exactly what to check hardest
- After the fix, tests against the plan's acceptance criteria with observable evidence — a screenshot, dumped UI string, log line — never "build is green" or "no exception thrown" alone
- (`D-Desktop-10` shipped "fixed" twice on that weaker bar; this rule exists because of that)

**The user approves.**
- Neither agent pushes, deploys, or opens a PR without the user seeing the diff first
- Neither agent builds/installs/deploys without asking first — standing rule independent of this workflow

### The loop, per item

1. Claude writes the plan + acceptance criteria (what to observe, not just what to build)
2. Claude hands the plan to Codex (`herdr agent prompt <name> "..." --wait`) for edge-case review
3. Claude revises the plan if Codex found a real gap, then executes — asking the user before any build/deploy step
4. Codex tests the result against acceptance criteria with evidence, reports pass/fail
5. Claude closes the loop: check the item off `TODO.md`, add its closure note to `docs/decisions.md`'s "Archived todo-tree closures" (matching existing format), report to user what changed and what review/test caught

### Context measurement (part of the PoC, not optional once running)

Record per item closed: context/tokens spent on each side, and **how much of Codex's raw tool output stayed in its own Herdr pane and never had to be read into Claude's context.** Report this alongside closure note so process value is a number, not a vibe.

### Codex context harness (added 2026-09-14, post QA-10 PoC)

QA-10 closed with Claude spending a small fraction of Codex's context for equivalent work. The split worked as intended, none of Codex's raw output crossed into Claude's context. But 57% for a two-screen visual check is too much fuel per item.

**Mechanical overspend causes (rules to fix them):**
- **`uiautomator dump` was piped to `/dev/tty`** (~300-390 lines XML per check). **Rule: dump to file only** (`uiautomator dump path.xml`), then extract just the `text="..."` values that matter via `Select-String`/`grep -o 'text="[^"]*"'`. Never print a full hierarchy to console.
- **Blind trial-and-error navigation** (wrong category tap, filter-text guess) cost a full dump-and-inspect per failed attempt. **Rule: plan the exact tap/filter sequence against a known layout before executing**, not probing and re-dumping after each guess. If layout is unknown, one exploratory dump, extract coordinates, then execute the rest blind.
- **Full-file reads used `Skip N -First M` blocks** (35-90 lines) even when plan already named exact line numbers. **Rule: when plan cites file:line, read a tight window (±10-15 lines), not an arbitrary block** — prefer `rg`/grep for a symbol over paging.
- **Screenshots are most expensive** (vision tokens per image) and one was viewed twice. **Rule: capture once, verify it's settled before viewing (short sleep after action), view each evidence image at most once.**

**Standing budget:** State a rough context ceiling in test-handoff prompt itself (e.g. "target finishing under ~25% of your context"). If trending over, stop expanding evidence and report with what exists — same as any acceptance-criteria shortfall.

**Reporting requirement:** Codex's final report must state its own context-used percentage (copy from status line into written report, don't leave implicit) so number is comparable run-to-run.

**Auto-clear at ~35%** (added 2026-09-15): Once Codex's status shows ~35% used, Claude resets it with `herdr agent send-keys <name> /clear` (Codex's own in-CLI context-reset command — not exiting, since exit loses the `codex resume` handle) before handing it the next task. Do this proactively between tasks, not waiting for Codex to balloon.

### Escalation

If Codex's review surfaces a scope/architecture disagreement (not a straightforward correction), **stop and ask the user** rather than Claude unilaterally overruling it. If Codex's test fails, the loop returns to Claude, not back to Codex to patch directly — Codex is the more error-prone party on the exact subsystem (native/FFI desktop code) where the "fixed twice, still broken" pattern happened.

---

## §7 Lessons Learned

### 2026-09-16 (Session: Context Restructuring)

**Decision:** Consolidated 17 scattered detail files (8 `claude-*.md` + 9 `product-*.md`) into this unified `PROJECT-BIBLE.md`, following the EMR project pattern.

**Why:** The old structure had 3 conflicting entry points, undefined "relevant detail files" rule, invisible shared files, and ~170 lines of mandatory loading per session with guesswork.

**Result:** Single unified bible (§0-7), crystal-clear session protocol, ~209 lines upfront but zero ambiguity afterward. Eliminated need to maintain 17 separate files.

### 2026-09-14 (D-Desktop-10-REGR, Herdr workflow PoC)

**Decision:** Adopted Herdr-coordinated Claude/Codex workflow with visible side-by-side progress, plan review before execution, test evidence requirements.

**Why:** Prior "fixed twice" incident on D-Desktop-10 showed that build-success ≠ feature-works; need structured plan+review+evidence.

**Result:** QA-10 closed cleanly with measurable context savings (Codex output stayed in its own pane). Context harness added to prevent overspend on larger items.

---

## §8 Related Documentation

For implementation details, architecture, and decisions:
- **`docs/architecture.md`** — system shape, caching logic, focus restoration
- **`docs/decisions.md`** — rationale for build choices, archived closure notes
- **`docs/ui-scope.md`** — layout system, type scale, card dimensions, state table
- **`docs/design/comps.html`** — approved visual reference (1920×1080, real metrics)
- **`docs/xtream-api-reference.md`** — URL patterns, endpoint contracts
- **`docs/design/img/CREDITS.md`** — asset sourcing and licensing

For product context (optional, if separate file created):
- **`PRODUCT-BIBLE.md`** (if created) — users, positioning, capabilities, product principles
