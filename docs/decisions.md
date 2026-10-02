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
**Precondition, verified 2026-09-07:** `get_vod_streams` with no `category_id`
returns the full VOD catalog on this panel, so the tier stands as designed and
the ~120-sequential-requests fallback is not needed for movies. `get_live_streams`
and `get_series` were not tested the same way — assume nothing until they are.

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
**Exception, added 2026-09-08:** the series **episode list** sorts by season then
`episode_num`. It is the one list in the app where the panel's own numbering is
meaningful, and alphabetical there would be actively wrong.

---

# Decisions from Phase 4 (Series), 2026-09-08

## A show is not a playable item, and the UI says so structurally

**Decision:** `series_id` addresses no stream endpoint, so a show carries no
extension in `BrowseItem` and activating one opens `ui/series/` — the
season/episode picker — instead of the player. `MainActivity` routes on content
type; the grid stays type-agnostic.
**Why:** The alternative was a `isPlayable` flag on `BrowseItem` that every
consumer has to remember to check. Routing on the type puts the decision in one
place, and the grid keeps knowing nothing about content types.
**Accepted cost:** `BrowseScreen`'s `onPlay` callback now means "the user
activated this item", which is slightly wider than its name. Documented at both
ends rather than renamed, because for two of the three types it does play.

## Episodes are a table with a string key and no generation column

**Decision:** `series_episodes` is keyed `(accountId, seriesId, episodeId)` with
`episodeId` a **string**, and has no `generation`. Its `replaceEpisodes` is a
plain delete-then-insert, and it **refuses an empty list**.
**Why:** The panel quotes the episode id and it is what goes in the playback
URL — round-tripping it through Int buys nothing and risks a broken URL. The
generation mechanism exists for 48,751-row writes that would otherwise hold a
multi-second write lock; a single show's episodes are hundreds of rows, where
delete-then-insert is the simpler correct thing. The empty-list guard is the
same reasoning as the catalog zero-row guard: a rejected `get_series_info`
parses to zero episodes, and wiping a cached season on that makes an
already-cached show unplayable offline.
**Revisit if:** a panel is found whose per-show episode counts run to thousands.

## Episode freshness is stamped under its own content type

**Decision:** `refreshSeriesInfo` reuses `CachedFetch` with the **show id in the
category slot**, under the content type `series_info` rather than `series`.
**Why:** `sync_meta` is keyed `(account, contentType, categoryId)`. Filing shows
and categories under one type would let show 770 and category 770 share a stamp,
and each would make the other look fresh for 24 h.

## `duration` beats `duration_secs` on this panel

**Decision:** the episode runtime is read from `duration` (`HH:MM:SS`) first,
with `duration_secs` as the fallback.
**Why:** Verified 2026-09-08 — a 60-episode drama reported 9-190 "seconds" per
episode in `duration_secs`. `duration` is the human-authored field and it is the
one a viewer can check against the player.
**Accepted cost:** a panel that populates only `duration_secs` correctly and
leaves `duration` malformed would be worse off. Not observed; the fallback
covers it.

## One `syncCatalog` body for all three content types

**Decision:** `CatalogSyncer`'s three public methods delegate to a single private
`syncCatalog`, parameterised by the request, the parse-and-insert step and the
generation flip.
**Why:** The zero-row guard was latent in the VOD path and only found while
building live. Series would have been a third copy of it. Three copies of a
rule that silently deletes a user's whole catalog when it is got wrong is three
places to get it wrong.

---

# 2026-09-08 — QA findings and the `/impeccable` design pass

## The full-catalog tier is TTL-gated, like the per-category tier

**Decision:** `CatalogSyncer` gained a `force` flag and an `isFresh` gate using
the same 24 h window as `CachedFetch`. Only `complete` counts as fresh;
`partial`, `failed` and an interrupted `indexing` all re-run.
**Why:** `BrowseViewModel.init` called `sync*` unconditionally, and the syncer
had no freshness check of its own, so **every entry into a listing
re-downloaded and rewrote the whole catalog**. Measured: 13,264 series rows
rewritten 11 minutes after a `complete` run, ~15 MB per entry, with the progress
line reading "Indexing" over data that was already there.
**The real lesson:** two caching tiers existed and only one knew the rule. When
a second tier is added, the freshness rule is stated once for both or they
diverge silently.
**Consequence:** `Refresh everything` is now the only way to re-sync inside the
window, so it had to start covering **series**, which it never did.

## Quality is a badge, not a name

**Decision:** `NameNormalizer.qualityOf(raw)` re-derives the stripped quality
token at map time; `BrowseItem` carries it; the grid draws it as a corner badge.
**Why:** stripping `HD`/`SD` into `name_display` is what makes mixed-direction
titles truncate correctly, but this panel publishes the same title once per
quality, so two genuinely different rows rendered as the same string and read as
duplicates. Confirmed by the rail itself: `RAMADAN EGYPT 2026 SD` (25) and
`RAMADAN EGYPT 2026 HD` (43) are separate categories of the same titles.
**Why derived, not stored:** the raw `name` is already on all three entities, so
there is no schema change and the badge cannot drift out of sync with
`name_display`.
**Revisit if:** a panel appears whose quality tokens are not edge-anchored.

## Concurrent streams are an account property, never an app rule

**Decision:** the UI **reports** `max_connections` from `server_info` and never
states a limit as a product fact. Copy reads "This account allows N stream(s) at
a time. Other accounts may allow more."
**Why:** `max_connections` is 1 on the development account, and an earlier draft
wrote "one stream at a time" into the product record as a constraint. This app is
provider-agnostic by construction; asserting a per-account value as a rule would
be wrong for most panels it ships against.

## Search is two filters on the browse screen, not a screen or a global action

**Decision:** a persistent category filter pinned above the rail, plus an item
filter that expands in place from the grid header's search icon. No search on
Home, no global search surface.
**Why:** Home has nothing to search — putting it there means choosing a content
type *inside* search rather than before it, which is the ambiguity the Home
screen exists to remove. Filtering in place also keeps the grid visible and
needs no back-navigation.

## Rail labels wrap and are never truncated

**Decision:** category labels wrap to two lines; a focus tooltip appears **only**
if a label still overflows two lines.
**Why:** `RAMADAN EGYPT 2026 SD` and `...HD` truncate to identical strings.
Ellipsising the rail recreates the false-duplicate defect that the quality badge
was introduced to fix. This was caught twice — once designing the 240 dp rail,
once in a mock that hardcoded a truncated label — which is why it is written down
rather than remembered. The rail settles at **280 dp**, not the 240 dp a TV
convention would suggest, because 240 could not hold these names.

## `Sign out` is guarded; `Sign in to a different account` is not destructive

**Decision:** `Sign out` sits below a divider, states its consequence, and opens
a confirm dialog with default focus on the safe option. The switch-account path
gets none of that.
**Why:** `signOut()` calls `store.wipe()`, destroying a non-exportable Tink
keyset — unrecoverable, and the app is used by a household where others hold the
remote. `onSwitchAccount` only routes to Login and keeps the current credentials
until a new sign-in is *accepted*; conflating the two led to a wrong conclusion
in-session that reaching the login screen costs the credentials. It does not.

## Subscription is a separate read-only screen

**Decision:** `Show subscription` opens its own screen. Nothing on it is
editable.
**Why:** read-only means no field, so no IME, so the whole class of
keyboard-occlusion defects (Q-10, Q-11) cannot occur there at all. It also frees
the Account screen to hold only account actions.

## The brand mark is a pulse, not a monogram

**Decision:** the mark is a live signal inside a screen; the beat cuts a V
trough. An earlier `T` letterform was discarded.
**Why:** Tvivo is TV + *vivo* (*vivre*: alive, quick, vivid). A `T` encodes the
spelling, not the meaning. One stroke also survives launcher-size reduction,
which lettering does not.

## No third-party brand marks in shipped assets

**Decision:** tile photography is screened for logos before use; two candidates
were rejected for a visible Netflix logo, recorded in
`docs/design/img/CREDITS.md`.
**Why:** shipping a competitor's trademark inside the app is a legal problem, and
it was only caught by opening the downloaded files rather than trusting the
search result titles.

## Exit is guarded by a confirm dialog, not by position alone

**Decision (2026-09-08):** `Exit` sits last in the Home icon row **and** opens a
two-option confirm whose default focus is `Stay in Tvivo`. Position alone was
the alternative and was rejected.

**Why:** moving Exit onto Home (D-7/D-17) is right — quitting is a global action
and does not belong on the Account screen — but it makes Exit one press from the
first screen, on a household remote. Position alone only makes an accidental
press less likely; it does not make it recoverable. The dialog costs one press
on a deliberate exit and makes the reflex press after any accidental one
harmless, which is the same trade already accepted for `Sign out`. Having one
confirm treatment for both also means there is a single component
(`ui/common/ConfirmDialog.kt`) enforcing the safe-default-focus rule, rather than
two screens each remembering it.

## The login screen is fixed by layout, not by insets

**Decision:** the login form is a fixed ~290 dp top-anchored column. No
`verticalScroll`, no `imePadding()` on the screen itself.

**Why:** Q-11 was fixed twice and survived both times.
`WindowCompat.setDecorFitsSystemWindows(window, false)` was a real bug fix — it
is what stopped `imePadding()` being a silent no-op — but it did not save the
screen, because a `verticalScroll` column only brings the *focused* child into
view and the action row sat below it. As long as anything focusable is
*positioned* below the IME ceiling, some mechanism has to rescue it at runtime,
and each such mechanism is another thing that can quietly do nothing. Laying the
whole form out above y = 297 dp removes the need for a rescue. The insets fix
stays in `MainActivity` because it is a precondition for every other screen.

## The login error line is reserved whether or not there is an error

**Decision:** a fixed 22 dp box holds the error, always laid out, holding at most
one line (`ErrorCopy.forLogin`, budget asserted in `ErrorCopyTest`).

**Why:** an error that *appears* pushes the action row 22 dp further down — that
is, toward the keyboard — at exactly the moment the user needs to press it. A
reserved line makes the ceiling arithmetic static instead of state-dependent, and
the one-line budget means copy can never make it worse by wrapping. Copy that
does not fit is rewritten, not wrapped.

## Desktop playback starts with LibVLC, but only after the native surface is ready

**Decision (updated 2026-09-14):** the Windows desktop application uses a
version-pinned, dynamically loaded LibVLC runtime through a narrow JNA binding.
The VideoLAN VLC 3.0.23 Windows x64 archive and its published SHA-256 accompany
the desktop distribution. The application extracts it into its local runtime on
first playback, so a user never needs to install VLC separately. A developer may
override the location through `tvivo.libvlc.dir`. The native video surface is
embedded only after its AWT canvas is displayable, so JNA can safely obtain an HWND.

**Evidence:** a local synthetic MP4 played in the Compose Desktop window on
Windows x64 using LibVLC 3.0.23 whose SHA-256 was verified against VideoLAN's
published checksum. The POC is partial: MKV, TS, seek/duration, tracks,
media-end/error callbacks, and close/reopen teardown are not yet verified. No
provider endpoint, credential, or real stream enters automated validation.

**UX consequence:** the test also showed that a naïve heavyweight native surface
can leave a large black/matte frame and compete with Compose controls. This is
not a desktop feature-parity green light. A design run must define the player
viewport and control system before desktop feature work begins; Android TV gets
the same viewport-quality review under N-16.

## Archived todo-tree closures (folded 2026-09-14)

`todo-tree/` was trimmed from 66 files to open-items-only per
`D:\HCode\bible-detail\todo-and-bible-maintenance.md`. These items were closed/decided
and are recorded here rather than kept as live todo-tree entries; each was independently
verified present before its section file was deleted.

- **Q-10 — TV IME owns the D-pad while open.** Platform constraint, not a defect: while
  the on-screen keyboard is up, every arrow press goes to the keyboard, not the app.
  `Show`/`Clear`/`Sign in` are reachable once the IME is dismissed with Back — the
  standard Android TV model. Do not "fix" by intercepting keys harder; the original login
  focus trap came from fighting this same platform behaviour.
- **Q-11 — Sign in / Clear sit under the TV keyboard.** Fixed (D-1): centred 820px login
  column, everything focusable above the IME ceiling. Fixed twice, survived both times.
- **Q-12 — HD/SD stripping made distinct titles look like duplicates.** Fixed.
- **Q-13 — Opening any listing re-downloaded the whole catalog.** Fixed.
- **Q-15 — Icon pill drew a second rectangular focus indicator.** Fixed: `clickable`'s
  default indication doesn't follow a pill's rounded shape; set
  `indication = null` and rely on `tvFocusFrame`, applied to every `clickable` site.
- **Q-16 — Exit pill glyph rendered as tofu.** Fixed: restrict placeholder glyphs to ones
  that actually exist in Roboto/Noto on Android TV; verify each on-device.
- **Q-17 — RIGHT from a pill sometimes landed on a tile instead of the next pill.**
  Fixed: explicit `focusProperties` across the pill row instead of trusting the 2D focus
  search, which can be won by a tile mid-animation.
- **Q-18 — Overlaid card titles hard to read over bright artwork.** Fixed: scrim given
  its own height (68dp on a 165dp poster) instead of being sized to the text.
- **Q-19 — Category/item filter fields trapped focus.** Fixed: `ui/common/DpadField.kt`
  lifted from `LoginScreen`'s private `dpadFieldNavigation` into a shared contract; any
  focusable text field on a TV must carry it.
- **Q-20 — Splash mark showed its launcher background as a box.** Fixed: separate
  `ic_mark.xml` without the launcher background rect, for in-app use.
- **Q-22 — Diagnostic log had almost no call sites.** Fixed, verified 2026-09-10.
- **Q-23 — Sign out rendered as an empty focus frame.** Fixed: an unscrollable `Column`
  squeezes its last child rather than overflowing; added `verticalScroll` so every row
  gets its full height. Same class as Q-11 — content below the fold on a fixed-height
  screen.
- **Q-25 — Browse screen opened with nothing focused.** Fixed, root cause behind Q-24:
  an Android TV screen with no focus owner has no D-pad behaviour — the first press runs
  an originless 2D search. Fix: request focus onto the rail once categories arrive, UP
  from the first row only opens the filter, and the field reads "Search" at rest.
- **Q-26 — Virtual folders rendered "Loading…" forever.** Fixed: `PagingData.from(list)`
  without explicit `LoadStates` leaves `refresh` as `Loading` forever; pass explicit
  `NotLoading(endOfPaginationReached = true)` for a virtual folder's full in-hand list.
- **Q-27 — Back from the episode picker skipped the pre-run page.** Fixed: now returns
  to `Route.ItemDetail`.
- **QA-10 — Whole-number ratings rendered with a trailing `.0`.** Fixed,
  verified on-emulator 2026-09-14: shared `Double.asRatingLabel()`
  (`BrowseItem.kt`) used by both the grid card badge and the detail page now
  shows `7`/`6` for whole numbers; zero/unrated correctly shows no badge at
  all, distinct from an independent quality badge. Fractional formatting
  (`%.1f` branch) is unchanged code, code-reviewed but not live-verified —
  this panel's catalog has zero non-integer ratings across 45,822 rated rows,
  so no on-device fixture exists to exercise it.
- **T-T1 — Room DAO and transaction tests.** Done: `CatalogDaoTest` (16) +
  `CatalogSyncerTest` (11).
- **T-T2 — Instrumented D-pad focus traversal tests.** Verified on API 34 emulator
  2026-09-13; needed `androidx.test.runner.AndroidJUnitRunner` declared explicitly or
  Android silently selects the legacy runner and discovers no JUnit4 tests.
- **N-7 — Global search across all three content types.** Declined 2026-09-13.
- **N-15 — Windows desktop playback POC (POC-D1).** Superseded 2026-09-14: the owner
  dismissed the POC-D1 gate and Windows desktop is now an active product target under
  the `D-Desktop-*` items — see `todo-tree/63-section.md` and `handoff.md`. This entry
  is kept only as history; do not re-scope desktop work back down to a POC.
- **D-1..D-12 (UI work batch) and D-13..D-17 (feature work batch)** — all built, see
  `todo-tree/38-section.md`'s and `39-section.md`'s prior content for file-level detail
  if ever needed; superseded by this archive as the summary record. Exceptions: D-9
  (Home tile photographs), D-10 (splash mark/wordmark/progress bar polish), and D-11
  (app mark + TV banner) were never built — kept as an open item, see `todo-tree/`.
- **Exit placement, icon set, and title-overlay-coverage open questions** (formerly
  `todo-tree/40-section.md`): Exit uses a two-option confirm dialog defaulting to "Stay
  in Tvivo", last in Home's icon row. Icon set is placeholder pending T-D1. Title overlay
  is capped at 2 lines + ellipsis because the quality badge is a separate element.
- **Phases 0-3 + Account screen, and Phase 4 (Series).** Done — Account screen is the
  path used to test login (Account → "Sign in to a different account", does not wipe
  current credentials).
- **Environment notes** (emulator quirks, formerly `todo-tree/58-section.md`/`61-section.md`
  "Part 5"): media volume defaults to 3/15 (`adb shell input keyevent 24` x14); host
  keyboard shortcuts need `forwardShortcutsToDevice` in
  `HKCU\Software\Android Open Source Project\Emulator\set` (conflicts with Esc-as-Back —
  pick per task, do not add a `KEYCODE_ESCAPE` handler to app code); prefer pasting
  credentials over typing; `-gpu swiftshader_indirect` required; `-memory 1536` with
  Gradle/Kotlin daemons stopped first (34s→1.4s launch); `adb exec-out` not `adb shell`
  for binary pulls on Windows; Android TV AVDs have no touchscreen. `tools/emulator.sh`
  already applies these.
- **Considered and not taken:** idle dim/screensaver (not worth tracking yet), voice
  search (declined as a whole integration for one field), `KEYCODE_ESCAPE` handling in
  the player (emulator config artefact, not a product requirement).

## D-Desktop-10 — vlcj concurrency/freeze bug closed; playback moved to D-Desktop-14 (2026-09-15)

**Closed:** the UI-freeze/hang regression that motivated the vlcj migration. A fresh
Codex review of `LibVlcPlayer.kt`/`DesktopShell.kt` found 4 more real races beyond the
4 already fixed pre-session: resume-seek firing on an already-released player,
`positionMs()`/`durationMs()` racing release outside the serial executor, `close()`
queuing a redundant blocking `stop()` behind one already in flight, and the
playback-launch coroutine calling `playUrl()` after `terminal` was already set. All four
fixed and live-verified: fixture playback confirmed working, and 3 real-provider-stream
attempts all ended in `opening → playing → buffering → watchdog timeout → clean stop`
with the UI staying responsive throughout — no freeze, no crash, no double-block.

**Not closed — moved to `D-Desktop-14`:** the stream never reaches sustained playback,
stalling in `Buffering` at 100% cache regardless of tuning (`network-caching` raised to
10000ms, `--clock-jitter=0`/`--clock-synchro=0` tried, both reverted — no benefit). User
confirmed the same content plays fine in other apps on the same connection, ruling out a
provider/network cause and pointing at the bundled libvlc 3.0.23 itself.

**Decision:** replace vlcj/libvlc with mpv (libmpv) entirely, using mpv's own built-in
on-screen controls (OSC) rendered on the video surface instead of hand-built Compose
transport controls (Play/Pause/Stop/seek/Full screen) — the user rejected the ongoing
cost of maintaining that control layer, independent of the libvlc bug. Full scope,
including a Codex edge-case review (HWND embedding timing, native-input ownership
conflicts between mpv's OSC and Compose key handling, resume-tracking via
`mpv_observe_property`, and shutdown-ordering discipline carried over from this
session's races), is filed as `D-Desktop-14` in `todo-tree/63-section.md`. Not started.

## D-Desktop-14 — mpv OSC input-routing bug closed (2026-09-15)

**Closed:** the mpv/libmpv migration itself (see the D-Desktop-10 entry above) had
already restored stable playback, but the owner caught a blocking follow-on bug after
that verification: mpv's OSC never rendered and no mouse or keyboard input reached the
embedded video surface at all, leaving no way to pause/seek/stop from the UI.

**Root cause:** not a Compose/SwingPanel layering problem, despite that being the
original suspicion carried into this session. mpv's `--wid` embedding on Windows creates
its child window `WS_CHILD | WS_VISIBLE`, then explicitly calls `EnableWindow(child, 0)`
whenever it has a parent (confirmed directly in mpv's `video/out/w32_common.c`) — a
disabled window never receives OS mouse/keyboard input, by design, regardless of how the
host toolkit layers its own components around it. Matches mpv issues #4795 and #6762,
whose resolution is to forward input explicitly through mpv's own client-API commands
rather than fight the disabled window.

**Fix, part 1 (input forwarding):** `MpvPlayer.kt` gained `sendMouseMove`/
`sendMouseButton`/`sendKey`/`sendWheel`, forwarding AWT input through `mpv_command`'s
`mouse`/`keydown`/`keyup`/`keypress` — mpv's own OSC and keybindings still own playback
control, preserving the owner's earlier rejection of hand-built Compose transport
controls rather than reversing that decision. `DesktopShell.kt` wires AWT mouse/
keyboard/wheel/focus listeners on the video `Canvas` to those methods, with rapid
mouse-move coalesced to one queued owner-thread task at a time and Canvas-pixel
coordinates scaled to mpv's own `osd-width`/`osd-height`.

**Fix, part 2 (OSC visibility):** live-testing the first fix (compiled clean, Codex
diff-reviewed) surfaced a second, distinct bug: the synthetic `mouse` command updates
mpv's pointer position but does not itself trigger OSC's own hover/mouse-activity
detector, so OSC still never rendered — this turned out to be exactly mpv issue #9910
("osc (overlay) does not respond to mouse actions transmitted by the command"). Fix:
`MpvPlayer.setOscVisibility(mode)` sends `script-message osc-visibility always|auto`
(osc.lua's own documented modes), tied to Canvas mouse-enter (`always`) and mouse-exit/
focus-loss (`auto`) in `DesktopShell.kt`.

**Review process:** three Codex passes across this half of the item, per
`bible-detail/claude-07-agent-division.md`'s binding loop — (1) a plan review before any
code was written, which flagged that OSC's absence alone wasn't proof of a routing
failure and proposed an OS-level `WindowFromPoint` probe (superseded once the real root
cause was found via direct research into mpv's own source and issue tracker instead of
guessing further); (2) a diff review that caught two real bugs — stale click coordinates
in `mousePressed`/`mouseReleased` (fixed to use the click event's own `e.x`/`e.y` rather
than whatever mouse-move happened to be coalesced last) and held keys never released on
`onDispose` (fixed by calling `releaseAllHeldKeys()` before listener teardown); (3) a
final short diff confirm after debug-logging additions, which came back clean.

**Verification:** owner-verified live with real mouse and keyboard on the fixture
(`desktop-fixtures/sintel-trailer.mp4`) — OSC now appears on hover, and both a mouse
click and the Space key pause/resume playback. Two verification paths were attempted and
explicitly not completed: a from-scratch Codex GUI-automation pass (blocked by an
unrelated Herdr pane environment limitation — that particular pane has no interactive
desktop/GDI access, confirmed by both a failing `CopyFromScreen` and a failing exclusive
file-lock open that succeeded immediately from the interactive session; not a product
defect, and not fixable from within this repo) and a real-provider-stream repeat of the
same input checks (fixture-only verification was accepted as sufficient for closing this
item; revisit if a provider-stream-specific input issue surfaces later).

## D-Desktop-16a — Home empty-shelf/first-run fix closed (2026-09-15)

**Closed:** first of five sub-items split out of D-Desktop-16 (owner: "make the main
screen more intelligent and intuitive instead of holding empty sections and being not
usable"), per the consolidated Claude/Codex decision recorded against the owner's
`docs/design/desktop-home-proposal.md`. 16b-16e remain open in `todo-tree/01-open.md`.

**Fix (`DesktopShell.kt`, `HomeScreen`/`HomeShelf`/`EpisodeHomeShelf`):**
- `HomeShelf` and `EpisodeHomeShelf` now `return` immediately when their item list is
  empty — no title, no "will appear here" placeholder copy — instead of always rendering
  a section that explains its own absence.
- A `loaded` flag plus a `hasAnyContinuation` check drive a new first-run/empty-library
  message ("Nothing to continue yet" + next-action copy), shown only once the initial
  load has resolved successfully and all three continuation lists (live, movies,
  episode-resume) are genuinely empty.
- Incidental bug fix found while touching this code: the manual Refresh button called
  `repository.refresh(type)` for every catalog type but never reloaded the continuation
  shelves afterward, so they stayed stale until app restart. `loadRecent()` (converted
  from a fire-and-forget `scope.launch` to a `suspend fun`) is now called again after a
  successful refresh.
- Owner follow-up during review: removed the "Tonight's movie shelf" hero block (owner:
  "it's meaningless") — Home now goes straight from the Welcome/Refresh header into the
  continuation shelves.

**Review process:** Codex ran a read-only adversarial review of the owner's proposal
against `DesktopShell.kt`/`DesktopCatalogRepository.kt` before any plan was written (see
`todo-tree/01-open.md`'s D-Desktop-16 entry for the full consolidated split and the gaps
it surfaced — schema unit-mismatch in `added_at`, Live TV incorrectly treated as
resumable, missing route-state preservation — none of which 16a needed to touch). Codex
then ran the acceptance-criteria test pass: verified by direct SQLite fixture (a
rolled-back transaction reproducing a genuinely empty cache, and the real local cache's
existing partial state) that the empty-shelf and first-run logic behave correctly, and by
source trace that Refresh now reloads shelves and that the continue-watching series path
is unchanged (regression pass). Codex's pane has no interactive desktop/GDI session, so
it could not capture a screenshot of the rendered UI — flagged plainly rather than
fabricated.

**Verification:** owner looked at the running app directly (`:desktop:run`, compiled
clean both before and after the hero-block removal) and confirmed the result.

## D-Desktop-16b — Home navigation/route-state prerequisite closed (2026-09-15)

**Closed:** second of five sub-items split out of D-Desktop-16. 16c-16e remain open in
`todo-tree/01-open.md`.

**Fix (`DesktopShell.kt`):**
- New `BrowseSavedState` class — one instance per `CatalogType`, hoisted at `DesktopShell`
  scope (not `remember`ed inside `BrowseScreen`) so it survives leaving Browse for Detail/
  Episodes/Player and coming back: `filter`, `categoryFilter`, `query`, `highlightedId`,
  `items` (the last-loaded list), and a `LazyGridState`.
- `HomeScreen`'s `onBrowse` changed from `(CatalogType) -> Unit` to `(CatalogType, String)
  -> Unit` — a typed destination filter. Continue-watching shelves pass `"__continue"`;
  the generic Explore tiles pass `"__all"` — `See all` now opens on the matching virtual
  folder instead of always landing on the unfiltered catalog.
- The card opened from Browse gets an accent-border highlight on return, and (after the
  fix below) the grid's scroll position survives the round-trip.
- Real bug fix found along the way (`DesktopCatalogRepository.items()`): SERIES +
  `"__continue"` was silently always empty — `recordResume()` never writes
  `items.resume_ms` for a series play, only `episode_resume`. Replaced with a join against
  `episode_resume` (grouped by series, most recent `updated_at`), restricted to
  `position_ms>0` to match the semantics the MOVIES/LIVE branch already had (a zero-position
  row is "finished/reset," not "in progress" — Codex caught this during its test pass,
  see below).

**Review process:** Codex ran a source-trace + SQLite-fixture test pass (no live GUI —
same display-access limitation as D-Desktop-14/16a) against four acceptance criteria (join
correctness, non-series path unaffected, typed `See all` wiring, per-tab state isolation)
— all four passed, plus the `position_ms>0` gap above, which was fixed immediately.

**Bug found by the owner during live testing, fixed by Codex directly (first use of the
new heavier-Codex-delegation posture, see `todo-and-bible-maintenance.md`/session memory
`codex-heavier-usage`):** scroll position did not actually survive a Browse → Detail →
Back round-trip, despite the highlight border working (proving `BrowseSavedState` itself
survived). Root cause: `catalogItems` was a *local* `remember` inside `BrowseScreen`, not
part of the hoisted state — every remount reset it to an empty list while the async reload
ran, and `LazyGridState` clamped its saved scroll index to 0 against that transient
zero-item frame. Fix: moved `items` into `BrowseSavedState` so the grid never renders a
zero-item frame across a remount. Codex implemented this directly (not just diagnosed it),
compiled clean, and reported back for review — owner re-tested live afterward and
confirmed both scroll position and per-tab isolation (filter + scroll independent across
Movies/Series/Live) now work.

**Verification:** owner tested live against the manual step list (typed `See all`, scroll
restoration, highlight border, per-tab isolation) twice — once before the scroll-position
bug was found, once after Codex's fix — and confirmed pass on the second round.

## Catalog title cleanup — empty bracket artifacts from panel templating (2026-09-15)

**Not a D-Desktop-16 item — a standalone catalog data-quality bug**, found by the owner
while testing D-Desktop-16b: some provider panels template titles as `"{title}
({quality})"` and substitute an empty string when quality metadata is missing, leaving
titles that render as literal `"()"`, `"() ()"`, or `"HD ()"`.

**Fix, two places:**
- `app/src/main/java/com/dev/Tvivo/data/local/NameNormalizer.kt` (Android/TV app): added
  an `EMPTY_BRACKETS` regex strip, run *before* the existing quality-token stripping —
  ordering matters, since doing it after would let `"HD ()"` reduce to a bare `"()"` (the
  quality-token loop's "don't empty the whole title" guard only protects the token being
  stripped, not what's left over once brackets are gone). Also changed the final fallback
  from `s.ifEmpty { raw.trim() }` to `s.ifBlank { quality ?: "Untitled" }`, since falling
  back to the raw string would put the original `"()"` right back for a title that was
  nothing but empty brackets. Three new regression tests added to
  `NameNormalizerTest.kt`.
- `desktop/src/main/kotlin/com/dev/tvivo/desktop/catalog/DesktopCatalogRepository.kt`: the
  desktop app did **no** title cleanup at all before this — raw provider names were stored
  and displayed verbatim. Added a small `cleanTitle()` (same `EMPTY_BRACKETS` rule, no
  quality-token handling since desktop doesn't render a quality badge), applied in
  `insertItem()`. Deliberately duplicated rather than shared: `desktop` depends only on
  `shared-core`, not the `app` module `NameNormalizer` lives in; noted in a comment as a
  candidate to consolidate into `shared-core` if a third place ever needs the same rule.

**WinUI3 parity (2026-09-22):** WinUI has no shared C# title cleaner to reuse: Android's `NameNormalizer` is app-local Kotlin and desktop's `cleanTitle()` is repository-local. `SqliteCatalogRepository` mirrors the same empty-bracket/whitespace rule during snapshot writes and reads; schema v9 transactionally cleans schema-v8 cached titles. Future platform catalog paths should clean on ingestion/read and migrate stale persisted values rather than fixing presentation alone. The infrastructure tests cover Live, Movies, Series, and a pre-populated v8 cache.

**Verification:** desktop compiled clean (Claude). Android-side handed to Codex (first
full use of the heavier-delegation posture, see session memory `codex-heavier-usage`):
`:app:testDebugUnitTest` — 20 tests, 0 failures (3 new + no regressions);
`:app:compileDebugKotlin` — BUILD SUCCESSFUL. Codex also audited the rest of the Android
app for other raw-title reads bypassing `NameNormalizer.of()` and found none on the
movie/series/live catalog path; it did find two unrelated bypasses worth a follow-up note
— `SeriesInfoParser.kt` reads a raw season name for the season-rail label, and
`CategoryListParser.kt` reads a raw `category_name` for the category-rail label — neither
is a media title, so out of scope for this fix, left untouched as instructed.

## D-Desktop-16c — durable recency/live-history schema (closed 2026-09-15)

**Fixed**, implemented by Codex under the Claude-plans/Codex-implements division of
labor (`bible-detail/claude-07-agent-division.md`), Claude-reviewed. Three bugs in
`DesktopCatalogRepository.kt`:
- `added_at` unit mismatch (provider epoch *seconds* vs. `System.currentTimeMillis()`
  fallback epoch *milliseconds*) — `insertItem()` now normalizes any parsed `added`
  value under `10_000_000_000L` (i.e. plausibly seconds) to milliseconds before storing.
- `added_at` was rewritten on every `refresh()` (delete-all/reinsert), so "Recently
  added" meant "last refresh touched this row," not first-seen time. Added a
  `first_indexed_at` column (schema v4) preserved across refresh by widening the
  existing favourite/resume savedState↔restore round-trip in `refresh()` to cover
  every existing row, not just favourited/resumed ones — new rows get
  `first_indexed_at` = their normalized `added_at` at insert time; previously-seen
  rows keep their original value. `items()`'s `__recent` category now orders by
  `first_indexed_at DESC`.
- Live TV had no real "recently watched" concept — `DesktopPlayerScreen` called
  `recordResume()` for every content type including LIVE (on player dispose and on a
  5s polling effect), and `recentItems(LIVE)` read that fake resume state back as if
  Live were resumable, contradicting `docs/design/desktop-home-proposal.md`. Fixed
  with a new `last_tuned_at` column + `recordTuned()`, called once per successful LIVE
  playback start (the `playerReady` transition), not on the polling loop; both
  `recordResume()` call sites in `DesktopShell.kt` now skip LIVE; `recentItems(LIVE)`
  reads `last_tuned_at` instead of `resume_ms`/`resume_updated_at`.

Schema v4 backfills `first_indexed_at = added_at` on migration — a one-time
best-effort backfill that inherits the pre-fix unit-mismatch for existing rows;
accepted rather than trying to retroactively un-mix historical units.

Added `desktop/src/test/kotlin/.../DesktopCatalogRepositoryTest.kt` (new JUnit test
infra for the desktop module — `junit:junit:4.13.2` added to `desktop/build.gradle.kts`,
mirroring `app/build.gradle.kts`'s existing pattern) using a real SQLite temp-file DB
and a local `HttpServer` fixture standing in for the provider API: seconds-timestamp
normalization, `first_indexed_at` stability across two real `refresh()` calls,
`recordTuned()` leaving Live resume fields untouched, and `recentItems(LIVE)` ordering
by tune history rather than resume history. `./gradlew.bat :desktop:compileKotlin
:desktop:test` green, 4/4 tests passing.

## D-Desktop-16d — Suggestions v0 (closed 2026-09-15)

**Fixed**, implemented by Codex under the same plan/implement/review division of labor
as 16c, Claude-reviewed. Scope explicitly narrowed from the proposal's larger
"unfolded shelves per Browse tab" idea (not yet filed as its own item) to a Home-only
Suggestions shelf per content type, reusing the existing `HomeShelf`/typed-`onBrowse`
pattern from 16b/16c.

`DesktopCatalogRepository` gained a process-local (not persisted — regenerates on next
app open, per the proposal) `suggestionIds: MutableMap<CatalogType, List<String>>` plus
`ensureSuggestions()` (returns the existing session snapshot, generating one only if
absent) and `regenerateSuggestions()` (always draws a fresh `ORDER BY RANDOM()` sample,
excluding ids already visible in that type's continuation shelf). `items()`'s virtual
category switch gained `"__suggestions"`, matching the stored snapshot via a
**parameterized** `id IN (?,?,...)` clause (ids are provider-controlled but still bound
as statement parameters, not string-interpolated, to avoid the SQL-injection-shaped
pattern) with a `1=0` fallback when no snapshot exists yet (reuses Browse's existing
"No items available" empty state rather than a special case). `DesktopShell.kt`'s
`HomeScreen` calls `ensureSuggestions` on initial load and `regenerateSuggestions` only
inside the Refresh button's `onSuccess` — a failed refresh leaves the prior snapshot
untouched, matching the proposal's "regenerate only after a successful catalog
refresh" rule.

9 repository tests (4 from 16c + 5 new), including session-stability across repeated
Home/Browse access, Browse's `__suggestions` matching the Home snapshot exactly (not a
fresh independent draw), exclusion of continuation-shelf ids, the pre-snapshot empty
state, and a failed-refresh-preserves-snapshot case (fixture returns an empty catalog,
`refresh()`'s own `check(items.size() > 0)` throws, and the snapshot survives). All
green.

## Desktop player fullscreen ownership (follow-up to D-Desktop-16)

**Decision:** libmpv remains the playback library and its bundled Lua on-screen
controller (`osc.lua`) remains the playback-control surface. Its `osc-visibility`
script message is the supported way to select `never`, `auto`, or `always` visibility.

**Root cause:** the OSC fullscreen button changes libmpv's `fullscreen` property. With
libmpv embedded through `wid`, it is only a child surface and cannot resize the
Compose-owned desktop window. The visual action therefore appeared to do nothing.

**Implementation:** observe libmpv's `fullscreen` flag on the mpv owner thread and
route changes into `DesktopShell`'s player-content fullscreen state. The OSC button,
the app control, F/Escape, and the overlay Back control now share that property. The
desktop chrome supplies a guarded Exit action with `Stay in Tvivo` as the safe choice.

**Verification status:** the earlier desktop test suite passed before this follow-up.
A fresh follow-up Gradle run could not complete because the machine's shell launcher
was blocked by policy; D-Desktop-16 remains open until a rebuilt app is manually tested.

## WinUI unpackaged engine preference storage

Directly launching `Tvivo.App.exe` as an unpackaged WinUI app has no package identity.
`Windows.Storage.ApplicationData.Current.LocalSettings` therefore throws `0x80073D54`
during `MainWindow` construction, before the first window can be shown. Keep this
non-sensitive playback-engine preference in the app's per-user LocalAppData folder
instead; do not make startup depend on package-scoped storage APIs.

## WinUI fullscreen window transition

`AppWindow.SetPresenter(FullScreen)` was the fatal detection boundary for startup heap
corruption (`0xC0000374`) in six of six launches, including three calls queued after
activation. Three launches that skipped it stayed responsive for at least 18 seconds.
Use the existing HWND's popup style and current monitor's physical bounds for fullscreen,
then restore its saved style, placement, and bounds on exit. Do not switch presenters
for fullscreen or infer that the presenter caused the earlier invalid heap write.
The Win32 replacement builds; runtime acceptance remains open. See
`windows-spike/.codex-handoff-crash.md` and `.codex-handoff-fullscreen.md`.

## WinUI catalog shelf realization budget

The catalog hang capture showed a UI thread saturated in native XAML work with
110 live shelf grids. A `ScrollViewer` around an `ItemsControl` creates every
shelf, even though each shelf preview is limited to 12 cards. Keep the outer
shelf surface in a viewport-bounded, recycling list, and keep artwork requests
bounded and decoded near display size. Reapplying an identical snapshot should
leave the current item source in place. The Batch 5 build passes; runtime
realization counts and responsiveness still require a separately approved run.

## WinUI catalog and player handoff

The catalog card handler can open the Player before its previously collapsed video
surface has completed layout. Side-list selection runs after that surface is visible.
Give the Player a layout pass before the first playback start, and report startup
exceptions instead of losing them in a fire-and-forget task. Keep the selected
shelf's channel set when selecting another item in the Player side list; passing
an empty set erased that list. Returning from Player should reuse the existing
catalog control so its shelf, filter, page, and scroll state remain intact.

An artwork `ContentControl` needs content to instantiate its `ContentTemplate`.
Recycled images also need a new load when their data context changes, with the
fallback and image opacity reset before each request. A Spotlight chosen once
per snapshot will not rotate without an active-page timer. These source fixes
compile; their visual and playback results still need an approved app run.

## WinUI player-return and catalog transition flicker (closed 2026-09-28)

Nineteen iterative fix-batches (`windows-spike/HANDOFF-fix-batch-{2..19}.md`) chased a
family of screen-flicker/redraw bugs on catalog navigation. Two root causes recurred
under different call sites and were each fixed twice before being closed properly:

- **Player-return flicker (Back, Home, top-nav category clicks from Player).**
  `ShowCatalogAsync`/`ShowPage` gated a mask-then-fade transition behind a
  caller-supplied `returningFromPlayer` flag (`CatalogReturnTransitionPolicy` in
  `CatalogUiPolicies.cs`). Each caller that forgot to pass it flickered — first the
  Back button, then Home, then all four top-nav category buttons, found one at a time
  by direct code inspection rather than a systemic sweep. Closed for good in batch 19
  by having `ShowPage` derive the barrier itself from `_currentPage == ShellPage.Player`
  at the point of leaving, so no caller needs to know or pass the flag; covered by
  `AppSmokeTests.Player_return_layout_barrier_uses_current_page_for_every_catalog_mode`
  across every `CatalogMode`. **Rule: any binary UI-transition behavior that depends on
  "where did we come from" should be derived from current page/state at the transition
  point, not threaded through as a parameter every call site must remember to pass.**
- **Open-folder-listing-to-category flicker.** A distinct bug: `PrepareMode` changed
  mode state and nav chrome while the previously open folder-listing grid was still
  attached, and the fade-in didn't start until the new snapshot was ready, leaving a
  naked redraw gap. Fixed by fading the open-folder surface out first, before any
  mode/state change, then applying the normal snapshot fade-in.

Both fixes user-confirmed live in the same session as closed (build 0/0, 68/68 tests
passing). See `windows-spike/HANDOFF-fix-batch-19.md` for the grep-audit evidence that
no remaining `ShowCatalogAsync` call site or Player-exit path is unprotected.

Still open, not part of this closure: the nav-bar tooltip showing an increasing number
on hover (root cause not found in app source after three investigation attempts across
batches 15-17; needs a user screenshot to determine if it's OS-level rather than
Tvivo-drawn).

## WinUI parity list re-verification and Account/Settings screen (closed 2026-09-28)

**Context:** before deciding whether to retire the Compose desktop app (`desktop/`), the
2026-09-22 WinUI/Compose parity roadmap in `TODO.md` was re-verified line-by-line against
current `windows-spike/src/` source. It was badly stale — Stage 1 was fully done, and large
parts of Stage 2/3/4/6 had been closed in code without the doc being updated (e.g. fullscreen/
cinema mode, the Live-playback DirectUri bug, and the cache-refresh-destroys-favourites bug
were all already fixed). `TODO.md`'s parity section was rewritten with DONE/PARTIAL/MISSING
status and file:line evidence for every item. Two real gaps stood out as functional (not
cosmetic) regressions: the entire Account/lifecycle stage (Stage 5) was open, and the
resume-position data layer (`UpdateResumePosition`/`GetContinueWatching`) exists with zero
callers anywhere in the app — built and never wired up.

**Account/Settings screen (Stage 5), fixed:** new `Pages/AccountPage.xaml(.cs)` shows the
connected account's display name/username, host+scheme, max connections, and expiry (handles
null/expired/future `DateTimeOffset?`). Sign-out calls `ICredentialStore.DeleteAsync()`, clears
`CatalogLandingPage` state (`ClearAccountState()`) and `ProviderSetupPage` form fields
(`ClearCredentials()`), then routes to Setup. Exit shows a confirmation dialog when playback was
active before Account was opened; closes immediately when idle. Wired via `AccountNavigation_Click`
in `MainWindow.xaml.cs`, with `ShellPage.Account` added to the shell's page enum.

**Division of labor and a real self-report failure:** two `codex exec` background attempts to
implement this from a written plan both reported "no files were changed" / an inability to
proceed — both claims were false. Git diffs showed each run had actually made partial edits
(the first wired `MainWindow.xaml.cs`/`CatalogLandingPage.xaml.cs`/`ProviderSetupPage.xaml.cs`
but never created the `AccountPage` files those edits referenced, which would have failed to
compile; the second, run as a review pass, silently rewrote parts of `AccountPage.xaml(.cs)`
while claiming it made no changes). Claude finished the implementation directly (the documented
fallback role split in this section) and verified `dotnet build -p:Platform=x64` clean (0
warnings/errors) before and after. **This is exactly the failure mode this project's
evidence-over-self-report rule exists for — do not trust a "done"/"no changes" claim from either
agent without independently checking the diff.**

**Codex's review pass then found two real bugs**, both fixed and re-verified with a clean
rebuild:
- The exit-confirmation dialog claimed "cancel keeps playback running," but `ShowPage` always
  calls `StopPlaybackForNavigation()` when leaving the Player page — so playback is already
  stopped by the time Account is reachable, making that claim structurally false. Reworded to
  honestly warn that progress won't resume (since resume-position isn't persisted yet, see the
  open Stage 3 gap above), rather than implying playback can be un-paused by cancelling.
- `ProviderSetupPage`'s constructor-fired `LoadSavedConnectionAsync()` could resolve after
  sign-out cleared its fields and silently repopulate the old credentials. Fixed with a
  generation counter (`_credentialGeneration`) checked after the async load completes.

**Verification:** `dotnet build -p:Platform=x64` clean (0 warnings/0 errors) after each round.
No UIA/runtime click-through was performed this round (Codex's own review log stated this
explicitly rather than claiming untested behavior worked) — that's the next verification step
before this item is fully closed for release purposes, not just for TODO tracking.

**Net effect on the retirement decision:** the real remaining gap before Compose can be safely
retired is narrower than the pre-2026-09-28 doc suggested, but still real: Home personalization
shelves (Continue Watching, Suggested), the resume-position wiring gap, idle controller,
diagnostics logging, and the polish/perf cross-cutting items. Account/lifecycle is no longer
one of them.

## Full WinUI parity closure: Home shelves through dark startup frame, playback verified live (closed 2026-09-30)

This closes every remaining item from the "Net effect" note above, following the plan/review/execute
loop documented in `PROJECT-BIBLE.md` §6, across six sequenced batches (Batch 0 verification, then
Batches 1-5). Adopted the official `openai/codex-plugin-cc` Claude Code plugin this session,
replacing raw `codex exec` shell invocations with `/codex:rescue` (background job tracking via
`codex-companion.mjs`, proper `status`/`result` polling) — this fixed the false "no files changed"
self-report failure mode from the prior session, though a new, milder version of the same problem
recurred repeatedly this session (see below).

**What shipped, in order:**
- **Batch 0 — verification of the prior session's unverified work.** Launched the app via
  PowerShell + `PrintWindow` (window-scoped screenshot capture, not full-screen — a full-screen
  grab on the first attempt accidentally captured an unrelated RDP session with what looked like
  health-data content on the user's other monitor; caught immediately, flagged to the user, fixed
  by capturing only the target HWND from then on). Confirmed Home shelves and Account screen both
  render with real data. Found a real bug: `HomePage.xaml.cs`'s `ToCard()` hardcoded "Series ·
  resume episode" for any series item regardless of shelf, mislabeling never-started Suggestions
  entries as resumable — fixed by Codex, confirmed live via screenshot. Added a "Change user"
  button (`AccountPage`) at the user's request as a lower-risk alternative to testing sign-out —
  it opens a fresh setup form without deleting the saved credential, verified by relaunching the
  app after backing out of it and confirming the original account reloaded automatically.
- **Batch 1 — HTTP/HTTPS account-identity collision + sanitized error copy.** Scheme added to
  `AccountIdentity.For`'s hash (no migration — old accounts re-auth once, a deliberate choice).
  All user-facing exception text (`ProviderSetupPage`, `AccountPage` sign-out) routed through a
  `ConnectionErrorText` helper that logs full detail via `LaunchDiagnostics.WriteException` but
  shows only fixed, non-leaking text. Reviewed clean, no findings sent back.
- **Batch 2 — routine diagnostics + player lifecycle logging.** Structured `event=` logging added
  for catalog cache load/refresh, playback start/stop (tagged with reason), per-engine resource
  release, and resume-position saves. Two real issues found in review and fixed: (1) the redaction
  pass over-corrected and stripped full exception text/stack traces from `WriteException` — used
  for genuine crash diagnostics — down to just type name + HRESULT; restored full detail routed
  through the same redaction instead of discarding it. (2) zero test coverage for the new
  redaction/truncation/rotation logic; added `InternalsVisibleTo` + `LaunchDiagnosticsTests.cs`
  (6 tests).
- **Batch 3 — idle/featured overlay + artwork byte-budget cache.** Idle overlay matches the
  Compose reference exactly (5-min timeout, pauses on playback/unfocused-window/open-dialog,
  dismiss gestures consumed via `Handled = true` so nothing underneath activates, clean timer
  teardown on window close) — reviewed clean. Artwork cache gained a 96 MiB byte-based LRU
  eviction (`ArtworkMemoryBudget`, unit-tested) alongside the existing count cap; Home's artwork
  was found bypassing the shared bounded pipeline entirely (direct `Image.Source` binding) and was
  rerouted through it. One issue found and fixed: the idle overlay's featured-content query
  hand-rolled its own `SqliteConnection` against a hardcoded path instead of using
  `SqliteCatalogRepository` — moved into a proper `GetFeaturedSeriesTitles` repository method.
- **Batch 4 — performance acceptance measurements.** User scoped this to "core scenarios only"
  (skipped monkey/heavy-user long sessions). Ran the existing `Measure-Tvivo.ps1` harness's full
  `scenarios` phase live: idle My Tvivo (5 min), idle Movies (5 min), 50 mode switches, 20x scroll,
  30x fullscreen toggles, clean close. **PASS** — no crash, no hang, idle CPU <1%/core (one
  launch-transient spike aside), UI ping peak 84ms, clean exit in 225ms. Superseded the prior
  2026-09-26 report, which crashed mid-run and predated this session's entire feature set. Found
  the idle overlay had zero diagnostic logging (couldn't confirm from the run whether it fired
  during the 5-min windows) — fixed as a follow-up (`event=idle.overlay.show/dismiss`).
- **Batch 5 — dark startup frame.** Scoped down from "dedicated splash+login page" to just
  eliminating the white flash before first paint, since `ProviderSetupPage` is already
  dark-themed and serves as the de facto first-run screen. `RequestedTheme="Dark"` on `App.xaml` +
  `DwmSetWindowAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE)` in `MainWindow`'s constructor.
  `ExtendsContentIntoTitleBar` — a previously-diagnosed crash cause in this exact app — was
  explicitly named as off-limits in the task brief and confirmed untouched. Claude ran the
  3-launch verification live per the user's request: 3 consecutive launches, all responsive, no
  crashes, no DWM errors logged.
- **Playback verification (post-batches, user-requested).** No local-fixture test stream exists
  yet, so this used the real configured IPTV account briefly and carefully (max-connections=1 —
  the same constraint flagged in Batch 0). Played a movie, confirmed `resume_ms` written live to
  the SQLite DB mid-playback, tested the Account exit-confirmation dialog while playback was
  active, closed the app, relaunched, and confirmed the movie appeared in Continue Watching with
  correct artwork — a genuine end-to-end round trip, not code inspection. **Found a live,
  real bug in the process**: the exit dialog still claimed progress "won't resume automatically
  next time" — factually false now that resume-position wiring works, and exactly the
  contradiction Codex's own Batch-0 planning pass had flagged but which never got fixed. Corrected
  the copy directly (`AccountPage.xaml.cs`) to accurately state progress is saved and resumes.

**Recurring self-report reliability problem, again.** Codex's own build/test summaries were wrong
in essentially every batch this session — reporting DPAPI-related test failures (5 failures,
different exact count each time) that did not reproduce when Claude independently reran
`dotnet test -p:Platform=x64` immediately afterward (consistently 78/78 passing). Root cause
appears to be Codex periodically omitting the `-p:Platform=x64` flag despite it being explicitly
called out in every task prompt as mattering. This did not block any batch — Claude never trusted
a Codex-reported pass/fail number without an independent rebuild+retest — but it's a durable
pattern worth remembering: **always independently rebuild and retest after any Codex round in this
project, regardless of what Codex's own report claims, and expect the `-p:Platform=x64` omission
specifically.**

**Verification summary:** every batch's actual diff was reviewed directly (`git diff`, since
nothing was committed mid-session) rather than trusting Codex's self-report, per this project's
evidence-over-self-report rule. `dotnet build -p:Platform=x64` clean (0 warnings/0 errors) and
`dotnet test -p:Platform=x64` at 78/78 passing, independently confirmed after every batch. Runtime
UI verification (screenshots + live playback) was performed directly by Claude via PowerShell +
`PrintWindow`/UIA-style mouse/keyboard driving, not deferred to the user, for: Home shelves,
Account screen, Change-user flow, Suggestions subtitle fix, live movie playback, resume-position
persistence across a full close/relaunch cycle, the exit-confirmation dialog (both the bug and the
fix), and 3 consecutive cold launches for the dark-startup-frame change.

**Net effect on the retirement decision:** every functional gap identified before this session is
now closed and, with the exception of the idle overlay's 5-minute trigger and series-specific
resume-card behavior, verified live rather than just code-reviewed. The only remaining open item
is MSI/runtime distribution packaging, deliberately deferred pending signing-certificate,
architecture, and distribution-channel decisions that are the user's to make. **Compose retirement
is no longer blocked by a functional gap in WinUI** — the retirement decision itself has not been
made and remains open.

## windows-spike/CODEMAP.md added (2026-10-01)

Set up the `codemap` skill (`C:\Users\Dell\.claude\skills\codemap\`) against `windows-spike/`. The
skill's stock script assumes a web stack (`[HttpVerb]` controllers → backend → stored procs →
tables); this app has none of that — `Tvivo.App` Pages call `SqliteCatalogRepository` methods
directly, which run inline SQLite SQL against local tables, with no route or proc layer in between.

Rather than bend the config to fake a controller layer (which produced a map that looked broken —
every call flagged as "frontend calls a route the controllers do not define" — when nothing
actually was), extended `windows-spike/scripts/codemap.mjs` with a `collapsedRoutes`/
`collapsedProcs` mode: when `controllers`/`sql` are left out of `codemap.config.json`, each backend
method stands in as its own route and its own proc, so the index still shows real read/write table
sets per method with no fake intermediate hop.

While building that extension, found and fixed a real pre-existing bug in the stock script shared
by both the collapsed and original modes: the per-method brace-depth tracker only closed out a
method body on `depth < 0`, never on returning to baseline `0`. If a declaration the regex couldn't
parse (e.g. `GetSeriesPlayback`'s tuple return type `(string? EpisodeId, bool Finished)`) was
skipped, its body's SQL kept accumulating onto whatever method was last successfully matched —
caught via spot-check (`RecordVisit` showed `series_playback` as a read it never touches). Fixed by
tracking whether the body was actually entered and closing at the next return to depth 0.

`windows-spike/CODEMAP.md` now reports 24 routes / 16 "procs" (repository methods with inline SQL)
/ 5 tables, spot-checked against source. `.githooks/pre-commit` installed and `core.hooksPath` set
for this clone; tested live (stage a touched file, run the hook by hand, confirm it regenerates and
restages). Notes baseline (`TODO.md`, `docs/decisions.md` vs. `src/**/*.cs`) recorded in
`.codemap/reviewed.json`. Commands and the local script extension are documented in `CLAUDE.md`.

## app/CODEMAP.md added for the Android TV app (2026-10-01)

Applied the same `codemap` setup to `app/` (the Android TV app), on request to extend the
`windows-spike` setup to the other codebase. The shape here is three real layers — `ui/*`
ViewModels call `data/repository/*.kt` Repository methods, which call `data/local/dao/*.kt` Room
DAO methods, which run against SQLite tables — which maps naturally onto codemap's
frontend→controller→backend→table model, *except* for two things Kotlin/Room do differently from
the C# shape the stock script assumes:

1. **No `public` keyword, no `[HttpVerb]` attribute.** Kotlin declarations are just `fun name(...)`,
   and there is no web framework here to annotate a route. Added `controllers.declRegex` (override
   the decl pattern per config) and `controllers.requireVerb: false` (skip the verb-gate entirely;
   every matched declaration becomes a route, verb `CALL`) — both config-driven, no special-casing
   of "Kotlin" in the script itself.
2. **Room DAO methods are abstract.** The SQL is a `@Query("""...""")` annotation sitting *above*
   the method, not a body below it — the brace-depth body scanner built for C#'s
   `CommandText = "SELECT ..."` pattern cannot see it at all; there is no body to scan. Added
   `backend.kotlinRoomDao: true`, a self-contained parsing branch that: tracks `interface FooDao { }`
   boundaries (`SupportDaos.kt` holds six DAOs in one file), accumulates a `@Query(...)` annotation's
   text across however many lines it spans, and attaches it to the *next* method declaration. DAO
   method names collide freely across DAOs (`observe`, `get`, `upsert`, `remove`, …), so each method
   is keyed by its accessor shape — `liveDao().pagingInCategory`, not bare `pagingInCategory` — the
   same shape the Repository layer actually calls it by (`db.liveDao().pagingInCategory(...)`).
   Getting this wrong would have silently merged two different DAOs' same-named methods into one
   table-reads/writes entry.

A related but deliberate asymmetry: Repository method names are *not* qualified by class, on
purpose. `LiveRepository`, `VodRepository`, and `SeriesRepository` are explicitly "the same shape
line for line" per their own doc comments (one repository per content type, same method names
throughout) — BrowseViewModel even wraps them behind a type-erased `CatalogSource` interface,
so there is no way to tell from a call site which concrete repository is in play. Merging
`pagingInCategory` into one route that lists all three DAO calls (and the union of `live_streams` /
`vod_streams` / `series`) is the *correct* read here: "this operation, across content types,
touches these tables" — not a bug, unlike the DAO case where the three tables are genuinely
unrelated data and must stay separate.

Known, accepted coverage gaps (documented in `CLAUDE.md`, not fixed — regex-over-source limits):
`CachedFetch` holds its DAO as a bare field (`syncMetaDao.get(...)`, no `db.` prefix), so those
calls aren't linked to a route; the `CatalogSource` wrapper is itself a second indirection
(`catalog.pagingFiltered(...)`) the frontend extractor can't see through, so it flags as a route
"not defined" even though the real underlying call one line away resolves correctly.

`app/CODEMAP.md` reports 24 routes / 65 backend (DAO) methods / 51 of those carrying their own
inline SQL / 9 tables, spot-checked against source (`vodDao().updatePlot` writes-only `vod_streams`,
`favouriteDao().add` correctly shows no SQL since it's `@Insert`-generated). Confirmed the
`windows-spike` output is byte-for-byte unchanged after generalizing the shared script (diffed
against the last committed `CODEMAP.md`). `.githooks/pre-commit` extended to loop over both
`windows-spike` and `app`; notes baselined in `app/.codemap/reviewed.json`.

## WinUI top bar, spotlight and Settings rework (2026-10-02)

- **Idle/featured overlay removed** (supersedes the "Batch 3 — idle/featured overlay" entry above): the
  user judged it nonsense. Timer, focus/pointer tracking, overlay XAML and the now-unused
  `GetFeaturedSeriesTitles` repository method (and its test) are gone.
- **Search + sort pills moved to the top bar** and collapse on every non-catalog page (disabled-but-visible
  controls on Settings were noise). Page titles inside catalog pages were removed so the spotlight is first.
- **Spotlight is fixed**: it never follows the search filter or the sort pill. It is always built from the
  unfiltered shelves at `SpotlightSort` (Most visited) and cached per (account, mode) in
  `_spotlightShelvesCache`; `CatalogSnapshot.SpotlightShelves` falls back to `Shelves` when that is the same set.
- **Account page is now Settings** (class stays `AccountPage`): horizontal tabs in the order Library
  (last refresh + "Refresh catalog now" via `CatalogLandingPage.RefreshNowAsync`, **selected by default**),
  Player (playback engine) and Account (details, Sign out, Change user). Diagnostics and About tabs were built
  then removed at the user's request. The engine picker left the top bar. The in-page **Exit Tvivo** button (and its playing-confirmation dialog) was removed as redundant with
  the title-bar close button. Not built: Playback defaults (needs track switching first), Appearance, Profiles.
- **Taskbar hint (2026-10-02):** the borderless fullscreen window covers the whole monitor, so the shell hides
  the taskbar; after force-kills/crashes it could stay hidden. `WindowStateController` now calls
  `ITaskbarList2.MarkFullscreenWindow` on every enter/leave fullscreen and `ClearFullscreenHint()` from
  `MainWindow.OnClosed`. Unverified fix - the stuck state was only ever observed, not reproduced.
- **Cinema cursor**: hidden for the whole of cinema playback (no longer re-shown on pointer move); shown again
  when playback pauses/stops or cinema mode closes.
- **Refresh status bar** (`RefreshInfoBar`) moved from the top of the catalog list header to a bottom overlay.
- **Quality pill** (`HD/FHD/4K/SD`): the badge was parsed only from a *trailing* title token, which missed
  `Name FHD (2023)`, `4K - Name`, `Name FHD H265`, `1080p`/`BluRay` forms (about 1 in 10 live titles with a
  token and a few thousand movie titles were missed in the local DB). It now reads the token from anywhere in
  the title (highest wins; FULL HD/1080p->FHD, 2160p->4K, 720p->HD, 480/576p->SD), strips it and codec tags from
  the display title, and falls back to the category name (e.g. "beIN SPORTS HD"). Xtream list endpoints carry
  no resolution field; `get_vod_info` has one but would need a per-item fetch (not done).
- Debug-only "Gate 9 fixture" button is overlaid bottom-right in `CatalogPageRoot` so it no longer costs a row.

## WinUI search, progress, track picker and EPG batch (2026-10-02, built and unit-tested; interactive UI verification still pending)

- **Search is flat + category chips.** Per-category result grouping was dropped: results are one paged flat
  list, with count-sorted category chips (`GetCategoryMatchCounts`) above it. There is still no global search.
- **Recently added** = max(provider `added_at`, post-baseline `first_indexed_at`), 200 per type, so newly
  indexed items surface even when the provider reports no add date.
- **Previous/next episode** controls in the player bar plus PageUp/PageDown, and a next-episode overlay.
- **Viewing map + progress (catalog schema v16, `media_progress`).** Per-episode state Unwatched/InProgress/
  Finished. Completion is recorded before auto-advance, on engine end or at 90% of a positive measured
  duration (seeking past 90% therefore counts as finished: open question). Home Continue Watching shows a
  progress track and "min left" only when a duration was measured. Episode list: watched marks, per-season
  counts, Resume episode, Mark watched/unwatched. Back up `winui-catalog.sqlite` before the first v16 run.
- **Audio and subtitle picker.** `ITrackSelectingEngine` on both engines; control-bar flyout with Audio and
  Subtitles (Off first). Default subtitle policy: explicit Off, then preferred language, then UI-culture
  match, then first track; applied once per playback session. Nothing is persisted yet (Off/language
  persistence waits for a format-by-engine matrix). Native engine may enumerate timed-metadata tracks it
  cannot render. The picker is hidden in cinema mode (transport bar hides there).
- **EPG, Now/Next only.** Separate `winui-epg.sqlite` (v1), XMLTV parse with DTD disabled, caps 512 MB / 1M
  programmes, window now-6h to now+48h, due-gated and playback-busy-aware refresh via `EpgCoordinator`
  (App singleton). Live cards show Now / progress / Next; the player shows the same panel for live channels
  with an `epg_channel_id`. Nothing shows without data. Guide grid, reminders and catch-up are out of scope.
  Real feed size and coverage are unknown until tested against the real account.
- **Review fix:** `EpgRepository` never set its `PRAGMA user_version` command text (would throw on every
  construction); fixed before first build.
- **First build (2026-10-02).** Debug win-x64 builds with 0 warnings; Core 17, Infrastructure 62, Playback 4 and
  App 16 tests pass; 3-launch startup test clean on the migrated v16 catalog (backup taken first). Build/test
  caught real defects, all fixed: XMLTV parser skipped the node after each `ReadElementContentAsString` (dropped
  the next programme), EPG staging temp tables survived on pooled connections (second import failed), plus
  compile errors against the WinRT track-list projection, a LibVLC `TrackDescription` reference, a nullable
  conditional and two scope collisions. `EpgImportLimitExceededException` now derives from `Exception`
  (`InvalidDataException` is sealed). Not yet verified on screen: subtitle/audio picker on both engines, live
  Now/Next on cards and in the player (LibVLC overlay z-order), prev/next + overlay, viewmap/progress bars,
  flat search + chips, Recently added.
