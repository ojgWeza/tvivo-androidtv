## T-D4 — Decide whether Diagnostics should record routine events
**Not a defect.** Every `DiagnosticLog` call site is app start, a sync lifecycle event, or a
failure, so a *healthy* session leaves the screen nearly empty (see Q-22). That is defensible
— it is a diagnostics screen, not an activity log — but it means the screen cannot answer
"what did the app just do?", only "what went wrong?". Adding category selection and the
`get_vod_info` / `get_series_info` fetches would answer both. Costs noise; decide before
Phase 5 closes.

---

# Part 1b — Platform constraints, not defects

Recorded so they are not re-investigated as bugs.

