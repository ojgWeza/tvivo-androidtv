## Q-12 — HD/SD stripping made distinct titles look like duplicates — **FIXED**

**Severity: Medium.** Categories appeared full of duplicated posters. They were
not duplicates: the panel publishes the same show once per quality, and
`NameNormalizer` strips the quality token out of `nameDisplay`, so two genuinely
different rows rendered as the same string. Confirmed by the rail itself, which
carries `RAMADAN EGYPT 2026 SD` (25) and `RAMADAN EGYPT 2026 HD` (43).

**The strip must stay** — it is what makes mixed-direction titles truncate
correctly (see the `name_display` reasoning in `docs/architecture.md`). So the
fix restores the distinction alongside it rather than by undoing it:
`NameNormalizer.qualityOf(raw)` re-derives the stripped token, `BrowseItem`
carries it, and `ContentGrid` draws it as a corner badge (top-**start**; this
panel's watermark sits top-end).

**No schema change.** The raw `name` is already stored on all three entities, so
the badge is derived at map time and cannot drift out of sync with `nameDisplay`.

