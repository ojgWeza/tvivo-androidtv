## QA-5 — Live channel cards with no logo render as bare empty rectangles — **IMPLEMENTED, emulator verification pending**
**Severity: Low.** 13 of 15 cards on the Live TV landing had no artwork and no fallback — no
channel initial, no generic glyph. May be upstream absence; the empty state is unhandled
either way. The same gap shows on the live pre-run page, now that its frame is the right shape.

