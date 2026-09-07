# Xtream Codes API Reference

All endpoints below were verified live against the target panel. Field names
and URL patterns are exact — this is not the generic Xtream spec, it's what
this specific panel actually returns.

## Base configuration

The panel host, ports, username, and password are **entered by the user at
runtime** and stored encrypted via **DataStore + Tink** (see
`docs/architecture.md`). No provider, host, or port is hardcoded anywhere in
the app — the same build must work against any Xtream Codes panel the user
points it at.

```
Server:      user-entered hostname
HTTP port:   user-entered; frequently non-standard, so never default to 80/8080
HTTPS port:  reported back in `server_info`; cert validity varies by panel,
             verify before trusting it
RTMP port:   reported back in `server_info`; unused by this app
```

Store server + port + username + password together as one credential set.
Read the ports from user input or from the `server_info` block of the auth
response — never assume a default.

## Authentication / account check

```
GET http://{server}:{port}/player_api.php?username={user}&password={pass}
```

Returns account status and server info. Example shape:

```json
{
  "user_info": {
    "username": "",
    "password": "",
    "auth": 1,
    "status": "Active",
    "exp_date": "1808067679",
    "is_trial": "0",
    "active_cons": "0",
    "created_at": "1771434079",
    "max_connections": "1",
    "allowed_output_formats": ["m3u8", "ts", "rtmp"]
  },
  "server_info": {
    "url": "panel.example.com",
    "port": "8080",
    "https_port": "8443",
    "server_protocol": "http",
    "rtmp_port": "1935",
    "timezone": "UTC",
    "timestamp_now": 1788746447,
    "time_now": "2026-09-07 02:00:47"
  }
}
```

**Important:** `max_connections: "1"` — only one stream plays at a time
across every device/app using this account. Relevant when testing
side-by-side with an existing IPTV app.

## Content type 1 — Live TV

### List categories
```
GET .../player_api.php?username={u}&password={p}&action=get_live_categories
```
Returns `[{ "category_id": "823", "category_name": "...", "parent_id": 0 }, ...]`

### List channels in a category
```
GET .../player_api.php?username={u}&password={p}&action=get_live_streams&category_id={id}
```
Example item:
```json
{
  "added": "1782389941",
  "category_id": "823",
  "custom_sid": "",
  "direct_source": "",
  "epg_channel_id": "",
  "ext": "ts",
  "name": "تعليمات هامة",
  "num": 3,
  "stream_icon": "http://images.example.com/images/1721054693479.jpg",
  "stream_id": 357057,
  "stream_type": "live",
  "tv_archive": 0,
  "tv_archive_duration": 0
}
```

### Playback URL
```
http://{server}:{port}/live/{user}/{pass}/{stream_id}.{ext}
```
`tv_archive: 0` means no catch-up/replay for this channel. If a channel has
`tv_archive: 1`, a separate timeshift API exists but is out of scope for now.

## Content type 2 — Movies (VOD)

### List categories
```
GET .../player_api.php?username={u}&password={p}&action=get_vod_categories
```
Same shape as live categories. ~120 categories on this panel (language/era/
genre/studio groupings — don't build a curated taxonomy for the POC, just
render the list as-is).

### List movies in a category
```
GET .../player_api.php?username={u}&password={p}&action=get_vod_streams&category_id={id}
```
Example item:
```json
{
  "added": "1768511625",
  "category_id": "898",
  "container_extension": "mkv",
  "custom_sid": null,
  "direct_source": "",
  "name": "وقت إضافي (2026)",
  "num": 33,
  "rating": 0,
  "rating_5based": "0",
  "stream_icon": "http://images.example.com/images/1768511665129.jpg",
  "stream_id": 505439,
  "stream_type": "movie"
}
```

### Playback URL
```
http://{server}:{port}/movie/{user}/{pass}/{stream_id}.{container_extension}
```

Notes:
- `stream_icon` is often hosted on a different domain and port than
  the API/streaming server. Use as-is, don't rewrite.
- `direct_source` is typically empty — always construct the URL yourself.
- Titles are frequently Arabic (RTL) or mixed Arabic/English — verify text
  rendering handles this (Android handles Unicode RTL by default, but test).
- `rating` / `rating_5based` are frequently `0` — don't assume populated.

## Content type 3 — Series

Series has one more layer than movies/live: category → show → season/episode
list → play. The action names break the `get_X_streams` naming pattern used
by live/VOD.

### List categories
```
GET .../player_api.php?username={u}&password={p}&action=get_series_categories
```

### List shows in a category
```
GET .../player_api.php?username={u}&password={p}&action=get_series&category_id={id}
```
Note: action is `get_series`, **not** `get_series_streams`. Returns show
metadata only — no episodes, no playable stream yet:
```json
{
  "backdrop_path": [],
  "cast": "",
  "category_id": 770,
  "cover": "http://images.example.com/images/1781463543334.jpg",
  "director": "",
  "episode_run_time": "0",
  "genre": "",
  "last_modified": "1788601549",
  "name": "HD  للعدالة وجه آخر",
  "num": 1,
  "plot": "...",
  "rating": 0,
  "rating_5based": 0,
  "releaseDate": "",
  "series_id": 10840,
  "youtube_trailer": ""
}
```

### Get seasons + episodes for a show
```
GET .../player_api.php?username={u}&password={p}&action=get_series_info&series_id={id}
```
Returns `seasons` (metadata), `info` (show metadata, duplicate of the above),
and `episodes` — **an object keyed by season number as a string**, each value
an array of episode objects:
```json
{
  "seasons": [
    { "id": 1, "name": "Season 1", "episode_count": 15, "season_number": 1, "cover": "..." }
  ],
  "info": { "name": "...", "cover": "...", "plot": "...", "category_id": "770" },
  "episodes": {
    "1": [
      {
        "id": "533032",
        "episode_num": 1,
        "title": "HD  للعدالة وجه آخر - S01E01",
        "container_extension": "mkv",
        "season": 1,
        "duration": "00:40:37",
        "duration_secs": 2437,
        "added": "1781562565"
      }
    ]
  }
}
```
The nested `info` block inside each episode (codec, bitrate, resolution,
audio language, etc.) is transcoding metadata — safe to ignore except
`duration` / `duration_secs` if displaying runtime.

### Playback URL
```
http://{server}:{port}/series/{user}/{pass}/{episode_id}.{container_extension}
```

**Do not fetch `get_series_info` for every show upfront.** Fetch and cache it
lazily, only when the user opens a specific show — fetching full episode
payloads for every series in a category up front is wasteful (each response
is large, per the sample above).

## Playback URL pattern summary

| Content type | List action(s) | Playback URL pattern |
|---|---|---|
| Live | `get_live_categories` → `get_live_streams` | `/live/{user}/{pass}/{stream_id}.{ext}` |
| Movies | `get_vod_categories` → `get_vod_streams` | `/movie/{user}/{pass}/{stream_id}.{container_extension}` |
| Series | `get_series_categories` → `get_series` → `get_series_info` | `/series/{user}/{pass}/{episode_id}.{container_extension}` |

## Open items / not yet verified

- HTTPS port behavior — cert validity, whether it works at all.
- Timeshift/catch-up API for channels with `tv_archive: 1` (none observed
  yet in this panel's sample data).
- EPG (program guide) — separate XMLTV endpoint, not yet investigated.
