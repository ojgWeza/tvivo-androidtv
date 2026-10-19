# Handoff for the WinUI session: series/refresh failure on the last MSI (from the Android session, 2026-10-19)
Android session will NOT touch D:\HCode\Tvivo-windows. This is yours.

## Report (friend testing the last MSI)
Suddenly, for no apparent reason, Series stop working although the same provider's series resolve elsewhere. Two messages:
1. "Couldn't load episodes. Try again." (windows-spike/src/Tvivo.App/MainWindow.xaml.cs ShowSeriesOpenMessage ~1822, 1829, 1858, 1914, 2103)
2. "Couldn't refresh — showing saved items" + RETRY (Pages/CatalogLandingPage.xaml.cs ~4782). See windows-spike/HANDOFF-fix-batch-3.md item 10: earlier refresh-error work; the saved app log then had no refresh error.

## Owner's requirements
- Find and FIX the real cause now, from code and reproduction, before any diagnostics arrive from the tester. Messages are not a fix.
- Make future errors cheap to diagnose: structured, redacted logs with a per-attempt trace id (endpoint kind, HTTP status, elapsed, exception type/stage), never log credentials or full stream URLs.
- One-click "Save diagnostics for support" in SETTINGS (also reachable from login/account screens, since the tester may be unable to reach the player): writes one redacted zip/txt to the Desktop and opens Explorer on it. Include app version/build, OS, network summary, last ~500 redacted log lines, last request outcomes per catalog operation, DB schema version/counts.
- Until it ships: give the tester a 2-minute instruction to collect existing logs (TvivoDataPaths: tvivo-launch.log and .1, the playback engine log).

## Hypotheses to rank (verify in code, name the log line that proves each)
provider throttling/429/403 or "max connections"; a failed task/response cached or remembered after failure (never cache failures); backoff memory/lockout; expired session/credentials; HttpClient timeout/cancellation; get_series / get_series_info JSON shape drift (nullable/array-vs-object fields) throwing in the parser; SQLite lock or schema/migration issue (schema 12); DNS/IPv6/proxy; retry logic giving up silently.
Make the parser tolerant (missing/odd fields must degrade, not throw), add bounded retry with backoff+jitter, keep saved data with a clear status.

## Android-side reference (reuse ideas, not code paths)
app/.../diagnostics/NetworkEventStore.kt (ring buffer, redaction, account-partitioned), NetworkCheckRunner.kt, shared-core diagnostics/NetworkDiagnosis.kt; Android Settings > Network diagnostics > export. A sol read-only investigation may also appear at docs/winui-series-refresh-failure-investigation-2026-10-19.md (written from the Android repo; read it, then verify).
