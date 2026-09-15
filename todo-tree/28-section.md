## Q-27 — Back from the episode picker skipped the pre-run page — **FIXED**
**Severity: Low. Found 2026-09-10 during the full sweep.**
`MainActivity.kt`

With the pre-run page inserted before the picker, `Route.SeriesDetail`'s Back still went
straight to the shows grid — skipping a screen the user had walked through, and losing the
heart they may have gone back for. Now returns to `Route.ItemDetail`.

