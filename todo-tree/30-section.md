## Q-24 — Every text field is a horizontal dead end — **FIXED IN CODE, RECHECK PENDING**
**Severity: High. Reported by the user 2026-09-10.**
`ui/common/DpadField.kt`, `ui/browse/CategoryRail.kt`

From Movies, RIGHT out of the rail lands in the `Filter categories` field, and a second
RIGHT does nothing at all. There is no D-pad answer to "how do I get to the grid from
here" other than knowing to press DOWN first, which nothing on screen says.

**Cause: [dpadFieldNavigation] only ever opened a *vertical* escape.** It intercepts
up/down and deliberately leaves left/right to the caret. `RailRow` carries
`focusProperties { right = gridFocusRequester }` and the field above it does not — so
RIGHT crossed to the grid from every row of the rail except the one at the top. The same
gap left the header's `Clear filter` pill unreachable: it sits to the right of the item
filter and nothing could move onto it.

**The caret argument does not survive contact with the device.** The TV IME ships its own
◀ ▶ keys, so caret movement is already served; and while the IME is up it owns every
arrow press and the modifier is never called at all (Q-10). The only time these events
reach the app is once the keyboard is closed — which is exactly when the user has
finished typing and wants to leave.

**Fix:** left/right escape too, consumed **only on a successful move**, so where focus has
nowhere to go (LEFT out of the leftmost pane) the event still falls through to the caret
and nothing is taken away. Plus the explicit `right = gridFocusRequester` on the rail's
field, because the 2D search scores the header's Refresh as a fine candidate to the right
— the same override `RailRow` already needed, and the same lesson as Q-17.

**The rule this settles:** the rail is one pane, and which row of it focus happens to be
on must never change what crossing to the content means.

**QA regression found 2026-09-11:** the expanded item-filter field still trapped
Up, Down, Left, Right, and Back on the field. The generic modifier had no valid
2D destination in that header geometry, and the documented Back-to-close behavior was
not wired. The code fix gives the field explicit Left → rail, Right → Refresh, and
Down → grid requesters, and installs an enabled Back handler that closes and clears the
filter. Debug and release unit suites pass (151 tests); emulator recheck is pending fresh
deployment approval.

