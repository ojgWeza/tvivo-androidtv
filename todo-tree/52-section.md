## N-12 — Database size profiling and optimization
**Cost: medium. Measurement first, optimization only from observed data.**

The local catalog holds 48,780 VOD rows, 6,425 live channels, and 13,279 series
shows, plus per-show episode lists (some shows have 100+ episodes). Room caches
poster images downsampled to card size (220×330 and 220×124 px). The app does
not yet have a manifest size constraint or a user-visible storage footprint
warning.

Before Phase 5 closes, measure the actual database footprint on the real panel:
total `.db` file size, per-table sizes, image cache size, and whether the TTL
+generation logic produces dead rows or orphaned image blobs. Add a
`diagnostics/DbMetrics` query that reports these to the Diagnostics screen so
the user can see what is consuming space.

Check for: rows retained past the TTL window (generation flips should clean them,
but verify the migration is correct), orphaned image files if the image loader
ever fails, and index bloat from the per-content-type and per-category structures.
If the database exceeds a reasonable threshold on real hardware (e.g., >500 MB),
decide whether to add explicit user-triggered cleanup, reduce the full-catalog
sync window, or implement incremental/differential sync instead of replace-all.

**Current state:** no profiling or optimization done; measurement is prerequisite.

