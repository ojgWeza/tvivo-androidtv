# Playback interruption verification

Use a local loopback HTTP server serving a sufficiently large video file with byte ranges enabled.
Start playback, let the server deliver data for several seconds, then drop that client connection
once. Keep the server available so the player's reconnect can request a range from the same file.

Verify that playback resumes near the last forward position, progress advances again, and the
player does not show a Retry prompt while recovery succeeds. For diagnostic confirmation, inspect
the local playback diagnostics for `playback.interrupt`, `playback.resume attempt=…`, and
`playback.resume.ok`; those events contain positions and attempt counts only. Repeat with a short
file to confirm a genuine end does not reconnect, and with a server response of HTTP 403 to confirm
recovery stops immediately and exposes Retry.

Keep the server bound to loopback. This check needs no network or firewall setting changes, and
must not log media titles, request URLs, or credentials.
