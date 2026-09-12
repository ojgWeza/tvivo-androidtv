## Q-11 — Sign in / Clear sit under the TV keyboard — **FIXED (D-1), verified 2026-09-08**

**Severity: High.** Focusing the password field made the `Sign in` and `Clear`
buttons disappear; they returned only once focus reached the button row.

**Root cause:** `Modifier.imePadding()` in `LoginScreen` was a **no-op**.
`android:windowSoftInputMode="adjustResize"` was set in the manifest, but
`WindowCompat.setDecorFitsSystemWindows(window, false)` was never called, so the
framework consumed the IME inset itself and reported zero to Compose. The button
row therefore stayed where it was, under a keyboard that covers roughly the lower
half of a 1080p panel. It reappeared on Tab only because the row's
`onFocusChanged { keyboard?.hide() }` (the Q-7 workaround) dismissed the IME —
that workaround was masking the real bug rather than fixing it.

**Fix:** `WindowCompat.setDecorFitsSystemWindows(window, false)` in
`MainActivity.onCreate`. One line; `imePadding()` then does what it always read
as doing.

**Distinct from Q-10.** Q-10 is about *reachability* (the IME owns the D-pad, so
arrows cannot move focus out) and remains an accepted platform constraint. This
was about *visibility* — the buttons were off-screen, not merely unfocusable.

**Verified on-emulator 2026-09-08, and the fix is not sufficient.** With the
password field focused and the IME up: the form now scrolls (the title scrolls
off) and the `Show` button is visible beside the field, neither of which happened
before — `imePadding()` is demonstrably working now. But `Clear` and `Sign in`
are **still off-screen**, because a `verticalScroll` Column only brings the
*focused* child into view and the button row sits below it. The remaining ~590 px
above the keyboard cannot hold title + subtitle + three fields + error + buttons.

**Closed by D-1 and verified on-emulator 2026-09-08.** With the IME up, the whole
form — server, username, password and the complete action row — is visible above
the keyboard. The fix was the layout, not the insets: a fixed ~290 dp top-anchored
column with no `verticalScroll` and no `imePadding()`. **Margin is ~0 px**, so any
added padding or a taller header re-breaks it; the reserved one-line error row and
`ErrorCopyTest`'s 48-char budget are what hold it.

**Original analysis, kept because it is why the first two fixes failed:**

**What was left was a layout change, not an inset fix.** The form is a 440 dp
column on a 1920 px screen, so the entire right half is empty. Moving the button
row beside the fields rather than below them takes it out of the IME's path
entirely. That is a design decision, so it is not made here.

**Not blocking sign-in:** the IME's own Done key submits, which is how this was
tested, and dismissing the IME reveals the row.

**Testing note that cost time:** Account → "Sign in to a different account" does
**not** wipe anything — it only routes to Login and keeps `current.credentials`
until a new sign-in is accepted. Only the separate `Sign out` button calls
`store.wipe()`. Entering a deliberately wrong account is therefore a free way to
QA the login screen, and was used for this. Do not conflate the two paths.

