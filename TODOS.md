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

## T-D2 — Decide list behaviour: key-repeat, jump affordance

Two related gaps, both affecting every list in the app. (A third,
sort-order-within-a-category, was decided 2026-09-07 — alphabetical on
`name_normalized`, plain code-unit order — see `docs/decisions.md`.)

**T-D2b — Key-repeat and fast-scroll.** Undefined, and the full-catalog sync
makes it matter: holding DOWN in `ALL` traverses 9,750 rows. Needs a decision on
repeat acceleration, and on whether focus movement stays 1:1 with key events or
switches to page-jumps past a threshold.

**T-D2c — Jump affordance in `ALL`.** This is the accepted cost recorded in
`decisions.md` under "`ALL` renders a grid in panel order": with no jump
affordance the end of a 9,750-row list is unreachable in practice, so the first
screen of `ALL` is effectively a random 10 of 48,751. Revisit if `ALL` proves to
be a dead end in use.

**Why:** Both affect every screen, so deciding once avoids inconsistent answers
per content type — but both are genuinely hard to judge without the real box.

**Pros:** Affects every screen; deciding once avoids inconsistent answers.

**Cons:** Fast-scroll feel is genuinely hard to judge without the real box, and
physical-TV validation is deliberately deferred to Phase 5.

**Depends on:** Both are best judged on hardware, so realistically they land
with the Phase 5 physical-TV session.

---

## T-D3 — Newest-first sort toggle for category grids

**What:** Add a second sort option — `added` descending — alongside the
alphabetical default (T-D2a decision above), reachable per category.

**Why:** Alphabetical fixes scanability but loses "what's new in this
category," which a viewer may still want. Unlike `ALL` (48,751 items, where
newest-first was tried and declined — see `decisions.md`), a single category
is small enough that either ordering is usable, so this is a real toggle worth
having, not just a discarded option.

**Context:** `RECENTLY ADDED` already exists as a separate bounded virtual
rail (30 items, catalog-wide) — this is not that. This is per-category, inside
a grid a viewer has already drilled into.

**Pros:** Cheap once the grid and long-press context menu exist (Phase 2) to
hang a sort control off; no schema change, just an alternate `ORDER BY`.

**Cons:** Needs a UI affordance (where does it live, how is it D-pad-reached)
and a decision on whether the choice persists per category, globally, or for
the session only — not worth designing before there is a real grid to react
to.

**Depends on:** Phase 2 (movies vertical slice) shipping the grid and context
menu. **Blocks:** Nothing.

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
