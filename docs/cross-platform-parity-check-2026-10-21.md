# Cross-platform parity check — 2026-10-21

Read-only WinUI evidence came from `git log codex/winui-catalog-design-plan`, that branch's commit subjects, `docs/winui-catalog-design-plan.md`, the series-refresh investigation/handoff, `docs/decisions.md`, and `vault/`. Android evidence was checked in `app/` and `shared-core/` with targeted source/test searches. `HAS` means an implementation is visible in source; it does not imply device acceptance. `PARTIAL` means related behavior exists, but scope or evidence differs. `N-A` means the row does not apply to that platform.

| ID | Type | Origin | What | Android status | Evidence | Action |
| --- | --- | --- | --- | --- | --- | --- |
| W-REFRESH-CACHE | fix | WinUI | Show cached catalog immediately; keep it usable on refresh failure | HAS | `app/src/main/java/com/dev/Tvivo/data/repository/CachedFetch.kt`; `app/src/test/java/com/dev/Tvivo/data/repository/CachedFetchTest.kt` | Keep behavior; ensure future refresh paths retain cache |
| W-REFRESH-COALESCE | fix | WinUI | Coalesce refreshes, bound retries, reject stale completions | PARTIAL | `app/src/main/java/com/dev/Tvivo/data/remote/`; `CatalogSyncerTest.kt` | Audit account-switch generations and retry caps across all content types |
| W-DIAG-CLASSIFY | fix | WinUI | Typed catalog failures and tolerant parsing | PARTIAL | `app/src/main/java/com/dev/Tvivo/ui/common/ErrorCopy.kt`; `XtreamFixtureContractTest.kt` | Align provider, protocol, auth, and database failure taxonomy |
| W-DIAG-TRACE | fix | WinUI | Trace IDs and request/stage timing diagnostics | HAS | `app/src/main/java/com/dev/Tvivo/diagnostics/DiagnosticLog.kt`; `RuntimeMetricsTest.kt` | Preserve bounded structured traces |
| W-DIAG-EXPORT | ux-perk | WinUI | Export support diagnostics | HAS | `app/src/main/java/com/dev/Tvivo/ui/settings/DiagnosticsScreen.kt`; `DiagnosticLogTest.kt` | Keep export easy to find |
| W-STARTUP-OFF-UI | fix | WinUI | Move shelf/snapshot work off UI thread and coalesce startup | PARTIAL | `app/src/main/java/com/dev/Tvivo/ui/home/`; `MyTvivoHomeTest.kt` | Profile cold start and coalesce duplicate reads |
| W-SEARCH-ACTIVATE | fix | WinUI | Require explicit activation before search playback | PARTIAL | `app/src/main/java/com/dev/Tvivo/ui/`; `BrowseQueryRouteTest.kt` | Verify focus/selection cannot launch playback implicitly |
| W-IDLE-TIMERS | fix | WinUI | Stop idle timers when their owner closes | HAS | `shared-core/src/main/kotlin/com/dev/Tvivo/playback/SleepTimerPolicy.kt`; `SleepTimerPolicyTest.kt` | Maintain lifecycle ownership tests |
| W-SPOTLIGHT | ux-perk | WinUI | Curated Spotlight with stable selection, focus, accessible controls | PARTIAL | `app/src/main/java/com/dev/Tvivo/ui/home/`; `MyTvivoHomeTest.kt` | Compare Android home discovery and selection affordances |
| W-SPOTLIGHT-ART | ux-perk | WinUI | Series artwork inheritance and bounded artwork warming | PARTIAL | `app/src/main/java/com/dev/Tvivo/ui/common/TvivoImageLoader.kt` | Check warm-up limits and series fallback parity |
| W-SERIES-ART-REFRESH | fix | WinUI | Refresh series artwork once; episodes inherit artwork | PARTIAL | `app/src/main/java/com/dev/Tvivo/data/`; `docs/decisions.md` | Verify episode artwork remains coherent after refresh |
| W-SPOTLIGHT-SETTINGS | ux-perk | WinUI | Per-content Spotlight sources and empty-folder selection | PARTIAL | `app/src/main/java/com/dev/Tvivo/ui/settings/` | Assess settings parity and empty-source usability |
| W-SPOTLIGHT-ROTATION | ux-perk | WinUI | Readable Spotlight loading, focus, origin, manual rotation | PARTIAL | `app/src/main/java/com/dev/Tvivo/ui/home/` | Audit announcements and remote focus paths |
| W-PLAYER-FAST-OPEN | ux-perk | WinUI | Render player surface immediately; resolve media asynchronously | PARTIAL | `app/src/main/java/com/dev/Tvivo/ui/player/PlayerActivity.kt` | Measure tap-to-player and first-frame experience |
| W-FILE-SIZE | ux-perk | WinUI | Label file sizes; bounded missing-size rechecks and coverage diagnostics | MISSING | `app/src/main/java/com/dev/Tvivo/ui/` search; no browse size policy found | Consider file size in detail/episode browsing where data supports it |
| W-PAGE-CACHE | lesson | WinUI | Revision-keyed rendered-page LRU for instant tab returns | PARTIAL | `app/src/main/java/com/dev/Tvivo/ui/`; `CatalogSnapshot` cache references in `docs/decisions.md` | Measure tab-return latency before adding a bounded render cache |
| W-DECODE-SLOTS | lesson | WinUI | Bound concurrent artwork decode slots | PARTIAL | `app/src/main/java/com/dev/Tvivo/ui/common/TvivoImageLoader.kt` | Compare decode concurrency and memory limits |
| W-CLOSE-BUDGET | lesson | WinUI | Bound teardown and cancel owner-scoped catalog work | PARTIAL | `app/src/main/java/com/dev/Tvivo/ui/player/PlayerActivity.kt`; `PlaybackSessionPolicyTest.kt` | Keep teardown bounded; verify each async owner cancels |
| W-STOP-ICON | ux-perk | WinUI | Make mini-player close control explicitly mean Stop | HAS | `app/src/main/java/com/dev/Tvivo/ui/player/PlayerActivity.kt`; `PipPlaybackActionTest.kt` | Preserve clear stop semantics |
| W-THEME-BOUNDS | lesson | WinUI | State theme/contrast support limits honestly | PARTIAL | `shared-core/src/test/kotlin/com/dev/Tvivo/ui/ThemeModeTest.kt`; `docs/decisions.md` | Document supported contrast/accessibility behavior |
| A-UNKNOWN-END | lesson | Android | Unknown-length playback uses elapsed-time fallback and avoids false completion | N-A | `shared-core/src/main/kotlin/com/dev/Tvivo/playback/EpisodeCompletion.kt`; `PlaybackEndPolicyTest.kt` | WinUI should port this edge-case policy |
| A-PLAYBACK-ERROR-COPY | lesson | Android | Playback errors explain recovery and expose actionable retry | PARTIAL | `app/src/main/java/com/dev/Tvivo/ui/common/ErrorCopy.kt`; `PlayerOverlay.kt` | Make WinUI failures stage-specific and actionable |
| A-DIAG-PRIVACY | lesson | Android | Scrub host, username, password, URLs, and titles before export | PARTIAL | `app/src/main/java/com/dev/Tvivo/diagnostics/DiagnosticLog.kt`; `docs/decisions.md` | Port the full privacy filter and test raw/encoded secrets |
| A-PIP-STOP | lesson | Android | PiP actions and stop-token/session ownership have explicit lifecycle rules | PARTIAL | `app/src/main/java/com/dev/Tvivo/ui/player/PipPolicy.kt`; `PlaybackCommands.kt` | Apply explicit stop ownership to WinUI mini-player dismissal |

## Android should add

1. **P0 —** Complete refresh coalescing/stale-result protections across account switches and content types; align typed failure categories and preserve cached series on malformed/empty refreshes.
2. **P1 —** Add labelled file-size visibility and bounded rechecks where provider metadata supports it; measure catalog tab-return latency and consider a small revision-keyed page cache if needed.
3. **P1 —** Audit artwork decode concurrency, image warm-up limits, and async teardown ownership against the WinUI lessons.
4. **P2 —** Compare home Spotlight settings/source selection, loading announcements, rotation, origin labels, and series-art inheritance; close only the meaningful UX gaps.
5. **P2 —** Clarify supported contrast behavior and document any intentional theme limitations.

## Android-originated lessons WinUI lacks or only partly reflects

- Unknown-duration playback needs an elapsed-time fallback and must not infer completion from an unknown duration (`A-UNKNOWN-END`).
- Playback failure copy should explain the likely recovery and keep a direct Retry action (`A-PLAYBACK-ERROR-COPY`).
- Diagnostic exports need a privacy filter for host, username, password (raw and URL-encoded), URLs, and catalogue titles (`A-DIAG-PRIVACY`).
- PiP/miniplayer close and Stop need explicit token/session ownership so dismissal cannot leave playback alive (`A-PIP-STOP`).

## Ledger

The seeded machine-readable entries live in [`vault/parity/ledger.json`](../vault/parity/ledger.json). Run `node tools/parity/parity.mjs check` to validate and list gaps, or `node tools/parity/parity.mjs report` for a platform summary.
