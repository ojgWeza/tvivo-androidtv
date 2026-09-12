## Q-21 — The expanded item filter overlaps the category title — **FIXED, unverified on-emulator**
**Severity: Low. Found 2026-09-08 on-emulator, verifying D-14.**
`ui/browse/BrowseScreen.kt`

Opening the grid filter expands a 320 dp field in place, and it is drawn over the category
name: with `ARABIC MOVIES 2026` in the header, the field covers everything from `2026`
rightwards.

**Cause:** the header is a `SpaceBetween` row of `title` and `actions`. Collapsed, the two
pills leave room; expanded, the field takes 320 dp more than the row has to give, and the
title does not shrink because nothing tells it to.

**Fixed 2026-09-10, both halves.** The title `Column` takes `Modifier.weight(1f)`, so
the actions measure first and the title can never be drawn over; and the title itself is
hidden while the filter is open, leaving the count line, which is the part actually
changing under the user. It wraps to two lines rather than truncating — `RAMADAN EGYPT
2026 SD` and `... HD` truncate to the same string.

**Not yet driven on the emulator.** The layout is the kind that has passed a green build
and still been wrong here; it needs a screenshot with a long category name and the filter
open before it is called done.

**Not a functional defect** — the filter and the `N of M` count both work, and the title
returns when the filter closes.

