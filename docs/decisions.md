# Decisions

Short log of choices made during discovery, with rationale, so Claude Code
doesn't re-litigate them without new information.

## Kotlin (native) over Flutter

**Decision:** Build native with Kotlin, not Flutter.
**Why:** Android TV specifically benefits from native D-pad focus handling
and mature leanback/TV UI libraries. Flutter would have reused prior Kemika
experience, but the TV-specific ergonomics tipped it toward native.
**Revisit if:** the LG webOS build later needs shared logic — at that point,
re-evaluate whether a cross-platform layer is worth the cost, but don't
introduce one preemptively.

## Xtream Codes API confirmed (not flat M3U)

**Decision:** Build against the Xtream Codes `player_api.php` structured API,
not flat M3U parsing.
**Why:** Verified via `player_api.php?username=...&password=...` returning a
valid `user_info`/`server_info` JSON payload — confirmed the panel is a full
Xtream Codes install. This gives structured, separate endpoints for live/VOD/
series instead of inferring categories from `group-title` tags in a flat
M3U file.
**Evidence:** see `docs/xtream-api-reference.md` for every verified endpoint
and response shape.

## Caching: 24h TTL + manual refresh

> **Amended twice.** `/plan-eng-review` regranulated the TTL to per *category*
> (see below). `/plan-design-review` added a full-catalog sync tier on top. The
> 24h TTL and the manual override both still hold.

**Decision:** Room-based local cache, 24h TTL per content type, plus a
manual refresh button that bypasses the TTL.
**Why:** Catalog data (categories, VOD/live lists) doesn't change minute to
minute, so refetching on every screen load wastes bandwidth and adds
latency. A day-long TTL balances freshness against unnecessary calls; the
manual override handles the case where the user knows new content just
dropped and doesn't want to wait.
**Not included in the TTL:** `series_episodes` — fetched lazily per show on
first open, not tied to the 24h cycle, since episode lists are cheap to
re-check per show but expensive to refresh for the entire catalog.

## Series treated as a second-pass feature

**Decision:** Build movies end-to-end first, then live (same pattern), then
series last.
**Why:** Series has an extra data layer (category → show → season/episode)
and an extra screen (season/episode picker) that don't teach anything new
architecturally once the movie slice works — it's a mechanical extension of
the same pattern, not a design risk.

## max_connections = 1 constraint

**Decision:** Documented explicitly, not solved for in the POC.
**Why:** The account only allows one active stream at a time. This matters
during testing (don't run the new app and an existing IPTV app
simultaneously) but doesn't require app-level handling beyond surfacing a
clear error if the panel rejects a connection for this reason.

---

# Decisions from `/plan-eng-review`, 2026-09-07

Reviewed against a live plan before any code existed. Outside voice: Codex
(`gpt-5.6-terra`). 15 findings, all folded in.

## DataStore + Tink, not EncryptedSharedPreferences

**Decision:** Encrypt credentials at rest with DataStore + Tink.
**Why:** `EncryptedSharedPreferences` was deprecated at
`security-crypto:1.1.0-alpha07`. It does synchronous crypto on the main thread
and has keyset-corruption crashes on specific OEM devices — and Android TV
boxes come overwhelmingly from those OEMs. The `CLAUDE.md` constraint was
reworded to require encryption rather than to name a library, so it cannot go
stale the same way again.
**Also required:** explicit Keystore key lifecycle, corruption recovery that
wipes and re-prompts rather than crash-loops, and exclusion from Android backup.

## Compose for TV, not Leanback

**Decision:** `androidx.tv:tv-material` for all browse UI; Media3's classic
`PlayerView` hosted in an `AndroidView` for playback.
**Why:** Leanback is deprecated with Compose for TV named as the replacement,
and `tv-material` is stable at 1.0.0. The player stays on `PlayerView` because
its D-pad transport handling has years of production use, and playback is the
one screen where a focus regression is most visible.
**Revisit if:** Media3's Compose-native player UI matures.

## Cache TTL is tracked per category, not per content type

**Decision:** `sync_meta(account_id, content_type, category_id, last_synced_at)`
with a scoped delete-then-insert inside one `@Transaction`.
**Why:** Fetches are per category (`get_vod_streams&category_id={id}`), but the
original design tracked freshness per content type and overwrote the whole
table. Refreshing one category therefore deleted every other category's rows
and then stamped them fresh for 24h — a silent, order-dependent data-loss bug
that presents as "some categories are randomly empty". TTL granularity must
match fetch granularity.

## Every cached row is scoped to an account

**Decision:** `account_id` (derived from server + port + username) on every
cached entity and sync record; atomic wipe on account change.
**Why:** The app is deliberately provider-agnostic — the server is user-entered
and may point at an entirely different panel. Without account scoping,
switching credentials surfaces the previous provider's catalog, artwork, and
resume positions with no error. Resume positions specifically need the
composite key `(account, content_type, item_id)`; `item_id` alone collides.

## Shared cache primitive, thin per-type repositories

**Decision:** One `CachedFetch` primitive owns the TTL check, the scoped
transaction, and chunked inserts. `Vod`/`Live`/`Series` repositories are thin
and only supply the API call and row mapping.
**Why:** The repeated logic — the highest-risk code, where the bug above lived
— exists exactly once. A single generic `CatalogRepository<T>` owning all three
content types was considered and rejected: live, VOD, and series diverge in
fields, playback rules, and refresh needs, so one owner accumulates
`when(contentType)` branching, which relocates duplication rather than removing
it. Share the mechanism, not the ownership.

## Cleartext permitted, TLS never weakened

**Decision:** `network_security_config.xml` permitting cleartext, system trust
anchors only, opportunistic HTTPS when `server_info` reports an `https_port`.
**Why:** `targetSdk 28+` blocks cleartext, and the panel is HTTP on a
non-standard port — without this the app cannot connect at all. The common
next mistake is disabling certificate validation when HTTPS turns out to have a
bad chain; that makes every connection forgeable, and since credentials sit in
the URL path of every request, the exposure is total.

## Defensive player instrumentation is kept, deliberately

**Decision:** Extractor flags (`FLAG_DETECT_ACCESS_UNITS`,
`FLAG_ALLOW_NON_IDR_KEYFRAMES`, `FLAG_ENABLE_CONSTANT_BITRATE_SEEKING`) plus
both a BUFFERING watchdog and an `onRenderedFirstFrame` check.
**Why:** Both relevant failures are silent rather than exception-bearing. TS
lacking AUD/IDR markers hangs in BUFFERING forever; HEVC on MediaTek SoCs
renders black video while audio continues, which a BUFFERING watchdog cannot
see. The outside voice argued this is speculative without a reproducing sample
— but physical-TV validation is deliberately deferred to Phase 5, so no such
sample will exist until the end. That is precisely when cheap instrumentation
pays for itself.

## Physical-TV validation deferred to Phase 5 (accepted risk)

**Decision:** Emulator-first throughout; the physical box is exercised once,
after Phase 5.
**Why:** The code is heavily deduplicated — one cache primitive, one URL
builder, one error mapper — so a defect found late has a single place to be
fixed. The outside voice disagreed, noting that decode and transport failures
are not code defects and gain nothing from single-site fixing.
**Accepted risk:** Phase 3's live `.ts` path carries the most exposure. The
Phase 0 `dumpsys media.codec` probe is a partial mitigation only — it reports
advertised decoders, not whether real files render.
**Revisit if:** the Phase 0 probe shows the box lacks a working HEVC decoder,
or the Phase 5 session surfaces more than one hardware-specific failure.

## Search is scoped to the selected category, not the catalog

> **SUPERSEDED 2026-09-07 by `/plan-design-review`.** Search is now scoped to
> the **content type**, not the category, and the full catalog is synced. See
> "Search scoped to content type" below. The reasoning here is preserved
> because it is still the reason search must never be *silently* partial —
> only its premise changed, not its principle.

**Decision:** Two local searches — a filter over the ~120 category names, and
an item search within the *currently selected* category. No catalog-wide item
search.
**Why:** The Xtream API has no search endpoint at all (the full action list is
`get_*_categories`, `get_*_streams`, `get_series`, `get_series_info`,
`get_short_epg`, `get_simple_data_table`, `get_vod_info`), so any search must
run locally. Catalog-wide search would therefore require syncing and indexing
the entire catalog — a second sync tier on top of the per-category cache. A
naive middle option, searching only whatever categories happened to be visited,
was rejected outright: a silently partial result set is worse than no search,
because a missing result is indistinguishable from a missing title.
Category-scoped search needs no new infrastructure, since those items are
already cached, and it keeps the caching design from Issue 5 unchanged.
**Accepted cost:** finding a title requires knowing roughly which category it
lives in. The category filter is what makes that workable.
**Design requirement:** the search field must always name its scope
("Search in Action Movies"), never a bare "Search" — hiding the boundary
reintroduces the misleading-omission problem the scoping avoids.
**Implementation:** match against a `name_normalized` column (lowercased,
diacritics stripped, whitespace collapsed, quality prefixes removed), populated
on insert. `LIKE` rather than FTS — a category is at most a few thousand rows,
and SQLite FTS tokenizers handle Arabic poorly without careful configuration.
**Revisit if:** users repeatedly fail to find titles they know exist, which
would indicate the category taxonomy is too opaque to navigate by.

---

# Decisions from `/plan-design-review`, 2026-09-07

Reviewed against a live plan before any code existed. Outside voices: Codex
(`gpt-5.6-terra`, 17 findings) and an independent Claude reviewer (~40
findings). Grounded partway through by a photograph of a working IPTV client
the user uses daily, which settled several open questions by evidence rather
than by argument.

## Left rail + grid, replacing the separate category and list screens

**Decision:** One browse screen: a dismissible left rail carrying four virtual
entries and the panel's categories, with the content grid to its right.
**Why:** It is the shape of an app the user has lived with, so it is validated
by use rather than by reasoning. It also collapses two planned screens — "home
/ content type" and "category grid" — into one, removing a D-pad journey per
session. The earlier objection to a two-pane layout (that a preview pane would
fetch on every focus move) does not apply, because the right pane is the real
grid and only loads when a category is committed to.
**Rejected:** a uniform tile grid of categories. Every tile would carry exactly
one piece of information, the name, inside a box that adds nothing.

## Item counts in the rail

**Decision:** Each rail entry shows its item count; blank until synced, never
`0`.
**Why:** The count is the only differentiating fact available without a second
fetch, and it separates a 37-title category from an 1,860-title one at a
glance. `0` is a claim that a category is empty, which is a different and false
statement from "not yet synced".

## Home screen: three large content-type buttons

**Decision:** After authentication, a landing screen of three large buttons —
Live TV, Movies, Series — nearly filling the screen.
**Why:** Content-type scope has to be chosen somewhere, and putting it on its
own screen is what makes every search scope unambiguous downstream: you chose
Movies, so "Search Movies" means exactly what it says. Both outside reviewers
independently flagged that the app had no clear top-level content-type
destination and that `ALL` was meaningless without one.
**Rejected:** three entries at the top of the rail (lengthens an already long
rail) and a header tab row (adds a second horizontal focus zone above the grid).

## Full catalog sync accepted, reversing the earlier decline

**Decision:** Sync all three content types in full. The rail carries `ALL`,
`FAVOURITES`, `CONTINUE WATCHING`, `RECENTLY ADDED`.
**Why:** `FAVOURITES` and `CONTINUE WATCHING` are free — they run on local data
the schema already holds. `ALL` and `RECENTLY ADDED` are not: both need the
whole catalog locally, which is precisely the second sync tier the eng review
declined. Accepted deliberately after the cost was stated.
**Consequences that had to be designed rather than discovered:** streamed JSON
parsing instead of a materialised `List<T>` (a ~15 MB payload is 30–50 MB of
heap against a limit that may be 96 MB); chunked transactions with a generation
counter instead of one multi-second write lock; virtual entries that own no
`sync_meta` row; and a `catalog_sync` state machine gating anything requiring
completeness.
**Unverified precondition:** that `get_vod_streams` accepts no `category_id` on
this panel. Verify in Phase 0 — the whole tier rests on it.

## Search scoped to content type, superseding category-scoped search

**Decision:** Three searches — Live, Movies, Series — each covering all of its
own content type. No category-scoped item search, no cross-type search.
**Why:** The earlier decision scoped search to one category solely because
going wider needed a full-sync tier. That tier now exists, so the premise is
gone, and holding 48,751 locally-searchable rows while refusing to search them
makes no sense. Content-type scoping preserves the honesty the original
decision was protecting: the Home screen makes the boundary visible in the
navigation itself rather than in a label on a text field, so a "no results"
cannot be misread.
**Requires:** all three types synced in full. Searching only VOD would give a
confident "no results" for a channel or a show that exists — the rejected
failure in a new location.
**Open:** `LIKE` with a leading wildcard cannot use an index at 48,751 rows.
Keep `LIKE`, add a covering index on
`(account_id, content_type, name_normalized)`, and measure on the real box
before reaching for FTS, whose Arabic tokenization was the original reason to
avoid it.

## Card geometry fixed early, because it is an image-pipeline input

**Decision:** `POSTER(220×330 px)` for movies and series, `CHANNEL(220×124 px)`
for live. 5 columns with the rail open, 7 with it closed, 20 px gutters, rail
600 px, safe area 96×54 px.
**Why:** Posters are downsampled to card size before caching, so this number is
baked into every cached bitmap. The first proposal (240×360 at 5 columns) was
measured and did not fit: the rail sat inside the 96 px overscan margin, and
column 5 ended 74 px from the screen edge, so an overscanning TV would clip it.
**Live needs its own size:** `stream_icon` for a channel is a logo, frequently
wide, square, or on a transparent ground. Cropping it to 2:3 destroys it, so
live art is fitted and letterboxed rather than cropped. Both sizes had to be
decided together or Phase 3 would need a second cache migration.

## Titles overlaid on the poster, two lines

**Decision:** Card titles sit on the poster over a bottom scrim, two lines at
16sp. Live is the exception — its card is too short, so channel names sit below.
**Why:** Measured against 14 real panel titles, a single line at 240 px
overflowed 6 of 14, which would have fired the tooltip on 43% of cards — a
popup, not a hint. Two lines drops that to 2 of 14. Overlaying rather than
placing titles underneath buys back ~90 px per card, taking the grid from 1.85
visible rows to 2.46.
**Escape hatch:** two long titles that truncate identically are resolved by
switching to the names-only view, not by a popup.

## Names-only view mode

**Decision:** A second view mode with no artwork: 2 columns, 44 dp rows, ~18
titles visible, toggled from the header and remembered per content type.
**Why:** Posters on this panel are frequently missing or fail to load, and a
text list is immune to that. It is also roughly double the density of the
poster grid.
**Not denser:** ~31 dp rows were considered and rejected — they leave no room
for a legible focus state, which would make the dense mode the mode nobody can
read.

## Static focus frame, no scale

**Decision:** Focus is a static 4 dp frame on cards and a solid amber fill on
rail rows. No scale, no elevation, no decorative shadow. A second "last-active"
state at 40% opacity marks where focus will return when the other pane holds it.
**Why:** Scaling a focused card overlaps neighbours, disturbs label spacing,
and adds compositing work at exactly the moment the user is traversing an
image-heavy paged grid on a 1–2 GB box. Physical-TV validation is deferred to
Phase 5, so the option that cannot stutter is cheap insurance. The last-active
state exists because otherwise a user standing in the rail presses RIGHT blind.

## OK plays; long-press OK opens a context menu

**Decision:** No movie detail screen. OK plays immediately. Long-press OK opens
play / start over / add to favourites / remove from continue watching.
**Why:** `plot`, `cast`, `director` and `rating` are usually empty on this
panel, so a detail screen would be a title, a poster and a button, costing a
press on every playback. But without one there is nowhere a favourite can be
created, and `FAVOURITES` had been specified as a rail entry with no way to
populate it — a permanently empty row. Long-press OK is the standard Android TV
gesture and the only affordance that works from a grid with no detail screen.
**Requires:** the `FAVOURITES` empty state teaches the gesture, or nobody
discovers it.

## Sync never blocks; progress is a dismissible percentage

**Decision:** Categories load in about a second and the content type is
immediately browsable. The full catalog syncs behind it with a visible
percentage that disappears at 100%. `ALL` and search activate on completion.
**Why:** Credentials are typed once, but the refresh is daily, so a blocking
2–3 minute spinner is a cost paid repeatedly rather than once. Nothing about
browsing one category requires the other 119 to have arrived.

## `ALL` renders a grid in panel order

**Decision:** `ALL` behaves like any other rail entry: a grid, in the order the
panel returns it.
**Why:** Matches the app the user already uses, and keeps every rail entry
behaving the same way, which is worth something on a D-pad.
**Accepted cost:** 48,751 items at 5 across is 9,750 rows. At an aggressive
key-repeat that is roughly 8 minutes end to end, with no jump affordance and no
ordering a viewer understands — so the first screen of `ALL` is effectively a
random 10 of 48,751, and the end of the list is unreachable in practice.
Sorting newest-first, and treating `ALL` as a search scope rather than a grid,
were both considered and declined.
**Revisit if:** `ALL` proves to be a dead end in use.

## Continue watching is bounded

**Decision:** A row appears after >60 s watched and while <92% complete,
ordered by last-played, capped at 50, oldest evicted, removable from the
context menu, and never mutated while the user is standing in it. Live is
excluded.
**Why:** The reference app shows `CONTINUE WATCHING 77`, which means it never
removes anything — nobody has 77 films in progress. Copying the pattern without
bounds copies the rot. Re-sorting on return would also make the focused card
vanish from under the user the moment they finish something and press BACK.
Live is excluded because a live stream has no meaningful resume point.

## Focus restoration is by stable item ID, and it constrains Paging

**Decision:** `enablePlaceholders = true`, stable item keys, restoration by
`stream_id` with a nearest-index fallback, pending state held in
`SavedStateHandle`.
**Why:** This looked like a UI detail and is actually a Phase-2 architecture
decision. With placeholders off, an item at index 800 does not exist in the
list until the user scrolls there, so restoration cannot find it and silently
degrades to "top of grid" — unfixable in UI code afterwards. `SavedStateHandle`
rather than a ViewModel field because the browse Activity will be killed under
memory pressure while a 1080p stream decodes on a 1–2 GB box.

## `name_display` as a third name column

**Decision:** Store `name`, `name_display` and `name_normalized`. Render
`name_display`, match on `name_normalized`, keep `name` for diagnostics only.
**Why:** Rendering the raw name breaks truncation. `"HD  للعدالة وجه آخر"`
opens with a strong LTR run, so the paragraph resolves LTR, the Arabic lays out
RTL inside a left-aligned box, and the ellipsis lands on the wrong visual edge.
Truncation then looks random across a large fraction of the catalog. The
normalisation pipeline already existed for search; it simply never kept a
display-safe variant. Decided before the schema is written because it is a
schema change.

## Colour: dark teal surfaces, Claude orange accent

**Decision:** A teal surface ramp (`#0A1619` → `#0F2126` → `#163036`) with
`#D97757` as the single accent, and a lightened `#E08466` for the accent used
as text. Full token table and measured contrast ratios are in `ui-scope.md`.

**Why:** The interface previously used an amber borrowed from a screenshot of
a different app — it was never chosen, and `/plan-design-review` recorded that
as an unresolved decision. Teal was picked as the surface hue because it is
cool and recedes, which is what a background full of poster artwork needs: the
posters supply all the colour, so the chrome must not compete. One warm accent
against an entirely cool interface means the accent can carry exactly one
meaning — *this is where focus is* — which matters more on a ten-foot D-pad UI
than anywhere else, because focus location is the only navigational state the
user has.

**Two accent tokens, deliberately:** `#D97757` as *text* on the elevated
surface measures 4.45:1, which misses the 4.5 threshold by a hair. Rather than
round the requirement down, accent text uses `#E08466` (5.07:1). The base
accent stays for fills and frames, where the bar is 3:1 and it measures 5.89
against the background.

**Every pair was computed, not eyeballed.** The ratio table in `ui-scope.md` is
the evidence. Nothing decorative uses the accent.

**Revisit if:** `/design-consultation` runs and produces a fuller system. This
decision covers colour and contrast only — motion, iconography and the wordmark
remain open, tracked in `TODOS.md`.

## Category grids sort alphabetically on `name_normalized`

**Decision:** Every category grid (Live, Movies, Series) orders rows ascending
on `name_normalized`, compared as plain UTF-16 code units — SQLite's default
`BINARY` collation on that column. No ICU collator, no per-locale rules.
**Why:** The panel's native `num` order is arbitrary to a viewer — see "`ALL`
renders a grid in panel order" above for what that costs at scale. Alphabetical
on `name_normalized` is the only ordering that makes a mixed Arabic/English
catalog scannable, and the column already exists for search matching (see
"`name_display` as a third name column"), so this is a query change, not a
schema change.
**Accepted cost:** Binary comparison sorts by code point, so the Latin block
sorts before the Arabic block — Latin-named titles precede Arabic-named ones as
a group, rather than interleaving by a shared alphabet. Good enough for a POC;
a real per-script collator is more machinery than a sort order needs right now.
**Revisit if:** Physical-TV validation (Phase 5) shows the Latin/Arabic
grouping reads as broken rather than as a reasonable two-block split.
