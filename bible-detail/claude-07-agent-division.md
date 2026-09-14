# Claude/Codex division of labor (Herdr-coordinated)

> Binding, per `D:\HCode\bible-detail\todo-and-bible-maintenance.md`'s constitutional
> clause. Read this at session start if you are Claude or Codex working in this repo —
> it is what makes the process self-sustaining instead of something the user has to
> re-explain every session. Established 2026-09-14 as a PoC on QA-10; extend to larger
> items (starting with D-Desktop-10) only after a PoC has actually closed a
> `todo-tree/01-open.md` item end to end.

## Roles (fixed, do not renegotiate per-task)

- **Claude plans and executes.** Writes the plan and acceptance criteria for any open
  item before touching code; does the implementation; owns closing the item out.
- **Codex reviews and tests, never self-marks its own work done.** Adversarially
  reviews Claude's plan for edge cases before execution (Codex wrote the original
  desktop LibVLC/native code, so its own past blind spots there are exactly what to
  check hardest). After the fix, tests against the plan's acceptance criteria with
  observable evidence — a screenshot, a dumped UI string, a log line — never "build is
  green" or "no exception thrown" alone. (`D-Desktop-10` shipped "fixed" twice on that
  weaker bar; this rule exists because of that, not in the abstract.)
- **The user approves.** Neither agent pushes, deploys, or opens a PR without the user
  seeing the diff first. Neither agent builds/installs/deploys without asking first,
  full stop — this is a standing rule independent of this workflow.

## Session-start self-check (do this before asking the user anything)

1. Read `D:\HCode\DEV-BIBLE.md`, `D:\HCode\bible-detail\todo-and-bible-maintenance.md`,
   this project's `AGENTS.md`/`CLAUDE.md`, and `todo-tree\01-open.md`.
2. If you are Claude and running inside Herdr (`HERDR_ENV=1`): check
   `herdr agent list` for an existing Codex pane in this workspace. Reuse it if present.
   Only start a new one (`herdr pane split` + `herdr agent start ... --kind codex`) if
   none exists — never spawn a second one.
3. If you are Codex and a session starts with no visible brief: assume the reviewer/
   tester role above by default for this repo, and read the same files in step 1.
4. Pick up work from `todo-tree\01-open.md` directly — it is the live queue. Do not
   wait for the user to restate an item that's already listed there.

## The loop, per item

1. Claude writes the plan + acceptance criteria (what to observe, not just what to build).
2. Claude hands the plan to Codex (`herdr agent prompt <name> "..." --wait` from the
   Herdr side) for edge-case review.
3. Claude revises the plan if Codex found a real gap, then executes — asking the user
   before any build/deploy step.
4. Codex tests the result against the acceptance criteria with evidence, reports pass/fail.
5. Claude closes the loop: check the item off `todo-tree\01-open.md`, add its closure
   note to `docs\decisions.md`'s "Archived todo-tree closures" (matching existing
   format), report to the user what changed and what the review/test step actually caught.

## Context measurement (part of the PoC, not optional once running)

Record, per item closed this way: context/tokens spent on each side, and — the number
that actually matters — how much of Codex's raw tool output stayed in its own Herdr
pane and never had to be read into Claude's context. Report this alongside the closure
note so the process's value is a number, not a vibe.

## Context harness for Codex (added 2026-09-14, post QA-10 PoC)

QA-10 closed with Claude spending a small fraction of Codex's context for equivalent
work (Codex: 9%→57% of its own window; Claude: single-digit percent of a much larger
budget) — the split itself worked as intended, none of Codex's raw output crossed into
Claude's context. But 57% for a two-screen visual check is too much fuel burned per
item; at that rate a harder item (D-Desktop-10) runs Codex out mid-task. The overspend
had identifiable causes, all mechanical, none inherent to the review/test role:

- **`uiautomator dump` was piped to `/dev/tty` (i.e. straight into Codex's own
  transcript) on every check**, ~300-390 lines of XML each, most of it
  `bounds=`/`resource-id=` noise irrelevant to the assertion. **Rule: dump to a file
  only** (`uiautomator dump path.xml`), then extract just the `text="..."` values that
  matter (`Select-String`/`grep -o 'text="[^"]*"'`). Never print a full hierarchy to
  console.
- **Blind trial-and-error navigation** (a wrong category tap, a filter-text guess) cost
  a full dump-and-inspect cycle per failed attempt. **Rule: plan the exact tap/filter
  sequence against a known layout before executing**, rather than probing and
  re-dumping after each guess. If the layout is genuinely unknown, do one exploratory
  dump, extract coordinates from it, then execute the rest blind.
- **Full-file reads used `Skip N -First M` blocks of 35-90 lines** even when the plan
  already named exact line numbers. **Rule: when a plan cites a file:line, read a tight
  window around it (±10-15 lines), not an arbitrary block** — and prefer `rg`/grep for
  a symbol over paging through a file that's already been narrowed.
- **Screenshots are the most expensive evidence type** (vision tokens per image) and
  one was viewed twice (a loading-frame capture, then its settled replacement).
  **Rule: capture once, verify the capture is settled before viewing it (a short sleep
  after the triggering action), and view each evidence image at most once.**

**Standing budget:** state a rough context ceiling in the test-handoff prompt itself
(e.g. "target finishing this pass under ~25% of your context"). If Codex is trending
over, it should stop expanding evidence collection and report with what it has rather
than silently continuing — same as any other acceptance-criteria shortfall, surfaced
not hidden.

**Reporting requirement:** Codex's final report must state its own context-used
percentage (its status line already shows this — copy the number into the written
report, don't leave it implicit) so the number is comparable run over run instead of
read off a screenshot of the pane after the fact.

## Escalation

If Codex's review surfaces a scope/architecture disagreement (not a straightforward
correction), stop and ask the user rather than Claude unilaterally overruling it. If
Codex's test fails, the loop returns to Claude, not back to Codex to patch directly —
Codex is the more error-prone party on the exact subsystem (native/FFI desktop code)
where the "fixed twice, still broken" pattern happened; letting it silently re-patch
its own flagged failure is how that regressed a second time.
