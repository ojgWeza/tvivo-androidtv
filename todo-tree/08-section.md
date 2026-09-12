## Q-16 — The Exit pill's glyph renders as tofu — **FIXED**
**Severity: Medium. Found 2026-09-08 on-emulator.**
**Fixed and verified on-emulator 2026-09-08.** `IconPill` takes an `ImageVector` from `material-icons-core` rather than a character, so font coverage stops being a variable. Still placeholders pending T-D1.
`ui/common/IconPill.kt`

`⏻` (U+23FB POWER SYMBOL) has no glyph in the emulator's font stack and draws as
a missing-character box. `◔` (Account) does render but is small and reads as
nothing in particular at ten feet; `↻` (Refresh) is fine.

These are documented placeholders pending the real icon set (T-D1), but a
placeholder that renders as a box is worse than one that renders as a wrong
picture — the pill's whole premise is that a glyph is legible at rest.

**Interim fix:** restrict placeholders to glyphs that actually exist in Roboto /
Noto on Android TV, and verify each one on-device rather than assuming.

