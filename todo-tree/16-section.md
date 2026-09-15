## U-14 — player seek is written but unverified

`max_connections` is 1, so no automated test may open a stream and the QA pass could not
either. The change is in (`SEEK_INCREMENT_MS` = 10 s, symmetric, intercepted in
`onKeyDown` so seeking does not depend on `DefaultTimeBar` taking focus) but **someone has
to play a film and press LEFT/RIGHT**. Lands with Q-4/Q-5 and the physical-TV session.

---

# Part 1d — 2026-09-10 QA sweep: found and still open

Visual/content findings from the report-only pass, none of which were in the fix batch.
Full report with screenshots: `.gstack/qa-reports/` (gitignored — it contains account
details visible in the UI).

