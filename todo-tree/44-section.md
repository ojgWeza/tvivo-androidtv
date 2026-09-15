## N-4 — "This category did not sync" is indistinguishable from "empty" — **IMPLEMENTED, emulator verification pending**
**Cost: small. The data it needs already exists.**

The zero-row guard records `partial` instead of flipping the generation — that
flag is written and never read by the UI. So a category that failed to sync and a
category the panel genuinely ships empty render identically, and a refresh looks
like it did nothing. Surface `partial` as a distinct state with the retry
affordance the empty state does not need.

Depends on N-3's state table; do them together.

