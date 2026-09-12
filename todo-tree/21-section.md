## QA-7 — The rail's "last-active" tint is very close to invisible — **IMPLEMENTED, emulator verification pending**
**Severity: Low.** The two-state contract (focus frame + last-active) is implemented, but the
tint is only a few percent lighter than the rail background, so when focus is elsewhere it is
hard to tell which category the grid belongs to. Same on the episode picker's season list.

