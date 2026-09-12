## N-8 — One-click diagnostic reporting from the TV
**Cost: medium. Depends on N-1 and the existing `DiagnosticLog`.**

A file export is not useful on a TV: the user is unlikely to have a file manager,
email client or practical way to move the artifact elsewhere. Replace it with a
remote-first **Report a problem** action in Diagnostics:

1. Show a short consent screen stating exactly what will be sent, with default
   focus on **Cancel**.
2. On confirmation, send a strictly allowlisted report over HTTPS.
3. Show a short reference such as `TV-4K7M2` when accepted; require no keyboard,
   copying or second device.
4. If offline, retain the pending report and retry through WorkManager, while
   making the queued state visible to the user.

The app should send to a small app-owned reporting endpoint. That service may
email the developer initially and may later create a sanitized GitHub issue.
**Never create issues directly from the APK:** a GitHub token or mail-provider
credential embedded in a public Android package can be extracted. The repository
is public, so detailed diagnostics must never be posted to an issue; at most the
service posts a coarse summary plus the private report reference.

Build the payload from an explicit allowlist rather than collecting broadly and
redacting afterward. Useful fields: app/build version, Android API and TV model,
timestamp, current screen, normalized failure category, recent redacted
diagnostic events, and coarse cached/partial/sync/playback outcomes. Never send
credentials, panel hostname or port, request/playback URLs, account or content
ids, titles, category names, search text, exact resume positions, or raw
exception messages. Use normal certificate validation, payload and retention
limits, server-side rate limiting, and a visible **Delete local diagnostics**
action. Document the data sent before enabling the feature.

