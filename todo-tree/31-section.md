## Q-23 — Sign out renders as an empty focus frame — **FIXED, verified**
**Severity: High. Found 2026-09-10 on-emulator, while verifying Q-21/Q-22.**
`ui/settings/SettingsScreen.kt`

Below the D-12 divider the Account screen draws a focusable row with **no text in it at
all** — a focus frame around nothing. It is `Sign out`: the only irreversible action in
the app, reachable on a remote with no indication of what it does. The accessibility dump
is unambiguous — the label node is 10 px tall and the hint node is absent entirely.

**Cause: an unscrollable `Column` does not overflow, it squeezes its last child.** The
screen is 540 dp less 128 dp of vertical padding, so 412 dp are usable, and the content
measures 451 dp (display 40 + username 26 + spacer 32 + three 76 dp action rows + 49 dp of
divider and spacers + a fourth 76 dp row). The 39 dp shortfall comes out of whichever
child is measured last, and D-12 deliberately put `Sign out` there.

**This is the same class as Q-11** — content below the fold on a fixed-height screen — and
it went unnoticed for the same reason every focus defect on this project has: it builds,
it launches, and the row is only wrong once you look at it.

**Fix:** `verticalScroll(rememberScrollState())` on the root `Column`, so every row gets
the height it asked for and TV focus traversal scrolls the row into view. Not a smaller
type scale or tighter spacers — those would fix this instance and leave the next one, and
the screen gains a row whenever an account gains a capability.

**Verified on-emulator 2026-09-10:** `Sign out` renders its label and its
"cannot be recovered" hint, and the `ConfirmDialog` was not reached — no credentials were
touched at any point.

