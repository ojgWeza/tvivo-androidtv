## N-7 — Global search across all three content types
**Cost: largest here. Fully unblocked, no network work.**

D-14 filters **within the current category only**, so finding a film today
requires already knowing its category — across 48,780 VOD rows, 6,424 channels
and 13,279 shows, that is the difference between a catalog and a haystack. The
full-catalog tables are already synced and local, so this is a DAO query per
content type plus a screen; **no panel call at all**.

Reuses D-14's shape: debounced 300 ms on `Dispatchers.IO`, results as
`BrowseItem` so the existing grid and pre-run page take them unchanged. Open
questions: where it is reached from (Home icon row is the obvious slot, and D-7
already established the component), whether results group by type or interleave,
and whether the TV IME ceiling rule forces the field into the upper half — it
does, and that decides the layout before anything else.

**Declines to reopen voice search** — that is in "Considered and not taken" and
this is the typed path that made it unnecessary.

