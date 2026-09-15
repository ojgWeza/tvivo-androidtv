## Q-18 — Overlaid card titles are hard to read over bright artwork — **FIXED**
**Severity: Low. Found 2026-09-08 on-emulator.** `ui/browse/ContentGrid.kt`
**Fixed and verified on-emulator 2026-09-08.** The scrim owns its own height (68 dp on a 165 dp poster) instead of being sized to the text.

D-6 works structurally — titles are on the poster, two lines, and the grid gained
back its row — but the scrim is too weak. Over bright posters the title competes
with the artwork rather than sitting on top of it.

**Cause:** the gradient starts at 45% of the overlay box and tops out at 94%
alpha over a box only as tall as the text plus 6 dp. Both the start point and the
box are too small.

**Fix:** start the gradient higher and give the scrim its own height independent
of the text.

---

