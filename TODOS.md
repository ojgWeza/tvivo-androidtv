# TODOs

Deferred work, with enough context to pick up cold. Items here were considered
during a review and consciously postponed — they are not a backlog of ideas.

Everything that was *not* deferred has already been folded into
`docs/ui-scope.md`, `docs/architecture.md` and `docs/decisions.md`.

---

## T-D1 — Finish the design system (DESIGN.md)

**What:** Run `/design-consultation` and produce a `DESIGN.md` covering what is
still missing: motion spec, icon set, and a product wordmark.

**Why:** `/plan-design-review` (2026-09-07) rated design-system alignment 0/10
because there was nothing to align to. Both outside reviewers independently
flagged the same gap: Codex answered NO to "brand/product unmistakable in first
screen" and "one strong visual anchor present", and the Claude reviewer noted
there was "not one colour, contrast ratio, or motion duration in the entire
handoff".

**Already closed:** colour and contrast were decided at the end of that session
— a dark teal surface ramp with a Claude-orange accent, with every pair
measured against WCAG. See "Colour" in `docs/ui-scope.md` and the decision in
`docs/decisions.md`.

**Context:** `docs/ui-scope.md` now carries a type scale, a layout system, exact
card sizes, a focus contract and a colour system with ratios. What remains is
motion, iconography, and identity. Focus is deliberately static, so motion only
matters for transitions between screens.

**Pros:** A wall of provider posters with no product identity is what both
reviewers said the first screen is today, and the wordmark is the fix.

**Cons:** The remaining pieces are the ones most improved by having real screens
to react to. Doing this after Phase 2 means one revision instead of two.

**Depends on:** Nothing. **Blocks:** Nothing, but the longer it waits the more
improvised colour accumulates in code.

**Suggested timing:** After Phase 2 (movies slice) so there is a real screen to
design against, and before Phase 5 hardening.

---

## T-D2 — Decide list behaviour: sort order, key-repeat, jump affordance

Three related gaps, all affecting every list in the app.

**T-D2a — Sort order within a category.** The panel returns items in `num`
order, which is arbitrary to a viewer: a 660-item category in `num` order is a
shuffle. No sort order is specified anywhere in the plan. Options are `num`
(what the panel gives), `added` descending (newest first), or alphabetical on
`name_normalized` — which is the only one that makes an Arabic/English mixed
catalog scannable, and also the only one that needs a decision about where
Arabic sorts relative to Latin.

**T-D2b — Key-repeat and fast-scroll.** Undefined, and the full-catalog sync
makes it matter: holding DOWN in `ALL` traverses 9,750 rows. Needs a decision on
repeat acceleration, and on whether focus movement stays 1:1 with key events or
switches to page-jumps past a threshold.

**T-D2c — Jump affordance in `ALL`.** This is the accepted cost recorded in
`decisions.md` under "`ALL` renders a grid in panel order": with no jump
affordance the end of a 9,750-row list is unreachable in practice, so the first
screen of `ALL` is effectively a random 10 of 48,751. Revisit if `ALL` proves to
be a dead end in use.

**Why:** These are cheap to decide while no code exists and expensive to retrofit
once a grid component has shipped and three content types copy it.

**Pros:** Affects every screen; deciding once avoids three inconsistent answers.

**Cons:** Fast-scroll feel is genuinely hard to judge without the real box, and
physical-TV validation is deliberately deferred to Phase 5.

**Depends on:** T-D2b and T-D2c are best judged on hardware, so realistically
they land with the Phase 5 physical-TV session. T-D2a can be decided now.

---

## Considered and not taken

Recorded so they are not re-proposed as new ideas:

- **Idle dim / screensaver.** A static rail on an OLED panel risks burn-in after
  ~5 min idle. Small and self-contained; judged not worth tracking yet.
- **Manual refresh control placement.** `CLAUDE.md` mandates the control and it
  appears in no layout. Expected to place itself obviously once the header is
  built.
- **Voice search.** Most Android TV remotes have a microphone, and search is the
  only place outside login that asks for typing. Declined as a whole integration
  for one field; the rail's category filter already keeps most navigation
  typing-free.
