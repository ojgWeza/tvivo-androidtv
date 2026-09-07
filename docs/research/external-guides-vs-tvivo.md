# External guides vs. what this project already knows

> Compiled 2026-09-07. Source material and quotes: `external-guides-digest.md`.
> This file is **research, not spec**. Where it identifies a real gap, the fix
> belongs in `architecture.md` / `ui-scope.md` / `decisions.md`, not here.

## Verdict

The project's own docs are **substantially deeper than all five articles on
every topic the articles cover**, with three exceptions. The articles operate
at the level of "you will need a video player and a CDN"; `architecture.md`
operates at the level of "`FLAG_DETECT_ACCESS_UNITS`, or TS lacking AUDs hangs
in BUFFERING forever with no error". There is no architecture, caching, focus,
paging, memory or error-handling advice in any of the five that this repo does
not already carry in more usable form.

The exceptions are worth taking seriously, because all three come from the one
source that built the same *kind* of product (Purrweb's playlist player) rather
than the market category:

1. **Audio-track and subtitle-track selection in the player** — genuinely
   absent from every doc in this repo, and Purrweb treated it as a shipping
   blocker on the same content type.
2. **Adult content in an uncurated feed** — this repo plans to render ~120
   panel categories "as-is" and has no answer for what happens when some of
   them are XXX.
3. **HTTPS→HTTP fallback is specified as an intention, not a flow** — Oxagile's
   fallback checklist is exactly the shape of the paragraph `architecture.md`
   is missing.

Everything else divides into "already deeper" and "not applicable".

---

## Topic-by-topic

| Topic | The guides say | This project already has | Verdict |
|---|---|---|---|
| Streaming protocol | HLS / DASH / RTMP / RTSP, adaptive bitrate assumed | Direct progressive `.mkv`/`.mp4`/`.ts` files, explicitly not HLS manifests; extractor flags specified per failure mode | **Deeper.** The guides assume a delivery model this panel does not use |
| Multicast / IGMP | Central to "real" IPTV; needs socket-level handling | N/A — Xtream over the public internet is unicast HTTP by definition | **N/A**, and the digest explains why |
| Player choice | VLC fork (Purrweb); "custom media players for MPEG-TS" (Oxagile) | Media3 `PlayerView` in `AndroidView`, with a documented rationale and required extractor flags | **Deeper** |
| **Audio / subtitle tracks** | Purrweb forked a player to expose voice-over and subtitle switching, then built language filtering on it | **Nothing.** No track selection anywhere in `ui-scope.md` or `architecture.md` | **Gap — see below** |
| Codecs | "H.264, H.265, VP9" | In-process `CodecProbe` against `MediaCodecList`; recorded emulator result including the missing AC3/E-AC3 decoder | **Much deeper** |
| DRM / content protection | Widevine, PlayReady, CAS, encrypted sessions | Nothing — and correctly so; Xtream serves unencrypted files over path-embedded credentials | **N/A** |
| Credential handling | "OAuth", "encryption" | DataStore + Tink, keystore invalidation recovery, backup exclusion, `redact()` on every logged URL | **Much deeper** |
| Concurrency limits | "Account-sharing abuse… device limits, concurrent stream rules" (Oxagile) | `max_connections = 1`, `ConnectionLimitReached` as a *probable* classification, no automated test may open a stream | **Deeper**, and from the client side of the same problem |
| Caching | Not mentioned by any source | Per-category TTL, account scoping, generation counters, chunked transactions, streamed `JsonReader` parse | **Only in this repo** |
| Large lists | Not mentioned by any source | Paging 3 with `enablePlaceholders = true` as a *focus* requirement, stable keys, prefetch distance per view mode | **Only in this repo** |
| Image pipeline | Not mentioned by any source | Two downsample targets, `RGB_565`, 250 MB LRU disk cap, crossfade disabled, prefetch bounded | **Only in this repo** |
| TV UX | "Lean-back, low-input, clear focus states, few choices per screen, resume points, favorites" | Full focus contract, static focus frame, long-press context menu, rail + grid, bounded continue-watching | **Deeper** — the guides state the principle, `ui-scope.md` states the implementation |
| RTL / bidi | Not mentioned by any source | Three name columns, first-strong direction resolution, bidi isolation, the truncation failure explained | **Only in this repo** |
| **EPG** | "The screen your live TV audience uses most, and where a weak IPTV app becomes obvious" (Oxagile) | Explicitly out of scope; XMLTV endpoint listed as uninvestigated | **Known deferral — worth re-reading the quote** |
| **Adult content** | Purrweb shipped a "Hide 18+" setting with PIN after finding XXX posters at the top of a stock playlist | Categories rendered "as-is"; no filter, no PIN, no mention | **Gap — see below** |
| **Runtime fallback** | Oxagile's checklist: when to leave the path, what happens to playback position, telemetry on trigger/outcome/error | "If `server_info` reports an `https_port`, try HTTPS first and fall back to HTTP on failure" — one sentence | **Gap — see below** |
| Onboarding on a remote | Redirect to mobile for sign-up if possible | One server field parsing `host:port`, show-password toggle, form never cleared on failure | **Deeper**, and the redirect trick does not apply (no companion app, no accounts) |
| Cloud DVR / timeshift | Listed as a standard feature | Out of scope; `tv_archive: 0` on observed channels | **Already decided, on evidence** |
| Monetization | SVOD / TVOD / AVOD / hybrid, at length in all five | N/A — personal project, no monetization | **N/A** |
| CDN, backend, scaling | Heavy coverage in four of five | N/A — no backend exists; the panel is the backend | **N/A** |
| Android TV Operator Tier | Certification route for operator STBs | N/A — sideloaded app on the user's own box | **N/A** |
| Cross-platform | Assumed mandatory; React Native recommended | Native Kotlin, non-negotiable; webOS out of scope until the Android POC works | **Already decided, contrary, and correctly so** |
| Cost / timeline | ~3–5 months single platform; ~$67.6k average | Phase 0 done 2026-09-07; solo build | Context only |
| Licensing risk | Emphasised by all five | Provider-agnostic by design; no hostname, port or account detail committed | **Different risk, already handled** |

---

## The three things actually worth acting on

### 1. Audio-track and subtitle-track selection — missing entirely

**Why it matters here specifically.** Purrweb hit this on the same product
shape: a third-party feed of `.mkv` files. `.mkv` is a container built for
multiple audio and subtitle tracks, and a catalogue this size will routinely
carry original-language plus dubbed audio, and embedded subtitle tracks.
Media3's `TrackSelectionParameters` handles selection, but **nothing in this
repo says a UI for it exists**, and `ui-scope.md`'s player section defines
states without a track control.

This compounds with something already recorded in `architecture.md`: the
emulator codec probe found **no AC3 or E-AC3 decoder**. If a file carries an
AC3 track and an AAC track, track selection is not a preference — it is the
difference between silence and sound.

**Suggested resolution.** Decide it in Phase 2 alongside the rest of the player
work, not Phase 5. Minimum viable: default track selection driven by a
preferred-language setting, plus a D-pad-reachable track picker in the player
overlay. This is a `ui-scope.md` + `decisions.md` change if accepted.

### 2. Adult content in an uncurated 120-category rail

**Why it matters here specifically.** `ui-scope.md` states categories are
"unordered and uncurated… mixing language, era, genre and studio with no
hierarchy, **rendered as-is**", and `decisions.md` records that `ALL` renders
in panel order. Both are reasonable decisions taken without considering that a
commercial Xtream panel very commonly ships XXX categories, and that `ALL` in
panel order plus `RECENTLY ADDED` will surface those posters without the user
navigating to them. Purrweb found exactly this and it reached QA before anyone
noticed.

**Suggested resolution.** Cheapest correct version: a category-level exclusion
matched on the panel's own naming (XXX / ADULT / +18 markers), on by default,
excluded from `ALL`, `RECENTLY ADDED` and search, toggleable in settings. A PIN
is probably over-engineering for a single-user personal app — but the default
matters, because the failure is a full-screen poster wall on a TV in a shared
room. Worth a `decisions.md` entry either way, including "decided not to".

### 3. HTTPS→HTTP fallback needs the flow, not the intention

`architecture.md` currently says: try HTTPS first if `server_info` reports an
`https_port`, fall back to HTTP on failure. Oxagile's checklist names what that
sentence leaves undefined:

- **When** does it leave HTTPS — connect failure only, or also TLS handshake
  failure, cert failure, timeout, mid-stream failure?
- **What happens to playback position** if the fallback occurs during playback
  rather than at list-fetch time?
- **Is the decision sticky** for the session, or re-evaluated per request?
  (Re-evaluating per request doubles latency on every call when HTTPS is
  broken.)
- **Does it surface?** Silent downgrade from encrypted to cleartext, with
  credentials in the URL path, is a security-relevant event the user cannot see.
- **Telemetry** — the `diagnostics/ErrorLog.kt` ring buffer already exists;
  record selected path, trigger and outcome in it.

Note this interacts with an open item in `xtream-api-reference.md`: "HTTPS port
behavior — cert validity, whether it works at all." The flow should be defined
*after* that is measured, but before Phase 5.

---

## Worth noting, not acting on

- **The EPG quote.** "It's the screen your live TV audience uses most, and it's
  where a weak IPTV app becomes obvious." Deferring EPG remains right for a POC
  proving the browse-and-play loop. But this is the clearest outside statement
  of what deferring it costs, and it belongs in the conversation whenever Live
  TV moves past Phase 3.
- **"TV as an afterthought" (OSKI).** The named industry pitfall this project
  structurally cannot fall into — TV-first, D-pad-first, native Kotlin chosen
  for that reason. Confirmation, not new information.
- **"Integration is what slips, not the UI" (Oxagile).** No billing or
  entitlement layer here, but the analogue holds: the risky surface is the
  panel contract and the player, not the screens. The build order already
  reflects this.
- **"Live is not VOD" (OSKI).** Already encoded — Phase 3 is separate, live
  uses `ext` not `container_extension`, the 16:9 channel card is a distinct
  image target, and the scrub bar is hidden because progressive live cannot
  seek.
- **Purrweb's language-filter differentiator.** They filtered *content* by
  voice-over/subtitle language as a browse feature. Adjacent to gap 1 but a
  larger scope; the panel's `name` field is the only language signal available
  here, and it is noisy. Not recommended for the POC.

## What the articles do not know about at all

Listed because it is the clearest measure of how far ahead the project's own
docs are. None of the five sources address any of these, and every one of them
is a decided, documented part of this build:

Xtream Codes `player_api.php` · per-category TTL and the silent data-loss bug it
prevents · account-scoped cache rows · generation-counter writes · streamed
JSON parsing against a 96 MB heap · Paging placeholders as a focus
prerequisite · focus restoration by stable item ID · downsample-before-cache
image pipeline · RTL/bidi title truncation · credential redaction in player
error strings · `FLAG_DETECT_ACCESS_UNITS` and the silent BUFFERING hang ·
`onRenderedFirstFrame` watchdog for black-video-with-audio on MediaTek HEVC ·
cleartext permitted without weakening TLS · Paging's three error surfaces
needing two different renderers.
