# Provider field report (redacted, counts only)

Collected 2026-10-21 by a read-only probe: one listing/info request at a time, no streams. No credentials, hosts, URLs or titles recorded.

## File size
- Movies list: 49,043 items, 0 with any size-like field (size, filesize, file_size, size_bytes).
- get_vod_info (5 sampled movies): fields include bitrate, duration, duration_secs, audio, video; no size field.
- get_series_info (3 sampled series, 69 episodes): episode info has bitrate, duration, duration_secs, audio, video; no size field.
- Conclusion: listings and info contain no file sizes; opened media uses HEAD Content-Length, then a ranged GET Content-Range fallback, while plausible bitrate and duration provide approximate sizes.

## Artwork
- Movies: 49,043 items; stream_icon http 47,954, https 477, empty 612 (1.2%).
- Series: 14,276 items; cover http 8,714, https 4,537, empty 1,024 (7.2%), one value containing a space.
- Series backdrop_path is an array or null; a usable first entry on about 6,100 items.
- Conclusion: almost all artwork is delivered as absolute URLs. Items with no image are the provider sending an empty value (about 1.2% of movies, 7.2% of series). The InvalidArtworkUri log event fires when the stored URL is empty, so it reflects those provider gaps, not a parsing bug. Only one series cover has an illegal space.
