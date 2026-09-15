## Q-19 — The category and item filter fields trap focus — **FIXED, verified**
**Severity: High. Found 2026-09-08 on-emulator, in the QA pass for D-13/D-14.**
`ui/browse/CategoryRail.kt`, `ui/browse/BrowseScreen.kt`

Once focus entered the rail's `Filter categories` field, **no D-pad press could leave
it.** Verified on-emulator: with the IME dismissed, two DOWN presses and a RIGHT and an
UP all left focus in the field, moving only the caret. The filtered categories the user
had just produced were unreachable, and Back was the only way out — which also discards
the filter. D-13 was therefore unusable as shipped, and D-14 could not even be reached to
test.

**Cause: the fix for this already existed and was in the wrong place.** `LoginScreen` has
carried a private `dpadFieldNavigation` since the very first defect on this project — a
login screen that could be typed into but never left. Being private to one screen, it was
a detail of that screen rather than a rule, so two new fields reproduced the identical
trap.

**Fix:** lifted to `ui/common/DpadField.kt` with the reasoning attached, and applied to
both filter fields plus an `ImeAction.Next` that moves focus into the list. **Any
focusable text field on a TV must carry it.** Left/right are deliberately still not
intercepted — inside a field they move the caret, which is wanted when fixing a typo.

**Verified on-emulator 2026-09-08:** with `horror` typed and the IME dismissed, DOWN moves focus out of the field and onto the first filtered category. D-14 became reachable and was then verified for the first time.

