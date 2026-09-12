## T-T2 — Instrumented D-pad focus traversal tests — **HARNESS IMPLEMENTED, device run pending**
Compose focus tests on the emulator, per CLAUDE.md. The Android-test harness now compiles with isolated RIGHT and DOWN escape tests for the shared D-pad field contract; execution remains pending fresh emulator approval. Q-1, Q-2, Q-3 and Q-9 are
all focus/layout defects that shipped despite a green build — this is the class
of test that would catch them. Q-9 raises the value further: it was invisible in
code review twice over, since the first fix compiled, read correctly, and did
nothing.

---

# Part 2b — The 2026-09-08 design pass: decided and unbuilt

`/impeccable` pass, four revision rounds with the owner. **Every decision below
is settled** — rationale in `docs/decisions.md`, specification in
`docs/ui-scope.md`, visual reference in **`docs/design/comps.html`** (open it in
a browser before touching UI). Nothing here is built yet.

**Status 2026-09-08: the whole design pass D-1..D-17 is built, deployed and
verified on-emulator.** Phase 5 has also landed its `RefreshWorker` (WorkManager
job confirmed scheduled), an on-device Diagnostics screen, `supportsRtl`, and a
grid loading state. 149 unit tests pass.

The QA pass that verified it found six defects, Q-15..Q-20; all six are fixed,
and Q-15..Q-20 are verified except where noted. Q-21 and Q-22 are open and were
found by the same pass.

Sequencing: **D-4 and D-5 touch nearly every screen.** Land them first so
everything else is built against the real scale and roles rather than twice.

