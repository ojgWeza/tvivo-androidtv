## Build order

0. Skeleton + install loop (TV manifest, network security config, `adb connect`
   workflow, **stable debug signing key**, one-time `dumpsys media.codec` probe,
   and verify whether `get_vod_streams` works with no `category_id` — the
   full-catalog sync tier depends on it)
1. Auth screen (one server field parsing `host:port`, user, pass; validate via
   `player_api.php`) then the Home screen (Live / Movies / Series)
2. Movies vertical slice end-to-end (rail → grid → player) — done; this is
   the pattern every other content type follows. Four things must land here
   rather than in Phase 5 because everything copies them:
   `enablePlaceholders = true` with stable keys, focus restoration by item ID,
   the focus frame plus last-active state, and the long-press context menu
3. Live TV — done; **16:9 channel card**, not the poster card, and `ext` not
   `container_extension`
4. Series — done; one extra layer (category → shows → `get_series_info` →
   season/episode picker → play). Episode ids are **strings**, `duration_secs`
   is wrong on this panel (read `duration`), and an empty `get_series_info`
   result must never wipe a cached season
5. Hardening — RTL, empty/loading/error states, `RefreshWorker`, physical TV

**Design pass (2026-09-08), decided and unbuilt — `TODOS.md` Part 2b.** Sits
alongside Phase 5 rather than inside it: D-1..D-12 are UI (login layout, type
scale, colour roles, rail width, overlaid card titles, Home icon row, tiles,
splash, app mark, guarded sign-out), D-13..D-17 are features (category filter,
item filter, read-only Subscription screen).

**For any UI work, start from `docs/ui-scope.md`, then open
`docs/design/comps.html` in a browser.** `ui-scope.md` carries the layout system,
type scale, card sizes, focus contract, state table and error copy.
`comps.html` is the approved visual reference from the 2026-09-08 `/impeccable`
pass: 12 frames at true 1920x1080, real palette hex, real catalog titles,
1 dp = 2 px, so its measurements are the ones to build.
`architecture.md` is the implementation view and deliberately says nothing about
how screens look. `PRODUCT.md` holds product truth (users, operating context,
constraints) and does not restate layout.

**A whole design pass is decided and unbuilt.** `TODOS.md` Part 2b lists it as
D-1..D-17, split into UI and feature work, with rationale in `docs/decisions.md`.
Land D-4 (type scale) and D-5 (Material colour roles) first — they touch nearly
every screen, and building anything else before them means building it twice.

**Two rules that were each violated twice and are now written down:**
- **Nothing focusable may sit below y = 297 dp** on a screen that opens a
  keyboard. The TV IME owns the lower half of the display and every arrow press
  while it is up. `imePadding()` alone does not achieve this.
- **Never truncate a category label.** This panel ships
  `RAMADAN EGYPT 2026 SD` and `... HD`, which truncate to the same string and
  recreate the false-duplicate defect. Wrap to two lines instead.

