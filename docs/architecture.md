# Architecture

> Revised 2026-09-07 after `/plan-eng-review`. Decisions and rationale are in
> `docs/decisions.md`; this file describes the target design only.

## Package structure

```
app/
├── data/
│   ├── remote/
│   │   ├── XtreamApiService.kt       // Retrofit interface
│   │   ├── XtreamApiClient.kt        // base URL builder (normalized, see below)
│   │   └── ErrorMapper.kt            // HTTP/IO/player failure -> AppError
│   ├── local/
│   │   ├── AppDatabase.kt            // Room DB
│   │   ├── dao/
│   │   │   ├── CategoryDao.kt
│   │   │   ├── VodDao.kt             // returns PagingSource
│   │   │   ├── LiveDao.kt            // returns PagingSource
│   │   │   ├── SeriesDao.kt
│   │   │   └── SyncMetaDao.kt
│   │   └── entities/                 // Room @Entity classes (all account-scoped)
│   ├── repository/
│   │   ├── CachedFetch.kt            // THE shared cache primitive — see below
│   │   ├── VodRepository.kt          // thin: field mapping + playback rules only
│   │   ├── LiveRepository.kt         // thin
│   │   ├── SeriesRepository.kt       // thin
│   │   └── SeriesEpisodeRepository.kt// lazy per-show, own TTL
│   ├── model/                        // shared DTOs (network + UI)
│   ├── AppError.kt                   // sealed error taxonomy
│   └── StreamUrlBuilder.kt           // URL construction + redact()
├── auth/
│   ├── CredentialsStore.kt           // DataStore + Tink (see Credentials)
│   ├── AccountIdentity.kt            // stable id derived from server+port+user
│   └── LoginScreen.kt
├── ui/                               // Compose for TV (androidx.tv:tv-material)
│   ├── categories/                   // category grid per content type
│   ├── streamlist/                   // paged VOD/Live list within a category
│   ├── seriesdetail/                 // season/episode picker
│   ├── common/
│   │   ├── ErrorState.kt             // ONE error component for all screens
│   │   └── ImageLoader.kt            // shared Coil ImageLoader
│   └── player/
│       └── PlayerActivity.kt         // Media3 PlayerView via AndroidView
├── diagnostics/
│   └── ErrorLog.kt                   // on-device redacted ring buffer
├── sync/
│   └── RefreshWorker.kt              // WorkManager — best-effort warm-up ONLY
└── MainActivity.kt / Navigation.kt

app/src/main/res/xml/network_security_config.xml   // cleartext permitted, strict TLS
```

## Credentials

Stored with **Jetpack DataStore + Tink**, not `EncryptedSharedPreferences`
(deprecated in `security-crypto:1.1.0-alpha07`; main-thread StrictMode
violations and keyset-corruption crashes on some OEM devices — and Android TV
boxes are predominantly those OEMs).

Requirements:
- All credential I/O off the main thread (Coroutines/Flow).
- Define the Android Keystore key lifecycle explicitly, including recovery when
  the key is invalidated or the payload fails to decrypt (wipe and re-prompt;
  never crash-loop).
- Exclude the credential store from Android backup.

## Transport

Panels are commonly HTTP on non-standard ports. `targetSdk 28+` blocks cleartext
by default, so a `network_security_config.xml` permitting cleartext is
**required** — without it the app cannot reach the panel at all.

Two rules that are not negotiable:
- Keep **system trust anchors**. Never install a trust-all `TrustManager` or
  disable hostname verification to work around a bad certificate.
- If `server_info` reports an `https_port`, try HTTPS first and fall back to
  HTTP on failure. Credentials travel in the URL path on every request, so
  encrypted transport is worth having wherever the panel supports it properly.

Base URL construction must normalize: scheme, port, trailing slash, path
prefix, IPv6 host bracketing, and URL encoding of the user/pass segments.

## Caching strategy

**Every cached row is scoped to an account.** `account_id` is derived from
server + port + username. Changing credentials or pointing the app at a
different panel must never surface the previous account's catalog, artwork, or
resume positions. On account change, wipe atomically.

**Room tables** (all carry `account_id`):
- `categories(account_id, type, id, name)` — `type` distinguishes live/vod/series
- `vod_streams`, `live_streams`
- `series` — show metadata only (from `get_series`)
- `series_episodes` — lazy per `series_id`, with its **own** TTL
- `resume_positions(account_id, content_type, item_id, position_ms)` — the
  composite key matters; `item_id` alone collides across accounts
- `sync_meta(account_id, content_type, category_id, last_synced_at)`

### Why `category_id` is in `sync_meta`

Fetches are **per category** (`get_vod_streams&category_id={id}`). Tracking
freshness per content type while overwriting the whole table produces a silent
data-loss bug:

```
   BROKEN (one sync_meta row per content type)     FIXED (row per category)
   ────────────────────────────────────────────   ─────────────────────────────
   open A  → fetch A                               open A → fetch A
           → overwrite vod_streams  ← wipes B             → delete WHERE cat=A
           → stamp vod = now                              → insert A
                                                          → stamp (vod,A) = now
   open B  → vod is "fresh" → serve Room
           → EMPTY, and stays empty 24h            open B → (vod,B) stale → fetch B
                                                          → A untouched
```

### Refresh logic (`CachedFetch`)

One primitive owns the whole mechanism; the per-type repositories only supply
the API call and the row mapping. This keeps the TTL/transaction logic in
exactly one place without one class owning three content types.

1. On read, check `now - last_synced_at > TTL` for `(account, type, category)`.
2. If stale → fetch, then in a **single `@Transaction`**: delete only that
   category's rows, insert the new ones in chunks, stamp `last_synced_at`.
   A fetch that fails mid-way rolls back — never a half-written category.
3. If fresh → serve from Room, no network call.
4. **Manual refresh** ignores the TTL, always fetches, same transaction.

**TTL is enforced at read/foreground time.** `RefreshWorker`'s
`PeriodicWorkRequest` is opportunistic — WorkManager may run it late or not at
all — so it is a best-effort warm-up, never the mechanism freshness depends on.

**Category invalidation:** the category list itself is cached under a sentinel
`category_id` and refreshed on the same TTL. Handle renamed, removed, reordered,
and newly-empty categories, plus streams that move between categories.

**Series episodes** get their own TTL and manual refresh. Cached "outside the
TTL cycle" would mean stale forever, and new episodes would never appear.

## Large lists

Category payloads can run to thousands of items. DAOs return `PagingSource`;
list screens consume `LazyPagingItems`; inserts are chunked inside the
transaction above. Memory stays flat regardless of category size — necessary on
a 1–2 GB TV box.

## Images

One shared Coil `ImageLoader`, downsampling posters to the actual card
dimensions before caching, with explicitly sized memory and disk caches. Full-
size poster decode is a known cause of grid stutter and OOM on a constrained
TV heap.

## Playback URL construction

Centralized so credential/server changes propagate without touching UI code:

```kotlin
object StreamUrlBuilder {
    fun movie(s: String, p: String, u: String, pw: String, id: Int, ext: String) =
        "$scheme://$s:$p/movie/$u/$pw/$id.$ext"

    fun live(s: String, p: String, u: String, pw: String, id: Int, ext: String) =
        "$scheme://$s:$p/live/$u/$pw/$id.$ext"

    fun episode(s: String, p: String, u: String, pw: String, id: Int, ext: String) =
        "$scheme://$s:$p/series/$u/$pw/$id.$ext"

    /** Xtream puts credentials in the path. Never log or display a raw URL. */
    fun redact(uri: String): String = /* replace user+pass segments with *** */
}
```

All logging routes through `redact()`, and raw URLs never appear in error UI.
ExoPlayer error messages echo the full URI, so this is the difference between
a pasted log being safe and it disclosing the account password.

## Player

Compose for TV for all browse UI; the player is Media3's classic `PlayerView`
hosted in an `AndroidView` (Leanback is deprecated; `LeanbackPlayerAdapter`
with it). Streams are direct progressive files, not HLS manifests.

Required configuration — these are silent failures, not exceptions:

```
DefaultExtractorsFactory:
  FLAG_DETECT_ACCESS_UNITS            // TS lacking AUDs otherwise hangs in
  FLAG_ALLOW_NON_IDR_KEYFRAMES        //   BUFFERING forever, no error
  FLAG_ENABLE_CONSTANT_BITRATE_SEEKING// progressive has no seek index

Watchdogs:
  BUFFERING past threshold   -> AppError   // covers dead channel, conn. limit
  no onRenderedFirstFrame    -> AppError   // HEVC/MediaTek renders BLACK video
                                           //   with audio playing, no exception;
                                           //   a BUFFERING watchdog cannot see it
```

Progressive live streams cannot seek at all — **hide the scrub bar for live**.

## Errors

One sealed `AppError`: `Unreachable`, `AuthFailed`, `AccountExpired`,
`ConnectionLimitReached`, `StreamUnavailable`, `Timeout`, `Empty`. Mapped once
at the network/player boundary, rendered by one shared `ErrorState` component.

`ConnectionLimitReached` is a **probable** classification, not a definitive one
— panels signal an exhausted slot inconsistently (HTTP error, HTML body,
malformed media, or simply terminating playback).

Also define, rather than leaving to Phase 5: network timeouts, retry policy,
cancellation, backgrounding, process death, player release, and what happens
when a catalog refresh overlaps active playback.

## Build sequencing

0. **Skeleton + install loop** — Gradle/Kotlin, TV manifest, network security
   config. Plus: `adb connect` workflow, a **stable debug signing key** (an
   unstable key wipes stored credentials on every install), and a one-time
   `adb shell dumpsys media.codec` probe to record what the target box can
   actually decode.
1. **Auth** — DataStore+Tink credentials, validate via `player_api.php`
   (`auth: 1` and `status: "Active"`), four distinguishable failure states.
2. **Movies vertical slice** — the reference implementation every other content
   type copies: `CachedFetch`, paged list, player, resume positions.
3. **Live** — same pattern; note `ext`, not `container_extension`; no seek.
4. **Series** — extra layer; `get_series` (not `get_series_streams`); episodes
   arrive as an object keyed by season number as a string.
5. **Hardening** — manual refresh, RTL verification, empty/loading/error states,
   `RefreshWorker`, on-device diagnostic log.

## Testing

Emulator-first, at the **same API level as the physical TV**
(`adb shell getprop ro.build.version.sdk`). Local JUnit + coroutines-test +
Turbine for repositories, Room in-memory for DAO/transaction tests,
MockWebServer for the API contract and the season-keyed-object parsing,
Robolectric where framework classes are needed. Automated Compose D-pad focus
tests on the emulator.

Physical-TV validation happens once, after Phase 5 (deliberate tradeoff —
accepted risk, with the live `.ts` path carrying the most exposure).
`max_connections = 1` means no automated test may open a stream.

## Explicitly out of scope for the POC

- LG webOS build (separate app model — web-based, Luna SDK, `ares-cli`).
- EPG / program guide.
- Timeshift/catch-up playback for archived channels.
- CI / release signing / GitHub Releases — deferred until there is a release.
