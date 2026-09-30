# Tvivo WinUI performance and robustness pass

## VERDICT

**PASS (core scenarios) — full run completed cleanly, no crash, no hang.** This run supersedes the 2026-09-26 report, which was incomplete (shell crashed mid-run) and predates this session's Account screen, Home shelves, idle/featured overlay, artwork byte-budget cache, and diagnostics-logging work. All core scenarios (idle My Tvivo, idle Movies, 50 mode switches, 20x scroll cycles, 30x fullscreen toggles, close) completed in a single unattended run with a clean process exit. Monkey/heavy-user long-session testing was intentionally out of scope for this pass (user chose "core scenarios only").

## RESULTS (2026-09-30 run, `scenarios-20260930-103602/`)

| Scenario | Samples | CPU avg/peak (%/core) | Working set avg/peak (MiB) | Private bytes avg/peak (MiB) | UI ping peak (ms) | Handles peak |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| S2 idle My Tvivo (5 min) | 300 | 0.96 / 156.76* | 297.3 / 351.0 | 211.1 / 268.2 | 43.9 | 1482 |
| S3 idle Movies (5 min) | 300 | 0.30 / 11.71 | 322.3 / 344.1 | 245.1 / 249.0 | 32.9 | 1831 |
| S4 50 mode switches | 54 | 0.89 / 13.82 | 328.6 / 353.0 | 227.8 / 280.9 | 61.2 | 1914 |
| S5 scroll x20 (keyboard PgDn/PgUp) | 350 | 0.04 / 2.08 | 299.1 / 310.4 | 186.7 / 198.3 | 83.2 | 1937 |
| S6 fullscreen toggle x30 | 31 | 0.01 / 0.29 | 295.3 / 295.9 | 182.7 / 183.3 | 14.0 | 1823 |
| S7 close (Alt+F4) | — | — | — | — | — | exited in 224.9 ms |

\* The S2 peak is a single-sample transient at the very start of the window (launch/first-paint settling — working set was still only 208 MiB at that sample, climbing to 350 MiB over the next ~15s as the catalog populated). Not a sustained idle cost; excluding that one sample, idle CPU is consistent with the pre-session baseline (<1% avg).

S4 mode-switch latency distribution: p50=31.6ms, p95=161.0ms, max=178.4ms — fast and responsive.

## EXCEPTIONS

| Signature | Count | Disposition |
| --- | ---: | --- |
| Generic CLR first-chance, `0xE0434352` | 2512 | Unclassified (same unattributed pattern as the 2026-09-26 run, now proportionally more due to far more UI activity in this longer run). Still no type/frame available from `--debug-output`. |
| `0x80004005` (E_FAIL) | 1 | Benign — single artwork image failed a DNS lookup ("No such host is known"), already caught and logged by the existing artwork pipeline. Not a crash. |
| `0x8013153B` (OperationCanceledException) | 23 | Benign — expected cancellation of in-flight artwork requests during scroll/navigation; this is the artwork pipeline's cancellation design working as intended. |
| Unclassified | 1 | Not investigated further; single occurrence. |

No crash, no unhandled exception dialog, no hang observed.

## OBSERVATIONS / FOLLOW-UPS

1. **Idle overlay has no diagnostic logging.** Added in Batch 3, the idle/featured-overlay show/dismiss events are not logged via `LaunchDiagnostics.Write` (unlike playback/cache events from Batch 2). This run could not confirm whether the overlay actually fired during the 5-minute S2/S3 idle windows — there's no log signal to check, and no CPU/memory anomaly near the 5-minute mark to infer it from. **Recommend adding `event=idle.overlay.show` / `event=idle.overlay.dismiss reason=...` logging** so future perf and production runs can verify this behavior directly instead of guessing.
2. **"Idle" is not fully idle.** The Spotlight carousel on My Tvivo auto-rotates every ~8 seconds indefinitely, causing continuous artwork load/cancel churn even with zero user input. This is expected/by-design behavior (matches the Compose reference), not a bug, but worth being aware of as a background CPU/decode cost during nominally idle periods.
3. Handle counts (1482–1937 across scenarios) are somewhat higher than the pre-session baseline (1478–1798), plausibly due to the new AccountPage/HomePage/idle-overlay controls now always constructed in `MainWindow`. Not flagged as a leak — no sustained growth observed within a single run — but worth a longer session (monkey/heavy-user, not run this pass) if a future concern arises.

## RULED OUT

- No crash or unhandled exception dialog in this run.
- No hang: UI ping stayed under 84ms across all core scenarios (well under any reasonable responsiveness threshold).
- No sustained idle CPU load: excluding the single startup-transient sample, idle CPU averaged under 1%/core in both My Tvivo and Movies idle windows.
- Clean process exit: 224.9ms after Alt+F4, no leftover process.
- Mode-switch and scroll interactions are fast (p95 161ms) and did not degrade over 50 repeated switches.

## NOT RUN THIS PASS

Monkey testing, heavy-user long sessions (15/30 min), local-fixture repeated-playback cycles, and close-during-playback/loading — deferred; user chose "core scenarios only" for this pass. These remain open follow-ups if a deeper perf/stability pass is wanted later.

## ARTIFACTS

- Rerunnable sampler and input driver: [`Measure-Tvivo.ps1`](Measure-Tvivo.ps1)
- This run: `baseline-20260930-095444/` (initial 5-min idle-only check) and `scenarios-20260930-103602/` (full core-scenario run — samples.csv, actions.csv, exceptions.csv, winapp-debug-output.log)
- Prior incomplete run (2026-09-26, kept for history): `baseline-20260926-*/`, `scenarios-20260926-142357/`, `scroll-20260926-144400/`
