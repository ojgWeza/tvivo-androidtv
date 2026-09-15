## Q-10 — The TV IME owns the D-pad, so in-form navigation is keyboard-only
**Severity: Low. Not a defect — a platform constraint, recorded so it is not
re-investigated as one.**

While the TV keyboard is up, **every** arrow press goes to the keyboard, not to
the app. `dpadFieldNavigation` never sees them, and no amount of
`focusProperties` changes that. Confirmed on-emulator: pressing RIGHT from the
password field moved the highlight around the on-screen keyboard, not to the
adjacent `Show` button.

Consequences, both acceptable:
- Moving between fields with the IME open is the keyboard's own `Next` / `Done`
  keys, which the form already wires up through `imeAction`.
- `Show`, `Clear` and `Sign in` are reachable once the IME is dismissed with
  Back. This is the standard Android TV model, not something to work around.

**Do not "fix" this by intercepting keys harder.** The earlier login focus trap
came from fighting the same platform behaviour.

