# Desktop playback fixtures

These are short, seekable excerpts derived from the Sintel trailer published by
the Durian Open Movie Project. The source is the official 480p trailer download:

<http://download.blender.org/durian/trailer/sintel_trailer-480p.mp4>

The Durian project content is released under **Creative Commons Attribution 3.0
(CC BY 3.0)**. Attribution: **© copyright Blender Foundation |
durian.blender.org**. See the project's [sharing terms](https://durian.blender.org/sharing/)
and [download page](https://durian.blender.org/download/).

The downloaded source is H.264/AAC, 854×480, 52.208333 seconds, SHA-256
`b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254`.
It has no subtitle stream. The MKV therefore includes a clearly labeled,
project-authored one-line SRT caption solely to exercise embedded-subtitle
handling; no source subtitle text was altered or represented as original film
dialogue.

## Files

| File | Purpose |
| --- | --- |
| `sintel-trailer-12s-subtitles.mkv` | Seekable H.264/AAC Matroska excerpt with an embedded English SRT test caption. |
| `sintel-trailer-12s.ts` | Seekable H.264/AAC MPEG-TS excerpt. The extension alone must not be treated as non-seekable. |

## Reproduction

Commands below use FFmpeg 9.0.1. Substitute the full executable path if it is
not on `PATH`.

```powershell
$ffmpeg = 'C:\Users\Dell\AppData\Local\Microsoft\WinGet\Links\ffmpeg.exe'
$source = 'sintel_trailer-480p.mp4'
$caption = 'sintel-trailer-fixture.srt'

& $ffmpeg -y -ss 0 -i $source -f srt -i $caption -t 12 `
  -map 0:v:0 -map 0:a:0 -map 1:0 `
  -c:v libx264 -preset medium -crf 23 -pix_fmt yuv420p `
  -c:a aac -b:a 128k -c:s srt -metadata:s:s:0 language=eng `
  -metadata:s:s:0 title='English fixture caption' `
  -metadata title='Sintel trailer seekable MKV fixture' `
  -metadata artist='Durian Open Movie Team' `
  -metadata copyright='(c) copyright Blender Foundation | durian.blender.org' `
  -metadata description='12-second excerpt derived from the Sintel trailer; project-authored caption track for subtitle testing' `
  sintel-trailer-12s-subtitles.mkv

& $ffmpeg -y -ss 0 -i $source -t 12 `
  -map 0:v:0 -map 0:a:0 `
  -c:v libx264 -preset medium -crf 23 -pix_fmt yuv420p `
  -c:a aac -b:a 128k -mpegts_flags resend_headers -muxdelay 0 -muxpreload 0 `
  -metadata title='Sintel trailer seekable MPEG-TS fixture' `
  -metadata artist='Durian Open Movie Team' `
  -metadata copyright='(c) copyright Blender Foundation | durian.blender.org' `
  -metadata description='12-second excerpt derived from the Sintel trailer' `
  sintel-trailer-12s.ts
```

The source and caption are build inputs only; they are not committed. Hashes,
sizes, stream details, and durations for the committed outputs are recorded in
the D-Desktop-21 plan's section 0.4 table.

## Live-style relay

After explicit approval for a local manual playback run, start the fixture-only
loopback relay with:

```powershell
./gradlew :desktop:fixtureLiveRelay
```

It binds only to `127.0.0.1`, serves the TS bytes with chunked transfer and no
`Content-Length`, rejects `Range`, and repeats the file with a delay. Stop it
with Ctrl+C after the run. It uses no provider data and is not a production
server.
