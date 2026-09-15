## QA-8 — Entering Series focused the header search and opened the IME — **not reproduced**
**Severity: unknown. Reported by the user, not seen in QA.** Entering Series and Live both
focused the rail cleanly with no IME across repeated attempts. `BrowseScreen` already carries
a comment about "RIGHT out of the rail opened the search field instead of crossing to the
grid", so the *class* of bug is known. Best hypothesis: a race where the rail has no
categories yet, so the first D-pad press runs an origin-less 2D focus search and the header
field wins. **If it recurs, note whether the catalog was mid-sync.**

