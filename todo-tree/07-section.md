## Q-15 — The icon pill draws a second, rectangular focus indicator — **FIXED**
**Severity: Medium. Found 2026-09-08 on-emulator. Regression of Q-3.**
**Fixed and verified on-emulator 2026-09-08.** Lifted to `tvClickable` in `ui/common/TvFocusFrame.kt` and applied to every `clickable` call site, since all of them were latent instances of the same thing.
`ui/common/IconPill.kt`

The focused pill shows the accent stadium fill *and* a black rectangle behind it,
squared off at the pill's layout bounds. Two competing focus indicators on one
control is exactly what Q-3 fixed, and `HomeScreen` even carries a comment about
suppressing `tv-material3`'s own outline for the same reason.

**Cause:** `Modifier.clickable` supplies a default indication, which paints to the
node's rectangular bounds and does not follow the `RoundedCornerShape(50%)` the
pill draws its background with. It went unnoticed on every other control in the
app because they are all rectangular, so the indication coincides with their
edges.

**Fix:** `clickable(interactionSource = …, indication = null)`, leaving
`tvFocusFrame` as the only indicator. Worth auditing the other `clickable` call
sites at the same time — they are all latent instances of this, and any of them
gaining a rounded shape reintroduces it.

