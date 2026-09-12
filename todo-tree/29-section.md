## Q-25 — The browse screen opens with nothing focused — **FIXED, unverified on-emulator**
**Severity: High. Root cause behind the Q-24 report. Found 2026-09-10.**
`ui/browse/BrowseScreen.kt`, `ui/browse/CategoryRail.kt`

Opening Movies leaves **no node focused at all** — confirmed by `uiautomator dump`, which
reports zero `focused="true"` nodes on the browse screen. An Android TV screen with no
focus owner has no D-pad behaviour: the first press runs a 2D search with no origin to
measure from, so it lands wherever the heuristic likes. That is why RIGHT out of the rail
opened the search field instead of crossing to the grid — the press was never *leaving*
the rail, because focus had never been in it.

**Every screen that had this bug had it invisibly.** Nothing looks wrong in a screenshot;
the defect is that the screenshot has no focus frame in it, which reads as "before the
user pressed anything".

**Fix, three parts:**
1. The rail is the resting focus, requested once the first categories arrive — it is
   where a user decides what to look at. Requesting on the LazyColumn's `focusGroup`
   delegates to its first focusable child, which is safe against rows not yet composed.
2. **UP from the top of the list opens the filter** (the decided idiom). Declared on the
   first row only, or UP from row 40 would jump to the filter instead of row 39. The
   field is pinned above the list and is not part of it, so without the override the 2D
   search is free to leave the rail entirely and hand UP to the grid — which it did.
3. The field reads **`Search`** at rest rather than `Filter categories`: the user is
   looking for the verb, and the rail is the only thing on that side of the screen, so
   what it searches is not in question.

**Worth a test, not just a fix.** "Does this screen have a focus owner when it opens" is
one assertion per screen and would have caught this — T-T2.

