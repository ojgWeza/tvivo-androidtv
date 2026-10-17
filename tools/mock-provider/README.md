# Offline Xtream mock provider

This dependency-free Node server binds to `0.0.0.0:8089` and exposes a synthetic Xtream catalog plus generated media. It does not host or fetch content. Credentials default to `demo` / `demo`.

## Generate and run

From the repository root:

```sh
node tools/mock-provider/generate-media.mjs
node tools/mock-provider/server.mjs
```

The generator requires `ffmpeg` with `libx264`, AAC, and `mov_text` support. It writes four two-minute clips to the ignored `tools/mock-provider/media/`: a movie, two series episodes, and a looping-style MPEG-TS live clip. Clips use test patterns and sine audio, with 440 Hz English and 880 Hz Arabic tracks and an embedded subtitle (also kept as `subtitles.srt`). Generated files are local and are not committed.

## Sign in from Android emulator

1. Start the server on the same host that runs the Android emulator.
2. In Tvivo, add a source with server `http://10.0.2.2`, port `8089`, username `demo`, and password `demo`.
3. Confirm/save the source and sync the catalog. On a physical device, use the host computer's LAN IP instead of `10.0.2.2`.

The API implements auth, live/VOD/series categories and lists, VOD info, series info, and `/live|movie|series/<user>/<pass>/<id>.<ext>` playback URLs. Stream endpoints support byte ranges.

## Controls

The unauthenticated `/__ctl` admin endpoint accepts a JSON POST; GET returns current state. Keep this demo server on a trusted network. Example:

```sh
curl -X POST http://127.0.0.1:8089/__ctl -H "content-type: application/json" -d '{"maxConnections":2,"stallAfterBytes":262144,"stallSeconds":8,"eofPercent":40,"catalog429":3,"killSwitch":false,"rejectSecondStatus":403}'
```

Settings: `maxConnections` (default 1), `rejectSecondStatus` (default 403), `stallAfterBytes` + `stallSeconds`, `eofPercent` (closes the response at that percent), `catalog429` (number of catalog calls to throttle), and `killSwitch` (returns 503 for all non-admin requests). Stream URLs may override stalls/EOF with `?stallAfterBytes=...&stallSeconds=...&eofPercent=...`. Environment equivalents are `PORT`, `MOCK_HOST`, `MEDIA_DIR`, `DEMO_USERNAME`, `DEMO_PASSWORD`, `MAX_CONNECTIONS`, `REJECT_SECOND_STATUS`, `STALL_AFTER_BYTES`, `STALL_SECONDS`, `EOF_PERCENT`, `CATALOG_429`, and `KILL_SWITCH`.

Run the API/stream transport self-test with `node tools/mock-provider/selftest.mjs`. It uses temporary byte fixtures for protocol checks; generated audio/video is exercised only by launching the app against the generated media.
