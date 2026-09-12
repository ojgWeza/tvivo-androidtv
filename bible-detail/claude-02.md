## Current state

**Phases 0-4 are built and working against the real panel** (as of 2026-09-08),
plus an Account screen (`ui/settings/`) carrying sign out, switch account,
refresh everything, exit, and the subscription expiry. The app authenticates,
browses movies, live channels and series by category, plays movies, and shows a
season/episode picker per show. 48,780 VOD rows, 6,424 live channels and 13,279
series shows are synced and cached; **158 unit tests pass**. Live and episode
playback are the paths never exercised — `max_connections` is 1, so no automated
test may open a stream.

**As of 2026-09-10, two things changed that everything else now assumes.**

*Activating a card does not play anything.* Every content type opens a pre-run
page (`ui/detail/`) carrying poster, title, description, Play (focused on
arrival) and a favourite heart. `ContentGrid`'s callback is `onActivate`, not
`onPlay`. The long-press menu keeps a direct Play as the shortcut. A movie's
description comes from `get_vod_info`, fetched lazily and cached — this panel
sends no `plot` on `get_vod_streams`, verified over a forced re-sync of all
48,780 rows.

*The rail leads with three folders the panel does not publish* — `RECENTLY
ADDED` (100), `CONTINUE WATCHING` (50) and `FAVOURITES`, in that order, above
the panel's categories. They are `CategoryEntity` rows with `__`-prefixed ids;
see `docs/architecture.md`. The Room DB is **v5, on real migrations** — never
restore `fallbackToDestructiveMigration`, it drops `favourites` and
`resume_positions`, which are the only user data the app holds and the two
tables those folders are built from. v5 adds `rating` to `vod_streams` and
`series`; nothing back-fills it, so ratings appear per category as each one
re-syncs.

**Every focusable surface has two distinct restore paths and both are required.**
Re-entering the grid from the rail is `focusProperties { enter }`; returning from
the pre-run page is `pendingFocusItemId` and must *request focus*, not merely
scroll. `focusRestorer()` is **not** a substitute — on Compose 1.6.8 alongside an
explicit `focusGroup()` it leaves nothing focused, which on a remote means the
app stops responding. The reasoning is in `docs/architecture.md` under paging.

**To test the login screen, use Account → "Sign in to a different account" and
enter a deliberately wrong account.** That path does **not** wipe anything: it
routes to Login and keeps the current credentials until a new sign-in is
*accepted*. Only the separate `Sign out` button calls `store.wipe()`. Clearing
app data destroys credentials that cannot be recovered — the Tink keyset is not
exportable.

**Start here:** `TODOS.md`. It carries the open QA defects (Part 1), the missing
test coverage (Part 2), the remaining phases (Part 3), and the emulator
environment gotchas that each cost real time to rediscover (Part 5).

**Phase 5 (hardening) is next.** Phase 4 landed as one `CatalogSource` factory in
`BrowseViewModel`, a `series` table and the `get_series_info` season parsing —
not another browse screen. The one thing series does add is `ui/series/`: a show
is not playable (`series_id` addresses no stream endpoint), so activating one
opens the season/episode picker and `MainActivity` routes on content type. The
browse UI stays typed to `BrowseItem`, not to any entity.

Run the emulator with `tools/emulator.sh`, never bare `emulator.exe`: it stops
the Gradle daemons first and passes the two flags this machine requires. Details
in `TODOS.md` Part 5.

