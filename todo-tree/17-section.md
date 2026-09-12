## QA-3 — Arabic description paragraphs resolve LTR, so the last line hangs on the wrong edge — **IMPLEMENTED, emulator verification pending**
**Severity: Medium.** Glyph order and bidi-isolation of embedded Latin runs are both correct;
the *paragraph direction* is not, so a short final line aligns left instead of flush right.
Reproduces on the movie pre-run page and the episode-picker header. Same class as
`rtl-title-truncation-needs-display-column`, but in the body text rather than the title.

