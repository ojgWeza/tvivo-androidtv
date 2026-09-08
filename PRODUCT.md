# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

**Primary: the owner and their household.** One technical person set the app up;
everyone else picks up the remote without knowing how it works and without being
able to fix it. Design for the person who did not build it.

Practical consequences, all confirmed:

- Labels must read at a glance from across a room. No jargon the builder
  understands and a family member does not.
- Destructive actions (`Sign out`, which wipes a non-exportable Tink keyset and
  cannot be undone) must be hard to hit by accident on a D-pad.
- Account switching is a real workflow, not an edge case.

## Product Purpose

Browse and play Live TV, Movies and Series from an Xtream Codes IPTV panel on an
Android TV device. Success is that someone who did not build it can sit down,
find something, and watch it without asking for help.

## Positioning

Provider-agnostic by construction. The server and port are entered at runtime and
read back from `server_info`, so the same build works against any Xtream Codes
panel. No hostname, port, provider name or account detail is ever hardcoded — the
repository is public.

## Operating Context

- **Ten-foot viewing, D-pad only.** No touchscreen (`hw.screen=no-touch` on the
  target class). Mouse and tap input do not exist. Every affordance is reached by
  arrow keys, and focus is the only cursor.
- **Text entry is the worst part of the platform.** A realistic credential set is
  ~44 characters, which on a D-pad grid keyboard is 200+ directional presses. The
  TV IME is a bottom-anchored panel covering roughly the lower half of a 1080p
  screen, and while it is up it owns every arrow press.
- **The login screen is seen regularly**, not once: re-auth, subscription expiry,
  and switching accounts all return to it. Typing ergonomics and error recovery
  matter as much as appearance.
- **Design space is 960 x 540 dp** (1920x1080 at density 320).
- **Concurrent streams are an account property, not an app rule.** The panel
  reports `max_connections` per account in `server_info`; it happens to be `1` on
  the account used for development, but other accounts allow more. The UI must
  always *report the value it read* and never assert a limit as a product fact.

## Capabilities and Constraints

- Native Kotlin + Compose with `androidx.tv.material3`. Not Flutter — chosen for
  D-pad focus handling and leanback support.
- Media3 ExoPlayer. Streams are direct `.mkv`/`.mp4`/`.ts` files, not HLS
  manifests: progressive download, not adaptive streaming.
- Room cache with a 24 h TTL tracked **per category**, plus a full-catalog sync
  tier on top of it that must never block the UI.
- **Card sizes are image-pipeline inputs, not styling.** `POSTER` 220x330 px and
  `CHANNEL` 220x124 px. Posters are downsampled to card size before caching, so
  changing either means re-encoding the whole cache.
- Credentials are encrypted at rest (DataStore + Tink). The keyset is **not
  exportable**: clearing app data destroys them permanently.
- Cleartext HTTP must stay permitted via `network_security_config.xml`;
  certificate validation is never disabled to work around a bad cert.
- Catalog scale, measured against the live panel: 48,761 movies, 6,425 channels,
  13,264 series shows.

## Brand Commitments

- Name: **Tvivo**. No wordmark or icon set exists yet — an open decision, not an
  absence to invent around.
- **UI chrome is English, left-to-right**, while catalog content is heavily
  Arabic. This is confirmed, not provisional: labels, buttons and error copy stay
  English; only titles carry mixed direction. Full RTL mirroring of the chrome is
  explicitly **not** wanted.
- Mixed-direction titles are a real rendering problem, already solved at the data
  layer with a `name_display` column and a derived quality badge. Any new title
  rendering must not regress that.
- Technical content in the repository is always English.

## Evidence on Hand

- A live panel with real credentials on the emulator, and real catalog rows
  (counts above). Screenshots in-session are true 1920x1080 frames.
- No user research, no analytics, no testimonials, no benchmarks. Do not
  fabricate any.
- Live TV and episode playback have **never been executed** — `max_connections`
  is 1, so no automated test may open a stream. Any claim about how live playback
  behaves is untested.

## Product Principles

1. **The person holding the remote did not build this.** Legibility and recovery
   beat density and cleverness.
2. **Focus is the cursor.** If it cannot be reached with arrow keys, it does not
   exist. Nothing important may sit under the IME.
3. **Typing is expensive; never spend it twice.** Preserve what was typed, make
   errors correctable in place, and never clear a field as a side effect.
4. **Cached data is the product.** The catalog must stay browsable while anything
   refreshes behind it, and a failed refresh must never empty a screen.
5. **Irreversible actions must be hard to reach by accident**, because the one
   that matters here cannot be undone.

## Accessibility & Inclusion

- Ten-foot legibility is the governing constraint: minimum type sizes and contrast
  are set by viewing distance, not by desktop convention.
- Colour and contrast are already measured against WCAG (dark teal surface ramp
  with a Claude-orange accent, `ui/theme/Palette.kt`).
- Focus state must be unmistakable at distance — it is the only indication of
  where input will land.
- No product-specific assistive-technology requirement has been established.
