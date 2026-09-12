## N-3 — A defined cached-catalog-but-no-network path — **IMPLEMENTED, emulator verification pending**
**Cost: small-to-medium. Blocks Phase 5's error-state work from being complete.**

The app assumes the panel is reachable. A household router being down, or the TV
waking before the network, is an ordinary state, not a failure — and the catalog
is fully cached locally, so browsing *should* work. Undefined today: whether the
grid renders from cache, what the per-category refresh does when it cannot reach
the panel, and whether a Play attempt says something better than a generic error.

`CachedFetch` already distinguishes fresh from stale; what is missing is a UI
contract over "stale and cannot refresh". Fold into Phase 5's empty/loading/error
state table rather than building it separately.

