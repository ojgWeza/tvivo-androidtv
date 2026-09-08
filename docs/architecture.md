# Architecture

> Revised 2026-09-07 after `/plan-eng-review`. Decisions and rationale are in
> `docs/decisions.md`; this file describes the target design only.
> For UI/UX work start from `docs/ui-scope.md` instead — this file is the
> implementation view and deliberately says nothing about how screens look.

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
│   │   └── SeriesRepository.kt       // thin catalog half + lazy per-show episodes
│   ├── model/                        // shared DTOs (network + UI)
│   ├── AppError.kt                   // sealed error taxonomy
│   └── StreamUrlBuilder.kt           // URL construction + redact()
├── auth/
│   ├── CredentialsStore.kt           // DataStore + Tink (see Credentials)
│   ├── AccountIdentity.kt            // stable id derived from server+port+user
│   └── LoginScreen.kt
├── ui/                               // Compose for TV (androidx.tv:tv-material)
│   ├── home/                         // three big buttons: Live / Movies / Series
│   ├── browse/                       // rail + grid: THE main screen
│   │   ├── CategoryRail.kt           // categories + 4 virtual entries + filter
│   │   ├── BrowseItem.kt             // what the grid renders, for every type
│   │   ├── ContentGrid.kt            // paged grid, CardShape = POSTER | CHANNEL
│   │   └── ItemContextMenu.kt        // long-press OK: play / favourite / resume
│   ├── series/                       // season/episode picker (the layer only
│   │                                 // series has; a show is not playable)
│   ├── common/
│   │   ├── ErrorState.kt             // full-screen: refresh-time failure
│   │   ├── ErrorFooter.kt            // inline focusable: paging append failure
│   │   ├── FocusSpec.kt              // focus frame + last-active, one place
│   │   └── ImageLoader.kt            // shared Coil ImageLoader (sized, see below)
│   └── player/
│       └── PlayerActivity.kt         // Media3 PlayerView via AndroidView
├── diagnostics/
│   └── ErrorLog.kt                   // on-device redacted ring buffer
├── sync/
│   ├── CatalogSyncer.kt              // full-catalog tier: streamed parse + generations
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
- `series_episodes` — lazy per `series_id`, with its **own** TTL. `episode_id` is
  a **string**: the panel quotes it, it is what goes in the playback URL, and
  nothing is gained by round-tripping it through Int. No `generation` column —
  episodes arrive one show at a time, so plain delete-then-insert is right here
  where it is wrong at catalog scale. That delete-then-insert **refuses an empty
  list**: a rejected `get_series_info` parses to zero episodes, and wiping a
  cached season on that makes an already-cached show unplayable offline
- `resume_positions(account_id, content_type, item_id, position_ms, updated_at)`
  — the composite key matters; `item_id` alone collides across accounts
- `favourites(account_id, content_type, item_id, added_at)` — same composite-key
  discipline. Required by the FAVOURITES rail entry, which otherwise has no
  source of data at all
- `sync_meta(account_id, content_type, category_id, last_synced_at, generation)`
- `catalog_sync(account_id, content_type, state, done, total, updated_at)` —
  state is `not_started | indexing | complete | failed | stale`

### Three name columns, not one

Every stream row stores:

| Column | Purpose |
|---|---|
| `name` | exactly as the panel returned it. Diagnostics only, never rendered |
| `name_display` | quality tokens stripped, whitespace collapsed, **case and diacritics preserved**. This is what the UI renders |
| `name_normalized` | additionally lowercased and diacritics-stripped. Matching only |

`name_display` exists because rendering raw `name` breaks truncation. A title
like `"HD  للعدالة وجه آخر"` opens with a strong LTR run, so Compose's default
`TextDirection.Content` resolves the whole paragraph as LTR; the Arabic then
lays out RTL inside a left-aligned box and the ellipsis appears on the wrong
visual edge. Truncation looks random across a large fraction of the catalog.
All three columns are written in the same transaction as the row.

### Virtual rail entries own no sync state

`ALL`, `FAVOURITES`, `CONTINUE WATCHING` and `RECENTLY ADDED` are **views over
existing rows**. They must never get a `sync_meta` row, not even a sentinel
`category_id`. Giving `ALL` a sentinel reintroduces exactly the data-loss bug
the per-category key was introduced to fix: refreshing `ALL` stamps everything
fresh, and per-category refreshes then stop happening for 24 h. `ALL`'s
displayed freshness is the *oldest* category stamp, and manual refresh inside
`ALL` re-fetches only the categories that are actually stale.

Rail counts come from one grouped
`SELECT category_id, COUNT(*) … GROUP BY category_id` exposed as a `Flow`, so
they fill in live as the background sync lands. Note `category_id` is a
**string** on VOD and live items but an **int** on series objects — normalise
the type on insert or the grouped count silently misses series.

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

**Both tiers are gated, and this is not optional.** `CachedFetch.ensureFresh`
gates the per-category tier; `CatalogSyncer.isFresh` gates the full-catalog tier
against the *same* 24 h window. The full-catalog tier shipped ungated, and
because `BrowseViewModel.init` calls `sync*` on every construction, every entry
into a listing re-downloaded and rewrote the entire catalog (Q-13, measured at
13,264 rows rewritten 11 minutes after a completed sync).

Only `STATE_COMPLETE` counts as fresh. `STATE_PARTIAL`, `STATE_FAILED` and an
interrupted `STATE_INDEXING` all mean the catalog on disk is not known to be
whole, so the next screen that asks for it must re-run. A negative age (a clock
that moved backwards) is also treated as stale, or the catalog would pin fresh
forever.

Because the gate makes automatic re-sync impossible inside the window,
**`Refresh everything` is the only manual escape hatch and must cover every
content type** — it previously omitted series entirely.

**Category invalidation:** the category list itself is cached under a sentinel
`category_id` and refreshed on the same TTL. Handle renamed, removed, reordered,
and newly-empty categories, plus streams that move between categories.

**Series episodes** get their own TTL and manual refresh. Cached "outside the
TTL cycle" would mean stale forever, and new episodes would never appear.

Their freshness reuses `CachedFetch` with the **show id in the category slot**,
under a distinct content type (`series_info`). Filing them under `series` would
let show 770 and category 770 share one stamp and each make the other look
fresh.

### Full-catalog sync tier

The app syncs **all three content types in full** (~48,751 VOD rows, plus live
and series), because the rail's `ALL` and `RECENTLY ADDED` entries and
content-type-wide search all require completeness. This reverses the earlier
decision to avoid a second sync tier; see `decisions.md`.

Three things it must not do:

1. **Must not block.** Categories are one fast call, so a content type is
   browsable in about a second. The catalog syncs behind it with a visible
   percentage that dismisses at 100%. Blocking on data the user does not need
   yet is the most visible kind of slowness, and it would recur every time the
   24 h cache expires, not just on first run.
2. **Must not materialise the response.** A 48,751-item VOD payload is roughly
   15 MB of JSON; parsed into a `List<T>` that is 30–50 MB of heap on a box
   whose per-app limit may be 96 MB, *while* a Paging grid and a Coil bitmap
   cache are live. `CachedFetch` therefore takes a `Flow<T>` or a callback sink
   and never returns a `List<T>`: stream-parse from the OkHttp `BufferedSource`
   with a `JsonReader`, inserting in chunks of ~500.
3. **Must not hold one giant write lock.** The single-`@Transaction`
   delete-then-insert is right for a 400-item category and wrong for 48,751
   rows, where it becomes a multi-second write that blocks every read and
   freezes the grid. Use **chunked transactions plus a generation counter**:
   write rows with `generation = N+1`, then in one small final transaction flip
   the active generation and delete `generation <= N`. This preserves the
   all-or-nothing property the eng review required, without the lock.

**Precondition, verified 2026-09-07.** `get_vod_streams` with no `category_id`
returns the full VOD catalog on this panel. The tier therefore stands as
designed, and the fallback — ~120 sequential per-category requests, which would
change the progress model but not the design — is not needed for movies.

**Verified 2026-09-08 for `get_live_streams` and `get_series` too** — 6,425
channels and 13,264 shows, each in one call. All three content types now use the
tier, and they share **one** `syncCatalog` body in `CatalogSyncer` rather than
three copies: the zero-row guard below was latent in the VOD path and only found
while building live, and a third copy is a third place to get it wrong.

**A zero-row response must never flip the generation.** A panel that rejects the
call answers with a JSON object rather than an array, which the parser reports as
zero rows — indistinguishable from a genuinely empty catalog, and in both cases
flipping generations would delete every per-category row the user already has.
Both paths instead record `partial` and leave the existing rows alone.

**Gating.** `ALL`, `RECENTLY ADDED` and search are disabled until
`catalog_sync.state = complete` for that content type. A partially indexed
catalog answering a search returns a confident "no results" for a title that
exists — the precise failure the original scoped-search decision existed to
prevent, relocated rather than solved. Ordinary category browsing works
throughout. Sync resumes safely after process death, and never downloads poster
art as part of the sync.

The daily re-sync of 48,751 rows should be staggered via WorkManager at low
priority rather than fired all at once on the first foreground of the day.

## Large lists and paging

Category payloads run to thousands of items, and `ALL` to tens of thousands.
DAOs return `PagingSource`; list screens consume `LazyPagingItems`; inserts are
chunked. Memory stays flat regardless of category size — necessary on a
1–2 GB TV box.

**Paging configuration is a focus requirement, not a performance tuning knob.**
These are not optional:

- **`enablePlaceholders = true`.** With placeholders off, if the played item is
  at index 800 the list *does not contain index 800* until the user scrolls
  there — so focus restoration cannot find it and silently degrades to "top of
  grid". No amount of UI code fixes this afterwards. Room's `PagingSource`
  supports it because it knows `COUNT(*)`. The cost is that every item
  composable must have a placeholder rendering.
- **Stable keys**: `items(lazyPagingItems, key = { it.streamId })`. Without
  them, recomposition after an append rebinds focus onto the wrong item.
- **The loading footer must not be focusable**
  (`Modifier.focusProperties { canFocus = false }`), or the user D-pads onto a
  spinner and cannot get past it.
- **Placeholders are focusable but inert** — they render the skeleton and OK is
  a no-op. Non-focusable placeholders make traversal skip a hole and jump
  unpredictably.
- **`prefetchDistance` ≥ 2 rows**, which is 10 items in poster mode and 4 in
  names-only. It differs per view mode.
- `Modifier.focusGroup()` on the grid plus `focusRestorer()`, so
  LEFT-to-rail-and-back returns to the same card.

## Search

Scoped to the **content type**, not to a category and not across the catalog.
The Home screen picks Live, Movies or Series, and search inside that content
type covers all of it. Three searches exist and each is complete within its own
scope, so the boundary is visible in the navigation rather than in a label.

This supersedes the earlier category-scoped design; see `decisions.md`.

- **Content-type search** — over every row of that type. Requires
  `catalog_sync.state = complete` for the type; disabled and labelled until then.
- **Category filter** — filters the ~120 cached category names in the rail. A
  plain `LIKE` over ~120 rows, always available.

**All three content types are synced in full.** If only VOD were synced,
someone searching for a channel or a show would get a confident "no results"
for something that exists — the exact failure the original decision rejected.
Live is ~10–20k rows and series is metadata only, so the cost over movies is
marginal. Series *episodes* remain lazy per show, so selecting a series result
triggers `get_series_info` and needs its own loading state on the detail screen.

**Match against `name_normalized`, render `name_display`.** Normalisation is
lowercase, Arabic diacritics stripped, whitespace collapsed, quality tokens
(`HD`, `FHD`, `4K`) removed — applied on insert, in the same transaction as the
write.

**Query mechanics.** Debounce 300 ms, run on `Dispatchers.IO` through the
paging source. DAOs expose a query-bearing `PagingSource` variant so results
page exactly like unfiltered ones.

**FTS remains deferred, but the reasoning has changed and must be measured.**
The original argument was that a category is only a few thousand rows. Content-
type search is ~48,751, and `LIKE '%query%'` has a leading wildcard so no index
applies — it is a full scan with UTF-8 comparison on every keystroke, on an
A53-class CPU. Keep `LIKE` for now, because SQLite's default tokenizers still
handle Arabic poorly, but add a covering index on
`(account_id, content_type, name_normalized)` so prefix queries stay indexed,
and instrument it on the real box. If p95 per keystroke exceeds ~200 ms,
revisit with `unicode61 remove_diacritics=2`.

## Images

One shared Coil `ImageLoader`, downsampling to the actual card dimensions before
caching. Full-size poster decode is a known cause of grid stutter and OOM on a
constrained TV heap.

**Two downsample targets**, because live channel art is not a poster:

| Target | Size | Used by | Scaling |
|---|---|---|---|
| `POSTER` | 220 × 330 px | movies, series | crop to fill |
| `CHANNEL` | 220 × 124 px | live TV | **fit**, letterboxed on a neutral tile — never crop |

Cropping a channel logo to 2:3 destroys it. Both sizes must be decided together
because they are inputs to the pipeline, not styling.

**Cache limits — these need numbers, not adjectives.** At ~25–35 KB per
downsampled poster, browsing a third of a 48,751-title catalog reaches ~500 MB
and the full catalog ~1.5 GB, on a box that may have 8 GB of total flash.

```
disk cache      250 MB hard cap, LRU
memory cache    maxMemory / 8, hard-capped at 32 MB
bitmap config   RGB_565   (halves memory; posters are photographic and
                           banding is invisible at 3 m)
crossfade       disabled  (animated fades during D-pad scroll read as
                           flicker at distance)
prefetch        visible viewport + 1 row only. Aggressive prefetch over a
                9,750-row list fills 250 MB in minutes
```

Poster art is **never** downloaded as part of the catalog sync.

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
at the network/player boundary.

**One taxonomy, two renderers.** `ErrorState` is full-screen and correct for
refresh-time failure. `ErrorFooter` is inline, focusable and one line, and is
required for Paging's *append* failure — a failed append must never take over
the screen and destroy 400 items the user is already reading. Paging has three
error surfaces (`refresh`, `append`, `prepend`); only `refresh` gets the
full-screen treatment.

**Each error carries its own action**, rather than a generic retry. A retry
button on `AuthFailed` or `AccountExpired` cannot fix anything and teaches the
user the app is broken. The action table is in `ui-scope.md`.

`ConnectionLimitReached` is a **probable** classification, not a definitive one
— panels signal an exhausted slot inconsistently (HTTP error, HTML body,
malformed media, or simply terminating playback).

Also define, rather than leaving to Phase 5: network timeouts, retry policy,
cancellation, backgrounding, process death, player release, and what happens
when a catalog refresh overlaps active playback.

## Build sequencing

0. **Skeleton + install loop** — ✅ done 2026-09-07. Gradle/Kotlin, TV manifest,
   network security config, stable debug signing key (an unstable key wipes
   stored credentials on every install), install loop verified on an Android TV
   API 34 emulator (`tvivo_tv34`), codec probe recorded, and `get_vod_streams`
   confirmed to answer with no `category_id`.

   **`dumpsys media.codec` does not exist on API 34** — the service was removed,
   and `adb shell dumpsys media.codec` answers `Can't find service: media.codec`.
   The probe therefore runs in-process (`diagnostics/CodecProbe.kt`) against
   `MediaCodecList`, logged under the `TvivoCodecProbe` tag and read with
   `adb logcat -s TvivoCodecProbe`. This is strictly better than the dumpsys
   route: it reports what ExoPlayer itself will see, and it runs unchanged on
   the physical box.

   First result (Android TV emulator, API 34, `android-tv;x86`): AVC, HEVC, VP9
   and MPEG-2 all decode, HEVC to 4096×4096 hardware. **No AC3 and no E-AC3
   decoder.** A share of `.mkv` movie files carry AC3/E-AC3 audio, so on the
   emulator those would play as video with silence. Re-run the probe on the real
   TV before drawing conclusions about audio.

   **Do not reach for that explanation first.** Silent playback was observed on
   2026-09-07 and it was *not* the codec set: logcat showed the stream was AAC
   (`audio/mp4a-latm`), `c2.android.aac.decoder` initialised cleanly, and no
   `DecoderInitializationException` was thrown. The emulator's media volume was
   simply at 3/15. Check `dumpsys audio` for `streamVolume` and confirm the
   actual track MIME in logcat before blaming the decoder — the probe result
   above makes a wrong diagnosis very easy to reach for.

   `debug.keystore` is generated locally and gitignored (`*.keystore`), not
   committed — regenerate identically on a new machine with:
   `keytool -genkeypair -v -keystore debug.keystore -storepass android
   -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048
   -validity 10950 -dname "CN=Tvivo Debug, OU=Dev, O=Tvivo, L=NA, ST=NA, C=US"`
   — same alias/passwords every time is what keeps the signature stable across
   machines, not the file itself being shared.
1. **Auth** — ✅ done 2026-09-07. DataStore+Tink credentials, validated via
   `player_api.php`, one server field parsing `host:port`, show-password toggle,
   form never cleared on failure.

   Three defects here were found only by driving the emulator with deliberately
   wrong credentials, after the code compiled, launched and passed 66 unit
   tests: D-pad focus was trapped in the text fields so **Sign in was
   unreachable and the app was unusable on a remote**; Enter was consumed by the
   IME so nothing submitted; and the password leaked into the IME suggestion
   strip because the field never declared `KeyboardType.Password`. Fixed in
   `c89424e`. The lesson generalises to every screen: on a D-pad device,
   "compiles and launches" proves almost nothing.

2. **Movies vertical slice** — ✅ done 2026-09-07. The reference implementation
   every other content type copies: `CachedFetch`, paged grid, player, resume
   positions, favourites, full-catalog sync. All four required items landed:
   `enablePlaceholders = true` with stable keys, focus restoration by item ID,
   the focus frame, and the long-press context menu.

   Verified against the real panel: 48,754 VOD rows synced to
   `catalog_sync.state = complete`, one generation with no orphans, 118
   categories with live counts, posters, Arabic RTL, and playback end to end.

   Open defects from the QA pass are in `TODOS.md` Part 1. **Q-2 (no in-app
   back stack) must be fixed before Phase 3** — every later screen inherits it.

3. **Live** — same pattern; note `ext`, not `container_extension`; no seek; and
   the **`CHANNEL(220×124)` 16:9 card**, not the poster card.
4. **Series** — extra layer; `get_series` (not `get_series_streams`); episodes
   arrive as an object keyed by season number as a string.

   Built 2026-09-08. Three things the design did not anticipate, all found by
   driving the emulator: `duration_secs` is wrong on this panel (a 60-episode
   drama reported 9-190 "seconds", so `duration` / `HH:MM:SS` is read first),
   episode titles repeat the show's leading quality token and need the same
   `NameNormalizer` display pass the grid uses, and the detail screen opened
   with focus on nothing until it requested it explicitly.
5. **Hardening** — manual refresh, RTL verification, empty/loading/error states,
   `RefreshWorker`, on-device diagnostic log.

**All three list endpoints answer without a `category_id`** — `get_vod_streams`
(2026-09-07), `get_live_streams` and `get_series` (both 2026-09-08). The
per-category fallback is still the behaviour on a zero-row answer, and still the
right one, but no content type depends on it today.

**Phases 3-5 share one browse screen, not three.** The grid renders `BrowseItem`
and takes a `CardShape`; one `BrowseViewModel` picks a `CatalogSource` from the
content type. `StreamListParser` holds the streaming JSON reader and absorbs the
`ext` / `container_extension` difference, with `VodStreamParser` and
`LiveStreamParser` as field mappings over it. Series added a `CatalogSource`
factory, not a browse screen — `SeriesListParser` is a third field mapping over
the same reader, which also absorbs `series_id` / `cover` / `last_modified`.

The one screen series does add is `ui/series/`: a show is **not playable**
(`series_id` addresses no stream endpoint), so activating one opens the
season/episode picker and `MainActivity` routes on content type. The picker
reuses the browse screen's layout and LEFT/RIGHT focus contract deliberately —
a viewer arriving from the Movies grid should not have to learn a second set of
navigation rules for the same D-pad.

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
