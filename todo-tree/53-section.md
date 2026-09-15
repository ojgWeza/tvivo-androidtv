## N-13 — RAM usage profiling and heap pressure
**Cost: medium. Empirical measurement on the physical TV is essential.**

The app holds the full in-memory `PagingData` for each category's grid, plus the
Compose UI tree for the rail, detail pages, and player. With 9,750-row categories
and simultaneous image-loading on a TV with 1–2 GB heap, memory pressure during
scroll/filter operations and background sync is unverified.

Profile on the physical TV during Phase 5:
- Measure heap size at launch, after catalog sync completes, and during active
  browsing (rail changes, grid scroll, category filter, detail page open, player).
- Capture GC frequency and pause times, especially during scroll and while a
  background sync writes to the database.
- Check whether placeholders or stable keys cause memory bloat (they should not,
  but confirm on real hardware).
- Profile image loader concurrency — how many images load simultaneously, and
  whether the downsampling/caching pipeline is a bottleneck or a pressure point.

If GC pauses or OOM behavior are observed, decide whether to: cap the in-memory
PagingData window per category, implement LRU eviction for off-screen image
caches, or reduce background sync concurrency when the heap is above a threshold.

**Current state:** no profiling done; the app is "responsive on the emulator" but
heap constraints are unknown on real hardware.

---

## N-14 — Play-distributed 30-day demo entitlement
**Cost: medium-to-large. Requires an app-owned backend before client work starts.**

Ship a 30-day **per-account-and-device** demo period that begins when a user
first completes setup. This is not a build-date expiry and must not trust the
Android device clock: changing the TV date/time must never extend access.

### Backend (source of truth)

Host a minimal app-owned HTTPS service (recommended: Cloudflare Worker plus D1;
Firebase Functions/Firestore, Supabase Edge/Postgres, or an equivalent managed
service are valid alternatives). The service owns both the UTC clock and a
durable trial ledger. It exposes an authenticated `POST /v1/demo-access` that
returns the original `expiresAt` on every subsequent request.

- Define a canonical, versioned trial key from the entered account identity plus
  the app-scoped Android ID. Derive the stored key server-side with a secret
  (HMAC); do not persist raw server address, username, Android ID, or any
  password.
- The first accepted setup creates `{ trialKey, startedAtUtc, expiresAtUtc }`,
  where `expiresAtUtc = startedAtUtc + 30 days`. Every later request for that key
  returns the same record, so clear-data and normal reinstall do not restart the
  period.
- Never send the IPTV password to this service. Use TLS, normal certificate
  validation, short request/response logs with no identifiers, rate limiting,
  abuse monitoring, retention/deletion rules, and a privacy-policy disclosure.
- Decide the reset policy before launch: an Android factory reset or a different
  device produces a different app-scoped Android ID. Account-only keys prevent
  that bypass but make the trial shared across all devices for that account.
- Add server-side Play Integrity verification before treating a request as a
  genuine Play-distributed app request; verify tokens only on the backend.
  Treat it as abuse reduction, not a permanent device identity.

### APK behavior

- Add a `DemoAccessRepository` and a pure, unit-tested access-state model:
  `Checking`, `Active(expiresAt)`, `Expired`, and `Unavailable`.
- After valid IPTV setup, require a successful entitlement response before
  entering Home. On cold start, foreground/resume, and immediately before every
  player launch, revalidate with the backend UTC result. Do not fall back to
  `System.currentTimeMillis()` or a public time API.
- If verification is unavailable, block catalog and playback with a focused,
  remote-friendly retry screen: “Connect to the internet to verify this demo.”
  If expired, show a non-dismissable expiry screen with Exit and support/current
  build guidance. Do not overload provider `AccountExpired`: provider expiry
  and demo expiry are separate truths.
- Expiry must close/return from an already-running player at the next lifecycle
  revalidation. Do not wipe encrypted credentials, Room data, favourites, or
  resume positions in either blocked state.
- Preserve the existing non-destructive account-switch behavior. A different
  account/device combination is a new entitlement lookup, never an automatic
  deletion of the current account.

### Play launch gate

- Publish an Android App Bundle with Play App Signing; test first through
  internal and closed tracks. Supply persistent, reusable reviewer access and
  clear English setup instructions for the restricted IPTV flow.
- Complete a truthful Data safety form and an in-app plus Play Console privacy
  policy. Explicitly cover encrypted IPTV credentials, the demo service’s
  derived trial identifier, retention, and no password sharing with the demo
  service.
- Complete App content declarations: target audience/content rating, ads (if
  any), app access, and any required sensitive-data disclosures. Confirm regional
  content/licensing rights independently before release; a provider-agnostic
  client does not grant distribution rights to any supplied content.
- Keep the Android TV target-SDK requirement current (API 34 satisfies the
  current TV requirement as of 2026-09-13); re-check before submission. Verify
  the final Play-delivered artifact—not only a locally sideloaded APK—on a real
  Android TV with a remote.
- Add integration tests for: first setup starts exactly one 30-day record;
  reinstallation/clear-data lookup returns the original expiry; server expiry
  blocks all routes and playback; unavailable service blocks access; and a
  provider-account expiration remains separately represented. No automated test
  may open a stream.

**Decision required before implementation:** choose whether the entitlement
identity is account+device (recommended; one trial per device) or account-only
(stronger against factory-reset/new-device retries, but shared across devices).

---

## N-15 — Windows desktop playback POC (POC-D1)
**Cost: medium spike. This is not approval to build a feature-parity desktop client.**

Prove the chosen Windows x64 playback stack can host LibVLC inside a Kotlin
Compose Desktop window and satisfy Tvivo's local-media playback contract. The
POC has no provider sign-in, catalog, artwork cache, favourites, resume state,
demo entitlement, account UI, diagnostics export, installer, or real-stream
support. It must remain provider-agnostic: no panel host, port, provider name,
credentials, or content data may be embedded in source, test fixtures, release
artifacts, or telemetry.

A full desktop client remains a separate, major decision after POC-D1 passes
and its decision record is reviewed. If approved later, it must enforce the
same server-authoritative 30-day trial as Android; it may never fall back to
the local system clock or restart a trial following app-data deletion or
reinstallation for the same account-and-desktop identity.

This is **not** a direct APK port. Android-specific components have no desktop
runtime equivalent (`Activity`, Compose TV focus, Media3 ExoPlayer, Room 2.6,
Tink Android Keystore integration, WorkManager, Android diagnostics and APK
delivery). Reuse protocol, parsing, domain rules, URL construction, error
taxonomy, and entitlement contracts only after extracting them from Android
dependencies into tested shared modules.

### Approved platform and tool choices

- **Scope:** Windows x64 first. macOS and Linux are follow-up ports, not
  compatibility claims for v1. Design layouts for keyboard, mouse, window
  resizing, and basic accessibility; retain a usable D-pad/focus path where a
  desktop user supplies one.
- **UI/runtime:** Kotlin Compose Multiplatform/Desktop. It preserves Kotlin and
  the Compose mental model without claiming the Android TV UI is desktop-ready.
- **Shared code:** a Gradle `shared-core/` Kotlin module, consumed by both
  `app/` and `desktop/`. It begins with pure protocol/domain code and grows only
  when a component has platform-independent tests.
- **Media:** direct, dynamically linked **LibVLC** through free JNA bindings.
  Do not use Media3 on desktop and do not adopt GPL `vlcj` or a commercial
  wrapper. Review and fulfil LibVLC LGPL distribution obligations before bundling
  native binaries in an installer.
- **Persistence:** evaluate Room KMP/Room 3 plus bundled SQLite in the desktop
  spike; retain Android Room 2 until an evidence-backed incremental migration is
  separately approved. Do not duplicate schemas casually.
- **Secrets/storage:** Windows DPAPI-backed credential storage. No plaintext
  configuration file and no secret, backend credential, panel address, or user
  password committed to this public repository.
- **Distribution:** signed Windows installer/MSIX with an update/rollback plan.
  The first proof of concept may discover a locally installed LibVLC only; it
  must not commit or redistribute native VLC binaries until their packaging and
  licence notices are reviewed.

### POC-D1 result — 2026-09-13

**Partially passed; do not start desktop feature-parity work yet.** The Windows
x64 Compose Desktop window loaded a locally installed LibVLC 3.0.23 runtime,
embedded its native surface, and played one local synthetic MP4 fixture. The
spike also exposed three design/implementation constraints: a `SwingPanel`
surface must wait until its AWT canvas is displayable before requesting an
HWND; the Gradle run task must forward the configured LibVLC directory to the
application JVM; and a heavyweight native surface can obscure Compose controls
unless they are deliberately laid out outside it.

Still unverified: local MKV and TS playback; duration/seek behavior; media-end
and playback-error callbacks; audio/subtitle track discovery; close/reopen
teardown without an orphaned native process; and the final desktop viewing
experience. The observed MP4 showed an oversized black/matte frame around the
video, which is an explicit design issue, not POC polish to paper over.

### Next desktop session — design run before implementation

Before implementing catalog, account, storage, or any Android-code extraction,
run a dedicated desktop design session. It must define the windowed and
full-screen player viewport, media-aspect behavior, control placement and
autohide policy, pointer/keyboard/accessibility behavior, resizing rules, and
the boundary between the heavyweight native surface and Compose UI. Record the
approved design in the project docs, then finish the outstanding POC-D1 test
matrix. Only a fully passed POC-D1 plus the reviewed design authorizes desktop
feature work.

### Original POC-D1 implementation plan

**Objective:** prove that the chosen free stack can embed LibVLC in a Compose
Desktop window and supports the media contract Tvivo needs. This is a technical
spike, not a usable desktop app and not a reason to migrate Android code yet.

1. Create the `desktop/` Gradle subproject and a minimal Compose Desktop window;
   wire its run task into the root build without changing Android release
   behavior. Create an initially empty `shared-core/` module only if the build
   layout requires it; do not move production code in this POC.
2. Add a narrow JNA interface for only the LibVLC lifecycle and playback calls
   needed by the spike. Load a developer-installed Windows x64 LibVLC from an
   explicit, documented local path; fail with a clear diagnostic instead of a
   crash when it is absent or the architecture is wrong.
3. Embed the native LibVLC video surface in the Compose Desktop window. Provide
   Play, Pause, Stop, Seek, and Close controls plus visible player-state/error
   text. Marshal LibVLC callbacks onto the Compose state owner; never call
   LibVLC again from its native callback thread.
4. Use only local, synthetic media fixtures for the POC: one `.mp4`, one `.mkv`,
   and one `.ts`. Do not connect to an IPTV panel, hardcode a provider URL, or
   open any real stream. Keep fixtures out of Git if licensing/size is unclear.
5. Exercise explicit teardown: stop playback, dispose the native surface/player,
   close the window, reopen it, and confirm a second playback session can begin.
   Capture only redacted diagnostics: LibVLC discovery result, file extension,
   state transition, error class, and teardown completion.
6. Record the result in `docs/decisions.md`: selected LibVLC loading/rendering
   strategy, exact licence-notice obligations, supported test matrix, known
   limitations, and whether POC-D1 passed. If rendering, seek, track discovery,
   or clean teardown fails, stop there and compare a Windows Media Foundation
   adapter; do not start catalog/UI parity work on an unproven player.

**POC-D1 exit criteria:** on a Windows x64 machine with LibVLC installed, the
window reliably plays all three local fixture containers, exposes duration and
seek for seekable files, observes media end/error, and closes/reopens without a
native crash or orphaned playback process. It produces no release installer,
no network traffic, no credentials, no provider data, and no real-stream test.

### Architecture and security work

- Split Android-independent modules for Xtream DTOs/parsers, credentials model
  (not storage), account identity, repositories' business rules, catalog-sync
  state machine, error mapping, stream URL builder, diagnostics schema, and
  `DemoAccessRepository` API contract. Keep Android behavior unchanged behind
  adapter interfaces while extracting them.
- Implement desktop adapters deliberately: OS-backed encrypted credential
  storage (not plaintext files); a desktop SQLite schema/migration strategy;
  cache directory/size limits; scheduled or foreground refresh equivalent to
  WorkManager; safe diagnostics persistence/export; and app lifecycle hooks.
  Preserve migrations and user data during desktop app upgrades; never use a
  destructive migration as a shortcut.
- Reuse the app-owned demo-entitlement backend. Its identity model must include
  a desktop installation/device component without leaking hardware identifiers
  or account details; document cross-device trial policy explicitly.
- Use HTTPS with normal certificate validation for the entitlement service. Keep
  the existing user-entered cleartext/HTTPS provider endpoint behavior isolated
  to the provider client; do not weaken certificate validation.

### Full implementation plan after POC-D1 passes

1. **Foundation:** establish application directories and Windows DPAPI storage;
   extract tested shared-core DTO/parser/error/URL/entitlement interfaces;
   configure desktop diagnostics; add provider sign-in and the backend-backed
   30-day demo gate. Confirm no sensitive data reaches logs.
2. **Catalog:** select and implement the desktop SQLite schema/migrations;
   port full/category sync; cache/downsample artwork; implement local
   browse/search/filter and offline catalog behavior; add manual and scheduled
   refresh with an explicit desktop lifecycle policy.
3. **User state:** port favourites, resume positions, virtual folders, pre-run
   item/series detail, Account, Subscription, and Diagnostics. Preserve state
   across normal desktop upgrades without destructive migration.
4. **Playback:** replace POC controls with a desktop player contract supporting
   movie, live, and episode URLs; resume/seek, tracks, error mapping, full
   screen/windowed presentation, Back/close behavior, and proven connection
   teardown. No automated test may open a real provider stream.
5. **Parity and quality:** implement desktop-native responsive layouts,
   keyboard/mouse/accessibility contracts, error/empty/loading/network-loss and
   demo-expiry states; test every Android feature against a mock provider and
   synthetic fixtures.
6. **Release:** package dynamically linked LibVLC and licence notices only after
   legal review; sign the installer/MSIX; test clean install, upgrade, rollback,
   uninstall/data-retention behavior, crash reporting, updates, and physical
   Windows-machine playback before public distribution.

Parity means the same functional contract, not pixel-identical Android TV UI:
desktop layout must be designed for window resizing, pointer input, keyboard
shortcuts, and accessibility while retaining a usable remote/focus path where
that is a supported input. Validate every slice against a mock provider and
synthetic media fixtures; never place provider credentials or streams in tests.

**Gate:** POC-D1 must pass and its decision record must be reviewed before any
catalog, account, or Android-code migration starts.

---

## N-16 — Android player viewport and chrome quality
**Cost: small design/QA batch. Do not change playback behavior until visual evidence exists.**

Carry the POC lesson back to Android TV: the player must not leave a permanent
oversized black/matte frame around ordinary video simply because the surface and
content aspect ratios differ. Run a focused Android TV visual design/QA pass
before changing the player. Define the intended fit/crop/letterbox policy,
whether matte is ever visible outside transitions, control placement and
autohide, remote focus behavior, and Back/exit behavior. Validate movie, live,
and episode surfaces manually on the physical TV; no automated test may open a
stream.

---

# Part 3 — Remaining build phases
