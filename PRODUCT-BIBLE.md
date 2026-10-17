# Tvivo Product Bible

> **Definitive product specification.** This file consolidates product-level decisions, constraints, capabilities, and user commitments. Paired with `PROJECT-BIBLE.md` (which covers build, testing, and implementation mechanics). Updated 2026-09-16 during documentation restructuring; platforms section revised 2026-10-02 (WinUI is a product platform); Android scope widened to phone/tablet, business model, Arabic UI and Kids profile added 2026-10-07.

---

## Platform & Positioning

**Platforms:** Tvivo is offered on two platforms, one product:

- **Android TV** (Android OS on televisions) — the original, ten-foot, D-pad-only platform. Native Kotlin + Jetpack Compose, not Flutter (`app/`).
- **Android phone and tablet** (added 2026-10-07) — the same app and codebase, touch-first and adaptive (bottom bar on phone, navigation rail on tablet), still keyboard/D-pad navigable. Chosen release target for all Android form factors is a **public Google Play release**. Plan: `docs/android-production-plan.md`.
- **Windows desktop (WinUI 3)** — a full second platform, not a spike or a demo: keyboard and mouse, a window or borderless fullscreen, a "cinema mode" for playback, two playback engines (native and LibVLC) chosen in Settings (`windows-spike/`, shipped as `Tvivo.App`). A Compose Desktop client (`desktop/`) also exists as an experiment.

Both platforms share the same product behaviour, data rules and commitments below (provider-agnostic, cached catalog, English LTR chrome, no invented data). Where a section says "Android TV", "ten-foot" or "D-pad", it describes that platform's input model; the WinUI app must be usable with keyboard and mouse and must not depend on either alone. Feature parity is a goal, not a guarantee: a feature may land on one platform first, and `TODO.md` records which.

**Product Purpose:** Browse and play Live TV, Movies and Series from an Xtream Codes IPTV panel on an Android TV device or a Windows PC. Success is that someone who did not build it can sit down, find something, and watch it without asking for help.

**Positioning:** Provider-agnostic by construction. The server and port are entered at runtime and read back from `server_info`, so the same build works against any Xtream Codes IPTV panel. No hostname, port, provider name, or account detail is ever hardcoded — the repository is public.

---

## Users

**Primary: the owner and their household.** One technical person set the app up; everyone else picks up the remote without knowing how it works and without being able to fix it. Design for the person who did not build it.

Practical consequences, all confirmed:

- Labels must read at a glance from across a room. No jargon the builder understands and a family member does not.
- Destructive actions (`Sign out`, which wipes a non-exportable Tink keyset and cannot be undone) must be hard to hit by accident on a D-pad.
- Account switching is a real workflow, not an edge case.
- Households include children and seniors. A parent can curate a **Kids profile** (Plus): an allowlist of picked titles, series and categories grouped in folders, with no search, settings or sign-out, left only with the parent PIN. The app never claims content is "safe for kids" and is not marketed as a children's app.
- Setup must work for people who cannot type credentials: manual entry always stays available, and a phone can add a source to a TV by scanning a setup QR and entering a 4-digit code shown on the TV.

---

## Operating Context

### Environment (Android TV)

- **Ten-foot viewing, D-pad only.** No touchscreen (`hw.screen=no-touch` on the target class). Mouse and tap input do not exist. Every affordance is reached by arrow keys, and focus is the only cursor.
- **Text entry is the worst part of the platform.** A realistic credential set is ~44 characters, which on a D-pad grid keyboard is 200+ directional presses. The TV IME is a bottom-anchored panel covering roughly the lower half of a 1080p screen, and while it is up it owns every arrow press.
- **The login screen is seen regularly**, not once: re-auth, subscription expiry, and switching accounts all return to it. Typing ergonomics and error recovery matter as much as appearance.
- **Design space is 960 × 540 dp** (1920×1080 at density 320).
- **Manual credential entry always stays available.** The phone-to-TV pairing (TV shows a 4-digit code, phone scans the setup QR and enters it) is an optional convenience, not a replacement. Pairing happens over the same Wi-Fi with no server holding credentials.

### Environment (Android phone and tablet)

- **Touch-first, handheld or on a stand.** Normal reading distance, portrait and landscape (the player is landscape). Touch targets are at least 48 dp. Window size classes select layout; D-pad/keyboard navigation must keep working.
- **Text entry is cheap but typing-once still applies;** a setup QR can be scanned with the camera, imported from a photo or screenshot, or pasted as text.
- **Mobile data is a real cost:** warn before streaming on a metered network, offer Wi-Fi-only catalog sync.
- **Same panel rules:** `max_connections` is read, never assumed, and the app holds at most one active stream.

### Environment (Windows / WinUI)

- **Desktop viewing, keyboard + mouse.** Normal reading distance, resizable window plus borderless fullscreen; every action must still be reachable from the keyboard.
- **Text entry is cheap**, so search and sort live in the top bar, but the typing-once rule still applies.
- **The window never leaves the system in a worse state**: entering and leaving fullscreen must restore the taskbar, cursor and window placement.
- **Same panel rules as Android TV:** `max_connections` is read from the account and never assumed; at most one active stream is held by the app at any time.

### Streaming & Accounts

- **Concurrent streams are an account property, not an app rule.** The panel reports `max_connections` per account in `server_info`; it happens to be `1` on the development account, but other panels differ. The UI must always *report the value it read* and never assert a limit as a product fact.
- **Streams are `.mkv`, `.mp4`, `.ts` direct files** (not HLS manifests) for movie/series/live — progressive download, not adaptive streaming.

---

## Capabilities & Constraints

### At-Scale Catalog

Measured against the live development panel:
- **48,761 movies** (VOD)
- **6,425 live channels**
- **13,264 series shows**

Full engineering constraint list (stack, Media3, Room/TTL, card sizes, credentials, cleartext HTTP) lives in `PROJECT-BIBLE.md` §2. Product-visible implications:
- Card sizes are fixed inputs to the image pipeline (220×330 px posters, 220×124 px channel cards). Changing either requires re-encoding the entire cache.
- Room database is v5 on real migrations — never use `fallbackToDestructiveMigration` (see `PROJECT-BIBLE.md` §1).
- Catalog refresh is non-blocking; cached data remains browsable while refresh happens behind it.

---

## Brand Commitments

- **Name:** **Under review (postponed 2026-10-07).** Tvivo is the working name only: `tvivo.es` is an unrelated IPTV subscription seller, which risks brand confusion and a Play-review association, so a rename is being considered. Wordmark, launcher icon, adaptive icon, TV banner, store name, domain and `applicationId` all wait for the final name. Until then the letter-spaced wordmark style from the WinUI shell is the design reference, not a final brand.
- **UI languages: English and Arabic** (reopened 2026-10-07; both free). English stays left-to-right. When Arabic is selected the whole layout mirrors (RTL). Layout code uses start/end, never left/right, and D-pad focus order mirrors with it. This replaces the earlier "English LTR only, no mirroring" commitment. WinUI is English-only for now, so Android leads on languages.
- **Mixed-direction titles** stay solved at the data layer with a `name_display` column and a derived quality badge. Any new title rendering must not regress that, in either UI language.
- **Neutral player.** No provider names, hosts or logos in the app, store listing, screenshots or marketing; no provider portal, provider accounts or white-label builds for unlicensed providers. The setup-QR name is plain user-visible text, treated exactly like a typed source name.

---

## Product Principles

1. **The person using it did not build it.** Legibility and recovery beat density and cleverness.
2. **Everything is reachable by keyboard / D-pad.** On Android TV focus is the only cursor, so if it cannot be reached with arrow keys it does not exist; on Windows mouse is additive, never required; on Android phone and tablet touch is primary but D-pad/keyboard navigation must still work. Nothing important may sit under the IME.
3. **Typing is expensive; never spend it twice.** Preserve what was typed, make errors correctable in place, and never clear a field as a side effect.
4. **Cached data is the product.** The catalog must stay browsable while anything refreshes behind it, and a failed refresh must never empty a screen.
5. **Irreversible actions must be hard to reach by accident**, because the one that matters here cannot be undone.

---

## Accessibility & Inclusion

- **Ten-foot legibility is the governing constraint:** minimum type sizes and contrast are set by viewing distance (ten feet from a TV screen), not by desktop convention.
- **Colour and contrast are measured against WCAG.** Dark teal surface ramp with a Claude-orange accent; see `ui/theme/Palette.kt`.
- **Focus state must be unmistakable at distance** — it is the only indication of where input will land.
- No product-specific assistive-technology requirement has been established yet.

---

## Evidence on Hand

### What We Know

- A live panel with real credentials on the emulator, and real catalog rows (counts above).
- Screenshots in-session are true 1920×1080 frames.
- The app authenticates, browses all three content types by category, plays movies, and shows a season/episode picker for series.

### What We Don't Know (Deferred Constraints)

- **No user research, no analytics, no testimonials, no benchmarks.** Do not fabricate any.
- **Live TV and episode playback have never been executed** — `max_connections` is 1, so no automated test may open a stream. Any claim about how live playback behaves is untested.

---

## Business Model (decided 2026-10-07)

Full detail in `docs/android-production-plan.md` §12. In short:

- **No ads**, ever (children may be watching).
- **Free core, optional Tvivo Plus** sold through Google Play Billing, plus an optional supporter payment. Prices are hypotheses to test, set per country (Egypt much lower than Gulf/Europe/US).
- **Rule:** if a first-time user would feel cheated by its absence, it is free. Free always: all playback, Continue Watching (in the app and on the TV home screen), favourites, resume, basic audio/subtitle selection, setup QR and phone-to-TV pairing, at least two saved sources, parental PIN and adult-category hiding, English and Arabic.
- **Plus v1:** profiles (including the parent-curated Kids profile), unlimited sources, backup/restore, themes, advanced subtitles, home customization and custom favourite lists. Existing profiles keep working if Plus lapses; only creating and editing locks.
- **Support prompt:** one polite message after about 30 days, dismissible forever, never during playback.
- **Licensed B2B** (hotels, ISPs, channel owners, mostly outside Egypt) may receive a signed Plus entitlement inside the setup QR, issued only by the owner. Never issued to unlicensed providers.
- **Setup QR:** open, documented format (name, server with optional port, user, password); a confirm screen always precedes saving; the QR is a secret and is never logged.

## Out of Scope (Deferred or Declined)

Rationale documented in `PROJECT-BIBLE.md` §5:
- Idle dim / screensaver / idle featured overlay (the overlay was also removed from WinUI)
- Voice search
- Second TV target (LG webOS)
- Provider white-label builds, provider portals and ads
- Casting/DLNA
- Cloud sync and EPG guide until a server or provider support exists

Resolved by the 2026-10-07 scope change: favourite folders are now Plus "custom favourite lists"; a Settings screen (Library / Player / Account) is in scope.

---

## Session Protocol

This file is loaded immediately after `PROJECT-BIBLE.md` §0 per the session protocol. Update it when:
- Product vision changes (new target platforms, new content types, new user model; platform scope is in "Platform & Positioning")
- User research surfaces new constraints
- Learned lessons reveal prior wrong assumptions

All other implementation details live in `PROJECT-BIBLE.md` (build, test, emulator, focus handling, etc.) or `docs/` (architecture, API reference, design decisions).
