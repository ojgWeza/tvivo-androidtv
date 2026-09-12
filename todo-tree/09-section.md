## Q-17 — RIGHT from a pill sometimes lands on a tile instead of the next pill — **FIXED**
**Severity: Medium. Intermittent — reproduced once, not on cold start.**
**Fixed and verified on-emulator 2026-09-08.** The pill row's traversal is declared — `focusGroup()` plus explicit `focusProperties` — rather than left to the 2D focus search.
`ui/home/HomeScreen.kt`

With focus on `Refresh everything`, RIGHT moved focus **down to the Series tile**
rather than across to `Account`, leaving Account and Exit reachable only by
accident. From a cold start the same press moves correctly to `Account`.

**Suspected cause:** the pill row is a plain `Row` with no `focusGroup()` and no
explicit direction overrides, and `animateContentSize` changes each pill's bounds
while the expand animation runs. Compose's 2D focus search scores candidates on
current bounds, so a press landing mid-animation can find a tile a better match
than the neighbouring pill. This is the same failure the rail already needed
`focusProperties { right = … }` to fix, and the same lesson: on this project the
2D search must be overridden, not trusted.

**Fix:** explicit `focusProperties` wiring across the row, and re-test with a
press sent during the animation window rather than after it.

