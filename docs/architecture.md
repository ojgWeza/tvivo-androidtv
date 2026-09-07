# Architecture

## Package structure

```
app/
├── data/
│   ├── remote/
│   │   ├── XtreamApiService.kt       // Retrofit interface
│   │   └── XtreamApiClient.kt        // base URL builder (server:port from prefs)
│   ├── local/
│   │   ├── AppDatabase.kt            // Room DB
│   │   ├── dao/
│   │   │   ├── CategoryDao.kt
│   │   │   ├── VodDao.kt
│   │   │   ├── LiveDao.kt
│   │   │   └── SeriesDao.kt
│   │   └── entities/                 // Room @Entity classes
│   ├── repository/
│   │   ├── VodRepository.kt          // cache-or-fetch logic per content type
│   │   ├── LiveRepository.kt
│   │   └── SeriesRepository.kt
│   └── model/                        // shared DTOs (network + UI)
├── auth/
│   ├── CredentialsStore.kt           // EncryptedSharedPreferences: server, user, pass
│   └── LoginScreen.kt
├── ui/
│   ├── categories/                   // category grid per content type
│   ├── streamlist/                   // VOD/Live list within a category
│   ├── seriesdetail/                 // season/episode picker
│   └── player/
│       └── PlayerActivity.kt         // Media3 ExoPlayer, fullscreen, D-pad controls
├── sync/
│   └── RefreshWorker.kt              // WorkManager, 24h periodic + manual trigger
└── MainActivity.kt / Navigation.kt
```

## Caching strategy

**Room tables:**
- `categories(type, id, name)` — `type` distinguishes live/vod/series
- `vod_streams`
- `live_streams`
- `series` — show metadata only (from `get_series`)
- `series_episodes` — populated lazily per `series_id` when a show is opened,
  not upfront for the whole catalog
- `sync_meta(content_type, last_synced_at)` — one row per content type

**Refresh logic (repository layer):**
1. On screen load, check `now - last_synced_at > 24h` for that content type.
2. If stale → fetch from API, overwrite the Room table, update `last_synced_at`.
3. If fresh → serve from Room, no network call.
4. **Manual refresh button** on category/list screens ignores the TTL check,
   always fetches, overwrites, and updates `last_synced_at`.

**Background refresh:** schedule a 24h `PeriodicWorkRequest` via WorkManager
so data is warm even if the user doesn't trigger a fetch by opening a screen
right at the 24h mark. Optional for the POC but cheap to add — don't defer
this to "later" once the manual/on-demand path works, since it's a small
addition to the same repository logic.

## Playback URL construction

Centralize in one object so credential/server changes propagate everywhere
without touching UI code:

```kotlin
object StreamUrlBuilder {
    fun movie(server: String, port: String, user: String, pass: String, id: Int, ext: String) =
        "http://$server:$port/movie/$user/$pass/$id.$ext"

    fun live(server: String, port: String, user: String, pass: String, id: Int, ext: String) =
        "http://$server:$port/live/$user/$pass/$id.$ext"

    fun episode(server: String, port: String, user: String, pass: String, id: Int, ext: String) =
        "http://$server:$port/series/$user/$pass/$id.$ext"
}
```

## Player

Media3 `ExoPlayer` handles `.mkv`, `.mp4`, `.ts` natively via progressive
download — these are direct file URLs, not HLS manifests, for movie/series/
live on this panel. Wrap in a `PlayerView`; use a `LeanbackPlayerAdapter` for
D-pad-controllable playback controls unless heavy custom UI is needed, in
which case a plain `PlayerActivity` with custom controls is more flexible.

## Build sequencing

1. **Auth screen** — store credentials in `EncryptedSharedPreferences`,
   validate via a plain `player_api.php` call (check `auth: 1` and
   `status: "Active"` in the response).
2. **Movies vertical slice** — categories (Room-cached) → VOD list → tap →
   play. This is the reference implementation every other content type
   copies.
3. **Live** — same pattern, reuse category/list UI with a content-type
   parameter.
4. **Series** — category → shows (Room-cached) → tap → `get_series_info`
   (fetched on-demand, cached after first fetch) → season/episode picker →
   play.

## Explicitly out of scope for the POC

- LG webOS build (different app model entirely — web-based, Luna SDK,
  `ares-cli` packaging; a separate project when the Android POC is proven).
- EPG / program guide.
- Timeshift/catch-up playback for archived channels.
- HTTPS streaming path — use the HTTP port until TLS is verified on the target panel.
