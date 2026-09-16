## T-D3 — Newest-first sort toggle for category grids

**What:** a second sort option, `added` descending, alongside the alphabetical
default, reachable per category.

**Why:** alphabetical fixes scanability but loses "what's new in this category".
Unlike `ALL`, a single category is small enough that either ordering is usable.

**Context:** `RECENTLY ADDED` already exists as a separate bounded virtual rail
(30 items, catalog-wide). This is per-category, inside a grid.

**Unblocked now** — Phase 2 shipped the grid and the long-press context menu to
hang a sort control off. Still needs a decision on whether the choice persists
per category, globally, or for the session only.
