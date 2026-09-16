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

