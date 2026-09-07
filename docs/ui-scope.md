# UI Scope — design handoff

Written 2026-09-07 as input for a design pass. Collects what is scattered across
`architecture.md` (implementation), `xtream-api-reference.md` (data realities),
and `decisions.md` (locked calls) into one brief.

**Read this first, then `decisions.md`.** Do not redesign anything under
"Already locked" without reading the rationale there.

## The device shapes everything

- **Ten-foot UI.** Viewed from ~3 m on a large panel. Touch targets are
  irrelevant; legibility at distance is not.
- **D-pad only.** No pointer, no touch, no scroll wheel. Every element is
  reached by up/down/left/right. Focus must be unmistakable at 3 m and must
  never be lost or land somewhere invisible.
- **Overscan.** Some TVs crop the edges. Keep content inside a safe area.
- **Text entry is painful.** An on-screen keyboard driven by a D-pad makes
  every character expensive — this is why the login screen deserves care and
  why nothing else in the app should ask the user to type.
- **Back button behaviour matters.** Returning from the player must restore
  focus to the item that was played, not reset to the top of the grid.

## Screens

| Screen | Purpose | Notes |
|---|---|---|
| **Login** | server, port, username, password | Four fields, all typed. Focus order and keyboard behaviour dominate. Four failure states must look different: unreachable host, bad credentials, expired account, transport failure |
| **Home / content type** | Entry to Live, Movies, Series | Shape is an open question — see below |
| **Category grid** | Categories for one content type | ~120 VOD categories, rendered as-is. Names come from the panel, arbitrary length and language. **Filterable by name** — this is the primary way to make 120 categories navigable |
| **Stream list** | Items in a category | Paged poster grid. This is where the user spends most of their time. **Searchable within the category** |
| **Series detail** | Season + episode picker | One extra layer that movies and live do not have |
| **Player** | Fullscreen playback | D-pad transport. Scrub bar hidden for live content |
| **Error** | One shared component | Renders 7 distinct error kinds, with a retry affordance |
| **Diagnostics** | Hidden, redacted error log | Utility screen, low design priority |

Every list-bearing screen needs four states: **loading, empty, error, content** —
plus a manual refresh affordance on category and list screens. Searchable
screens need a fifth: **no results for this query**, which must read
differently from an empty category.

## Search — deliberately scoped

Two searches exist, and neither is global over the whole catalog:

- **Category filter** — filters the ~120 category names. Local, instant, no
  infrastructure. This is the main answer to "how does anyone navigate 120
  uncurated categories".
- **In-category item search** — searches the items of the *currently selected*
  category only. Those items are already cached locally, so this is a filtered
  query, not a network call.

**The search field must always show its scope.** "Search in Action Movies",
never a bare "Search". A user who believes the box searches everything will
read a missing result as "they don't have it", which is the failure this
scoping was chosen to avoid — hiding the boundary reintroduces it.

Why not catalog-wide search: the Xtream API has **no search endpoint** (the
only actions are `get_*_categories`, `get_*_streams`, `get_series`,
`get_series_info`, `get_short_epg`, `get_simple_data_table`, `get_vod_info`).
Global search would require syncing and indexing the entire catalog locally —
a deliberate trade, declined for the POC. The accepted cost is that finding a
title requires knowing roughly which category it lives in.

Matching runs against a normalized column, not the raw title — titles carry
`"HD "` prefixes, doubled spaces, and Arabic diacritics, so raw matching is
erratic. See `architecture.md`.

## Content realities that will break a naive design

These come from real panel responses, not assumptions:

- **Titles are frequently Arabic (RTL) or mixed Arabic/English in one string.**
  Text direction, alignment, and truncation all need deliberate handling, and a
  row can contain both directions at once.
- **Titles carry junk.** Real example: `"HD  للعدالة وجه آخر"` — a quality
  prefix and a double space. Expect noisy, inconsistent, sometimes very long
  names.
- **Ratings are usually `0`.** `rating` and `rating_5based` are rarely
  populated. Do not build a design that leans on star ratings.
- **Metadata is often empty strings.** `plot`, `genre`, `cast`, `director`,
  `releaseDate`, `youtube_trailer` are frequently `""`, and `backdrop_path`
  is frequently `[]`. A detail screen must look intentional with almost
  nothing in it.
- **Artwork may be missing or fail to load.** Posters are hosted on a
  different domain from the API. Placeholder treatment is a real design
  problem, not an edge case.
- **Categories are unordered and uncurated.** Roughly 120 of them, mixing
  language, era, genre, and studio groupings with no hierarchy. The plan is
  explicitly to render them as-is rather than invent a taxonomy — making that
  legible is a design problem.

## Already locked (see `decisions.md` for rationale)

- **Compose for TV** (`androidx.tv:tv-material`) for all browse UI.
- **Player is Media3 `PlayerView`** hosted in an `AndroidView`, not a
  Compose-native player.
- **Lists are paged** (Paging 3). Focus must survive page-load boundaries —
  this constrains how grids and rows can behave.
- **One shared error component** for all seven error kinds.
- **Scrub bar hidden for live** — progressive live streams cannot seek at all.
- **Posters are downsampled to card size** before caching. This means the
  **card dimensions must be decided early**: they are an input to the image
  pipeline, not a late styling choice.

## Open design questions

1. **Card aspect ratio and grid density.** Blocks the image pipeline — answer
   this first.
2. **Top-level navigation shape.** Rows, side navigation, or tabs for
   Live / Movies / Series.
3. **Presenting ~120 unordered categories** without a taxonomy and without a
   wall of identical tiles. Partly answered by the category filter — but the
   unfiltered first view still has to be legible.
3b. **How search is entered and dismissed on a D-pad.** Typing is expensive
   here, so: does the filter live always-visible above the grid, or behind a
   button? Does it filter as you type? Is voice input worth wiring up, given
   most Android TV remotes have a microphone? This is the one place the app
   asks for typing outside login, so it earns real design attention.
4. **Focus treatment.** Scale, border, elevation, or some combination — must
   read clearly at 3 m.
5. **Typography** for mixed RTL/LTR content, including how long noisy titles
   truncate.
6. **Placeholder treatment** for missing posters, which will be common.
7. **Whether a "continue watching" row exists.** Resume positions are stored,
   so the data supports it; the plan does not currently surface it.
8. **Empty and error states** — illustrated or text-only.

## Not in scope

No EPG / program guide, no timeshift or catch-up UI, **no catalog-wide item
search** (see Search above — category filtering and in-category search are in
scope), and no settings beyond credentials and the hidden diagnostics screen.
