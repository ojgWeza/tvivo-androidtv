# Overnight test-and-fix loop (owner asleep, 2026-10-19) - RULES

Owner order: test scenarios and combinations that could cause misbehaviour or hurt performance; loop until all possible combinations are covered. Opus makes the hard decisions (the owner is asleep). Sol does the heavy work (fixes). Today ONLY Opus and Sol are used (no Haiku/Luna).

## Hard rules for every agent
1. Work only in the Windows worktree `D:\HCode\Tvivo-windows\windows-spike`, branch `codex/winui-catalog-design-plan`. Never touch `D:\HCode\Tvivo` (Android session). Run `git branch --show-current` before every commit; if wrong, stop.
2. Never print credentials, usernames, passwords, hosts, tokens or full stream URLs, in chat, logs or files. Statuses/timings only.
3. Real provider streams: one at a time, short (seconds), audio muted (Tvivo volume 0 / mute), dark theme unchanged. Do not change account/provider/credential settings, never delete or clear `%LOCALAPPDATA%\Tvivo` data (the key store is unrecoverable). Back up nothing, delete nothing there.
4. Tvivo: only ONE instance at a time. You may start/close the Debug build from this worktree (the owner is asleep and allowed it). Build only when Tvivo is closed. Windows focus lock: UI automation only works while Tvivo is the foreground window - launch it, then drive it; do not minimise/move other windows, do not try focus-stealing workarounds; if Tvivo cannot be brought to the foreground, record that scenario as BLOCKED and continue with log/OS-metric checks.
5. Watch machine memory: if free RAM < 1.5 GB or the harness says memory pressure, stop launching extra processes, close Tvivo, wait, and record it. Do not start background shells that scan large folders.
6. Commit after each verified slice (build 0 errors + all tests green) and push the branch (no force). No MSI, no deploy, no version bump, no pushing other branches.
7. Evidence only: every defect needs a log line / metric / screenshot path. Do not claim a pass you did not observe. Mark each scenario PASS / FAIL / BLOCKED / NOT-RUN.
8. Keep results in `docs/overnight/` (run-NN.md, DEFECTS.md, DECISIONS.md, STATE.md). STATE.md always says: iteration, what is done, what is next.

## Metrics to sample in every UI scenario (log + OS)
Process CPU %, working set MB, handles, threads (sample every 5 s for the scenario); app log counters: perf.stall events (count, max delayMs), perf.slowop, artwork.failure, playback.* errors, exceptions; process alive at end; clean close time.
Thresholds (Opus may adjust and record in DECISIONS.md): UI stall > 1000 ms outside startup = defect; working set growth > 150 MB over a scenario after GC settle = defect; idle CPU > 3 % for 30 s with no playback = defect; any crash/hang/unhandled exception = critical.

## Loop
A. Opus scenario-runner executes the next batch of the matrix (docs/overnight/MATRIX.md) -> run-NN.md, DEFECTS.md.
B. If defects: Opus triages (severity, decision on behaviour questions, writes the fix brief) -> Sol fixes (write) with tests, builds, commits, pushes -> Opus reviews the diff.
C. Repeat until every matrix row is PASS or documented BLOCKED, and one full clean pass completes with no new defect (cap: 8 iterations). Then write FINAL.md (what was covered, defects fixed, open items, what the owner must eyeball).
