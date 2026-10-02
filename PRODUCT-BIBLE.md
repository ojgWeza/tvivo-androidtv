# Tvivo Product Bible

> **Definitive product specification.** This file consolidates product-level decisions, constraints, capabilities, and user commitments. Paired with `PROJECT-BIBLE.md` (which covers build, testing, and implementation mechanics). Updated 2026-09-16 during documentation restructuring; platforms section revised 2026-10-02 (WinUI is a product platform).

---

## Platform & Positioning

**Platforms:** Tvivo is offered on two platforms, one product:

- **Android TV** (Android OS on televisions) — the original, ten-foot, D-pad-only platform. Native Kotlin + Jetpack Compose, not Flutter (`app/`).
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

---

## Operating Context

### Environment (Android TV)

- **Ten-foot viewing, D-pad only.** No touchscreen (`hw.screen=no-touch` on the target class). Mouse and tap input do not exist. Every affordance is reached by arrow keys, and focus is the only cursor.
- **Text entry is the worst part of the platform.** A realistic credential set is ~44 characters, which on a D-pad grid keyboard is 200+ directional presses. The TV IME is a bottom-anchored panel covering roughly the lower half of a 1080p screen, and while it is up it owns every arrow press.
- **The login screen is seen regularly**, not once: re-auth, subscription expiry, and switching accounts all return to it. Typing ergonomics and error recovery matter as much as appearance.
- **Design space is 960 × 540 dp** (1920×1080 at density 320).

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

- **Name:** Tvivo. No wordmark or icon set exists yet — an open decision, not an absence to invent around.
- **UI chrome is English, left-to-right**, while catalog content is heavily Arabic. This is confirmed, not provisional: labels, buttons, and error copy stay English; only titles carry mixed direction.
- **Full RTL mirroring of the chrome is explicitly not wanted.** Mixed-direction titles are already solved at the data layer with a `name_display` column and a derived quality badge. Any new title rendering must not regress that.

---

## Product Principles

1. **The person using it did not build it.** Legibility and recovery beat density and cleverness.
2. **Everything is reachable by keyboard / D-pad.** On Android TV focus is the only cursor, so if it cannot be reached with arrow keys it does not exist; on Windows mouse is additive, never required. Nothing important may sit under the IME.
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

## Out of Scope (Deferred or Declined)

Rationale documented in `PROJECT-BIBLE.md` §5:
- Idle dim / screensaver
- Voice search
- Second TV target (LG webOS)
- Favorite folders (separate persistence item with own plan)
- Home Settings screen (unscoped, no priority)

---

## Session Protocol

This file is loaded immediately after `PROJECT-BIBLE.md` §0 per the session protocol. Update it when:
- Product vision changes (new target platforms, new content types, new user model; platform scope is in "Platform & Positioning")
- User research surfaces new constraints
- Learned lessons reveal prior wrong assumptions

All other implementation details live in `PROJECT-BIBLE.md` (build, test, emulator, focus handling, etc.) or `docs/` (architecture, API reference, design decisions).
