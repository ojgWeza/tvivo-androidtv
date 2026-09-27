# Overnight performance + robustness plan (unattended)

Owner: Claude session orchestrating Codex. The user is ASLEEP: do not stop to ask questions. Every decision point below has a default; take it, log it in `windows-spike/perf/overnight-log.md`, and continue. Report at the end.

## Fixed settings
- Codex: `codex exec -m gpt-6-luna -c model_reasoning_effort=medium --sandbox danger-full-access -C D:/HCode/Tvivo "$(cat <prompt-file>)" < /dev/null > <stage-log> 2>&1`
  - ALWAYS `< /dev/null` (without it codex exec hangs on "Reading additional input from stdin...").
  - ALWAYS put the prompt in a file under `$CLAUDE_JOB_DIR/tmp` (avoids quoting bugs). Run with `run_in_background`.
  - Escalate to `-c model_reasoning_effort=high` ONLY for a stage that failed twice at medium.
- One stage at a time. A stage = one Codex run with one handoff file `windows-spike/perf/stage-N-handoff.md`. Never run two Codex jobs or two app instances at once.
- Report format required from Codex each stage: VERDICT, STRONGEST EVIDENCE, EXACT BOUNDARY, RULED OUT, UNCERTAINTY, NEXT GATE.
- Approvals: the user has APPROVED launching the app (`winapp run ./src/Tvivo.App/Tvivo.App.csproj --arch x64 --debug-output`, from `windows-spike`) and code changes for this plan. No commits during the night except the checkpoint commits below; NO push at night unless stage 6 says so.

## Hard rules (learned the hard way)
- NEVER `AppWindow.SetPresenter(FullScreen)` and NEVER `ExtendsContentIntoTitleBar`/`SetTitleBar`: both heap-corrupt the app (0xC0000374). Win32 borderless fullscreen only (`WindowStateController`).
- Don't touch user data, the SQLite catalog file, or saved credentials. Don't clear Tvivo app data. Don't commit/upload `perf/*/` raw run folders or `ui-captures`/screenshots: they contain account-derived provider data (repo is PUBLIC). Only `Measure-Tvivo.ps1`, `REPORT.md`, plans and handoffs are committable.
- Playback: one stream at a time, stopped after each use, total opens capped (max 15 per night). If no saved account works, skip playback tests and say so; do not ask.
- Never leave `Tvivo.App`, `winapp`, `procdump`, `dotnet-*`, or page-heap/gflags settings running/enabled at the end of a stage.

## Guards against the failures seen so far
1. **Memory pressure killed the last background task** (machine was critically low on RAM). Before every stage run: check free RAM (`(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory`); if < 3 GB, stop `Tvivo.App`/`winapp`/leftover `codex` from THIS plan, close spare `pwsh`/`Taskmgr`, wait 60 s, recheck (up to 5 times). If still low, run the stage anyway but with the app-only harness (no gcdump/procdump) and note it. Do not start a second heavy tool (dotnet-gcdump/procdump) while the app runs with a monkey driver.
2. **Shell death `0xC0000142`** (desktop heap exhaustion from too many spawned processes) ended the last run. Each stage's harness must reuse ONE long-lived driver process, sample via a single loop (no spawning a new `powershell`/`tasklist` every second), and write results incrementally. If a shell starts failing with 0xC0000142, kill leftover shells from this plan, wait 30 s, retry in a fresh tool call; never loop faster than every 5 s.
3. **Codex background notifications** can report success/failure incorrectly: after every stage read the stage log (`## VERDICT` section), the handoff file and `git status`, not just the notification.
4. **Stuck/hung app**: hang detector = UIA/SendMessageTimeout ping > 10 s three times in a row. On hang: capture a dump ONCE (`procdump`/dotnet-dump if present, else WER), then kill the app, record it as a finding, continue with the next scenario. Never wait indefinitely; every scenario has a wall-clock timeout (2x its nominal time).
5. **Codex hangs**: if a stage log is unchanged for 25 minutes and the process is idle, `TaskStop` it, kill leftovers, rerun the stage once with a narrower prompt (split it). Twice failed → log it, skip the stage, continue.
6. **Build breaks**: after any code change Codex must `dotnet build windows-spike/Tvivo.sln -p:Platform=x64`. On failure Codex fixes its own errors. If a stage leaves the build red, revert that stage's source changes with `git stash`/`git checkout -- <files it changed>` ONLY for files that stage changed (compare to the last checkpoint commit), log it, and move on.
7. **A fix makes things worse or crashes**: keep a checkpoint commit before each fix stage; if the after-measurement regresses (crash, hang, or CPU/RAM worse than baseline) revert that fix stage (`git revert`/checkout of its files) and log it. Never leave the repo in a state that doesn't build or that crashes on startup: do a 3-launch startup check at the end of every code stage (window handle present, `Responding`, no `CRASH DETECTED`/`0xC0000374`, closes and exits in < 3 s).

## Stages
Checkpoint commit before stage 1 is already made (branch `codex/winui-catalog-design-plan`). After each successful code stage: `git add` only the source/test/handoff files of that stage and commit locally (message `perf: stage N ...`). No push.

### Stage 0 - Preflight (me, no Codex)
- `git status` clean of surprises, build passes, no stray processes, RAM check (guard 1). Read `perf/REPORT.md` (baseline already partly captured 2026-09-26) and `.codex-handoff-perf.md`.
- Create `overnight-log.md`.

### Stage 1 - Baseline completion (Codex, medium)
Finish what the first run left: it captured S1-S4, S6, S7 but S5 (scroll) was invalid/uninspected and GC/exception-type data was missing.
- Get exception TYPES and top frames: enable managed first-chance logging in a debug-only way (e.g. `AppDomain.FirstChanceException` handler behind an env var `TVIVO_LOG_EXCEPTIONS=1` writing type+top frame+timestamp to `%TEMP%\tvivo-firstchance.log`, no behaviour change when unset), or `dotnet-trace`/`DOTNET_` EventPipe if available. Group by type/frame, counts per minute, idle vs active.
- Redo S5 with a scroll driver that verifies content moved (UIA scroll pattern or measured `ScrollViewer.VerticalOffset`/first visible item changed).
- Get managed heap numbers: `dotnet-counters`/`dotnet-gcdump` if installable via `dotnet tool install --global` (nuget.org only; never the private claude-work feed - see NuGet feed isolation), else `DOTNET_gcServer=0` + EventPipe env config, else record "not obtainable" and use private bytes only.
- Save everything under `perf/baseline2-<timestamp>/` (git-ignored for commit) + summary into `REPORT.md` (BEFORE section, sanitized: no titles, URLs, account fields).
Default if a tool can't be installed: proceed without it and note it.

### Stage 2 - Monkey test, baseline build (Codex, medium)
15-minute chaos run (or 10 if RAM guard tripped): random clicks anywhere incl. header buttons, keys (Esc, F11, Enter, Tab, arrows, Space; Alt+F4 only at the very end), double-click spam, rapid mode switching mid-load, fullscreen/cinema toggles mid-navigation, minimize/restore, wheel storms, open/close Player repeatedly (playback rules above). One long-lived driver, seeded RNG (log the seed to reproduce).
PASS = no crash, no hang (guard 4), no unhandled-exception dialog, memory slope reported, threads/handles return near baseline after idle.
Record every failure with seed + last 20 actions. This stage is measurement only: no code change.

### Stage 3 - Fix pass A: exceptions + idle CPU + shutdown (Codex, medium)
Using stage 1-2 evidence only (no guessing):
- Eliminate avoidable recurring first-chance exceptions (e.g. avoid the Microsoft.Data.Sqlite `ApplicationData` 0x80073D54 probe if it fires repeatedly; fix any real bug throwing per frame/per item).
- Idle CPU ~0: both catalog `ProgressRing`s are declared `IsActive="True"` even when collapsed -> bind IsActive to actual loading; Spotlight timer (8 s) runs only while its page is visible/window not minimized; stop transport/timeline timers when not playing; no infinite animations.
- Shutdown: everything disposed (engines/timers/handlers/threads), process exits < 2 s incl. close while loading and while playing.
Then guard 7 startup check, rebuild, commit locally.

### Stage 4 - Fix pass B: RAM/handles/GPU (Codex, medium)
Evidence from stage 1 shows ~+150 MiB private bytes and +320 handles after 50 mode switches with no return: find retention (event handlers not unsubscribed, cached pages/snapshots without bounds, artwork/BitmapImage not released, engines not disposed on leaving Player). Bound every cache (snapshot cache 16 entries already; image cache, decoded bitmaps via DecodePixelWidth). Reduce compositor work (no per-card Shadow/Acrylic/OpacityMask, no offscreen layers). Add debug counters for live pages/handlers. Then guard 7, rebuild, commit locally.

### Stage 5 - After measurement + heavy user (Codex, medium)
Rerun EXACT same harness scenarios S1-S7 and the monkey test (same seed) on the fixed build, plus a 30-minute heavy-user session (browse all modes, search/filter/sort, favourites, open many titles, play/pause/seek/cinema in-out, back/home repeatedly, 5-min idle mid-session then resume). Write the AFTER section + before/after delta table into `REPORT.md`: CPU idle/peak/avg, RAM start/peak/end/slope, GPU util+memory, threads, handles, exceptions/min by type, p50/p95/max mode-switch latency, startup time, exit time. Include failures found, fixes (file, why, measured effect), what could NOT be measured, remaining risks.
If a regression vs baseline is found: revert the responsible stage (guard 7), rerun the affected scenarios, document.

### Stage 6 - Wrap-up (me)
- Verify: build green, 3-launch startup check, no leftover processes, page heap/gflags off, `git status` sane, secrets scan (no account fields in committed files).
- Commit the final docs/handoffs. Push: `git push origin codex/winui-catalog-design-plan` ONLY if build green AND startup check passed AND no raw run folders/screenshots are staged; otherwise leave local commits unpushed and say why.
- Write the morning summary: what ran, before/after headline numbers, failures, skipped stages, reverted stages, and the single next decision for the user.

## Decision defaults (no waiting)
- Tool missing -> proceed without it, note it.
- Ambiguous scenario result -> repeat once, take the worse number, note it.
- Codex out of quota/erroring -> retry the stage once after 20 min; if it still fails, stop the run, leave a precise status in `overnight-log.md`, and end. Do not switch to Claude for code work overnight (Claude quota is the scarcer one).
- Scope creep -> don't. Only performance/robustness fixes backed by measured evidence. Do NOT implement Issue 8 (title cleanup), the mini-player, or new features.
