# Diagnostics for support

In Account settings or on the connection screen, select **Save diagnostics for support**. Tvivo writes one ZIP to the Desktop and selects it in Explorer. The ZIP contains the app version and OS version, online/proxy/IPv4/IPv6 presence, database schema version and per-type item counts, up to 500 sanitized app log lines across rotations, and up to 100 catalog request outcomes. It stays below 2 MB.

Exported log lines contain only known diagnostic fields such as UTC time, session, trace ID, operation, stage, attempt, outcome, HTTP code, elapsed time, exception type, and retry time. Free-form messages, names, titles, account details, addresses, URLs, paths, and raw exception messages are dropped. The export does not contain the database or stored credentials.

## Two-minute collection on older builds

1. Reproduce the issue once, then press **Win+R** and open `%LOCALAPPDATA%\Tvivo` (or the configured `TVIVO_DATA_ROOT`). Copy `tvivo-launch.log` and `tvivo-launch.log.1` to the Desktop.
2. Press **Win+R**, open `%TEMP%`, and copy `tvivo-playback-engine.log` and `.log.1` if present.
3. Inspect the copied text for account details, addresses, or URLs. Remove sensitive lines, compress the copies to one ZIP, and send it privately. Do not include the SQLite database or `provider-connection.bin`.

Older logs may not contain per-request outcomes unless network logging was enabled. The playback log gives context only; it does not prove a catalog failure.
