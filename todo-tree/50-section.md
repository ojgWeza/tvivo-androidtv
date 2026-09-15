## N-10 — Handset touch activation path needs form-factor flexibility
**Cost: small. Deferred until other handset form factors are supported.**

The 2026-09-12 handset Home tile fix extracts `detectTapGestures` specifically
for non-TV devices and wires it through `onSelect` alongside the Android TV `Card`
click path. This works for phones but assumes all non-TV devices behave the same
way. If tablet support is added later, the touch/D-pad/focus contract may differ
again, and a blanket "if not TV, use touch workaround" will need revisiting.

**Rule for future form factors:** do not generalize a handset touch workaround to
other non-TV devices without device evidence. Verify touch input paths, focus
traversal, and card activation on each new form factor independently before
merging the code.

**Current scope:** phone-only right now; defer tablet/other form factors until
they are actively supported.

