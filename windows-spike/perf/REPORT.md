# Tvivo WinUI performance and robustness pass

## VERDICT

**INCOMPLETE — baseline evidence captured; no source fix or after run.** The app launched and stayed responsive through the measured pre-change scenarios. Idle CPU was low, but 50 mode changes raised private memory and handle counts substantially. The initial `winapp run` debug output contained 173 generic first-chance CLR exception notices. It did not identify exception types or throw frames. The shell stopped initializing after the corrected wheel-input run (`pwsh` exit `0xC0000142`), before the wheel trace could be inspected or the app process could be confirmed stopped. No source files were changed.

## STRONGEST EVIDENCE

| Scenario / metric | Pre-change observation | Boundary |
| --- | ---: | --- |
| S1 cold launch | `winapp run` reached a process in 25.1 s including its build; launch diagnostics show `MainWindow` construction to activation in 0.323 s | First cold UIA round-trip was not captured; invocation time includes build/CLI startup |
| S2 My Tvivo idle, 5 min | 300 samples; CPU avg 0.09% / core, peak 1.25%; working set peak 257 MiB; private bytes peak 194 MiB; GPU utilization peak 0%; UI ping peak 555 ms | CSV has one-second cadence |
| S3 Movies idle, 5 min | 298 samples; CPU avg 0.49% / core, peak 24.11%; working set peak 417 MiB; private bytes peak 332 MiB; GPU peak 3%; UI ping peak 774 ms | Includes mode-entry/cache/artwork settling near interval start |
| S4 50 mode switches | 52 samples; CPU avg 0.36%, peak 9.3%; working set peak 433 MiB; private bytes peak 345 MiB; GPU peak 7%; UI ping peak 175 ms | Per-action timings are in `scenarios-20260926-142357/actions.csv`; full distribution was not calculated before the shell failure |
| S5 initial keyboard scroll attempt | 330 samples; max UI ping 466 ms | **Invalid as a scroll measurement:** PageDown/PageUp was sent without confirming the content moved |
| S5 corrected wheel run | A second run completed and created `scroll-20260926-144400/` | Artifacts and exit state could not be inspected after shell failure |
| S6 30 F11 toggles | 31 samples; peak UI ping 7.7 ms; app stayed responsive during sampled toggles | No DPI/multiple-monitor validation |
| S7 close | Alt+F4 to process exit: 236 ms | Idle close only; close during loading/playback not run |
| GC heap / Gen 0/1/2 | Not obtainable | The installed `.NET CLR Memory` perf-counter provider had no Tvivo.App instance; `dotnet-counters`, `dotnet-gcdump`, and `dotnet-trace` were not available on PATH |
| Dedicated/shared GPU memory | Dedicated reported 0 MiB; shared was about 70 MiB at idle and reached about 177 MiB during/after mode changes | GPU process-memory counters expose shared allocation; driver-reported dedicated allocation was 0 in these samples |
| First-chance exceptions | 173 generic `CLR Exception (0xE0434352)` notices in the raw `winapp --debug-output` log | No type, throw frame, or reliable event timestamps were emitted |

After mode switching, the sampled process reached 345 MiB private bytes and 1,798 handles versus the idle baseline of about 194 MiB and 1,478 handles. After the first attempted scroll sequence it settled near 303 MiB private bytes, 1,796 handles, and 40 threads. This is a retained-memory/handle signal that needs repetition and object attribution; it does not by itself prove a leak.

## ARTIFACTS

- Rerunnable sampler and input driver: [`Measure-Tvivo.ps1`](Measure-Tvivo.ps1)
- Initial launch/debug output: `baseline-20260926-141316/`
- One-second idle baseline: `baseline-20260926-141844/samples.csv`
- S2–S7 samples and UI action timings: `scenarios-20260926-142357/`
- Corrected mouse-wheel S5 attempt: `scroll-20260926-144400/` (not inspected)

The first `baseline-20260926-141316` run aborted before writing metric samples due to a sampler parameter-name conflict; its raw WinApp output remains useful for launch and first-chance counts. Do not use any card data or stream URL text from UI Automation output as report content; it contains account-derived fields.

## EXCEPTIONS AND DISPOSITION

| Observed signature | Count | Source / frame | Disposition |
| --- | ---: | --- | --- |
| Generic CLR first-chance notice, code `0xE0434352` | 173 in captured launch output | Unknown; `--debug-output` omitted the managed exception type and stack | Unclassified. No exception was suppressed or changed. |

The prior crash/hang handoffs document a caught SQLite `0x80073D54` no-package-identity probe and a historic large-shelf-tree UI saturation. This run did not connect the 173 generic notices to that probe or prove a recurrence of the historic saturation.

## FIXES

None. The source was not edited, so no after metrics exist.

Source review identified two candidates for a measured follow-up: both catalog `ProgressRing`s are declared `IsActive="True"` even while their parent state is collapsed, and the eight-second Spotlight timer runs while the catalog is active. Neither was changed because the run could not be completed and no after comparison was possible.

## MONKEY / HEAVY USER

Not run. Playback, favorite/search/sort workflows, close-during-load, close-during-playback, and the 15/30 minute sessions remain unverified.

## EXACT BOUNDARY

The app process launched as PID 93392 and was observed responding during the baseline. The S7 baseline run confirmed that its target process exited within 236 ms after Alt+F4. The later wheel-input run was launched after S7, and the PowerShell session returned an artifact path, but the following shell invocations failed at process initialization with `0xC0000142`. The final process state, corrected wheel CSV, and raw post-run logs could not be read. No `gflags`/page-heap settings were added.

## RULED OUT

- The measured five-minute My Tvivo idle window did not show sustained CPU load: average 0.09% per core and peak 1.25%.
- Sampled UI pings remained below 775 ms in S2–S4 and below 8 ms during S6; no sampled ping crossed the 2 s failure threshold.
- The old 1,451 MiB / 446-thread hang signature was not reproduced in the captured runs; sampled threads peaked at 63 and settled near 40.
- No crash or unhandled exception dialog was observed in the captured pre-change runs.

## UNCERTAINTY

- The debugger's generic first-chance lines cannot support exception-by-type/source conclusions.
- Runtime GC counters and managed-object counts were unavailable.
- The first S5 data is not a verified scroll. The corrected run's CSV is uninspected.
- Memory and handle retention after mode switching may be XAML/driver caches or a leak; native heap attribution was not collected.
- No post-fix comparison, monkey run, heavy-user session, local-fixture playback, or final process-state check occurred.

## NEXT GATE

Restore a working shell, inspect and stop any process from `scroll-20260926-144400`, review its raw CSV, then add low-overhead debug-only first-chance aggregation (type, HRESULT, throw-site, interval count) and address confirmed idle work. Rebuild with `winapp run ./src/Tvivo.App/Tvivo.App.csproj --arch x64 --debug-output`, rerun the same scenarios and input driver, then run monkey and heavy-user sessions. Recheck all app and launcher processes at shutdown.
