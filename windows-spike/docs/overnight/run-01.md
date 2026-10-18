# Run 01 - Batch 1 (Lifecycle and close paths) - iteration 1

- Date: 2026-10-19, 02:43-02:57 local. Runner: Opus.
- Build: HEAD 5e772ad; Debug x64 exe 01:44:36, no source file newer than the exe (build current, no rebuild needed).
- Tvivo: never launched in this run (0 processes before and after). Streams: 0.

## Result: Batch 1 BLOCKED - machine memory pressure (RULES.md rule 5, MATRIX §0 pre-batch check)

Pre-batch check requires Available >= 2.5 GB; rule 5 forbids launching below 1.5 GB.
`\Memory\Available MBytes`, sampled ~every 10-30 s for 13 min, never rose above 1.24 GB and trended down:

| Time | Available MB |
|---|---|
| 02:44 | 1287 (WMI free) / 1161 (counter) |
| 02:47 | 1045 |
| 02:50 | 1237 |
| 02:52 | 994 |
| 02:54 | 929 |
| 02:56 | 851 (min 814) |

Commit charge 19.2 / 30.3 GB. Largest consumers (no Tvivo process): Android emulator `qemu-system-x86_64-headless`
WS ~4.1 GB (owned by the Android session - not touched, per rule 1), five `claude` processes ~2.0 GB WS / ~3.9 GB
private together, Defender ~0.5 GB.

| ID | Status | Evidence |
|---|---|---|
| LC-01 .. LC-11 | BLOCKED (memory) | Available 0.81-1.24 GB < 1.5 GB launch floor; no launch performed |

Batches 2 and 3: NOT-RUN (same stop condition; the task says stop on memory pressure).

## Harness prepared (outside the repo, ready for the rerun)
`D:\Scratch\overnight-tools\ov-lib.ps1` (launch + session id, UIA find/invoke, foreground check, Alt+F4 via
keybd_event, close-time measure, WER/dump check, log counters, privacy grep, window/monitor bounds) and
`D:\Scratch\overnight-tools\sampler.ps1` (one long-lived sampler: WM_NULL ping every 1 s, CPU/WS/private/
handles/threads/GDI/USER/available-RAM row every 5 s, stops on process exit or stop-flag).
