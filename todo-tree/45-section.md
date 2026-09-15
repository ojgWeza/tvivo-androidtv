## N-5 — Verify a resume position survives process death — **LOCAL COVERAGE IMPLEMENTED, hardware verification pending**
**Cost: verification first, possibly zero code.**

`resume_positions` and `favourites` are the only user data the app holds, and
`CONTINUE WATCHING` is built directly on the former. Backing out of the player is
tested; **the player being killed is not** — a TV box reclaiming memory, or the
user pressing Home mid-film. If the write only happens on a clean teardown, the
row is lost silently.

Check the write path first (`onPause` vs `onStop` vs teardown); it may already be
correct. Related: U-13 was a data-loss bug in this same table, which is reason
enough not to assume.

