# UI Scope — design handoff

Written 2026-09-07 as input for a design pass. Revised the same day after
`/plan-design-review` (outside voices: Codex `gpt-5.6-terra`, plus an
independent Claude reviewer). Rationale for every locked call is in
`decisions.md`; this file is the design specification.

**Read this first, then `decisions.md`.** Do not redesign anything under
"Decided" without reading the rationale there.

Full review findings, the 23-task build list, and mockups:
`~/.gstack/projects/ojgWeza-tvivo-androidtv/Dell-main-design-review-20260907.md`

## The device shapes everything

- **Ten-foot UI.** Viewed from ~3 m on a large panel. Touch targets are
  irrelevant; legibility at distance is not.
- **D-pad only.** No pointer, no touch, no scroll wheel. Every element is
  reached by up/down/left/right. Focus must be unmistakable at 3 m and must
  never be lost or land somewhere invisible.
- **Overscan.** Some TVs crop the edges. Nothing focusable — including focus
  rings — may sit outside the safe area defined below.
- **Text entry is painful.** An on-screen keyboard driven by a D-pad makes
  every character expensive. This is why the login screen deserves care and
  why nothing else in the app asks the user to type except search.
- **Weak hardware.** 1–2 GB RAM, A53-class CPU. Per-app heap may be as low as
  96 MB. Nothing may allocate or animate as if this were a phone.

## Layout system

All numbers are at 1920×1080, which on Android TV is **960×540 dp at density
2.0**. Divide px by 2 for dp.

```
Safe area (5% action-safe)   96 px / 48 dp left+right, 54 px / 27 dp top+bottom
Usable content area          1728 × 972 px

Rail (open)                  600 px wide. Background bleeds to x=0;
                             content starts at x=96 (inside safe area).
                             Content width 464 px = label 344 + gap 24 + count 96.
Rail-to-grid gap             32 px
Grid pane, rail open         x 632 → 1824, usable 1192 px
Grid pane, rail closed       x 96 → 1824, usable 1728 px

Header                       76 px tall, starts at y=54
Grid starts                  y=154, bottom bound y=1026 → 872 px available
```

### Card sizes — these are image-pipeline inputs, not styling

Posters are downsampled to card size *before* caching, so these numbers are
baked into every cached bitmap. Changing one later means re-encoding the cache.

| Content | Card | dp | Columns (rail open / closed) | Gutter | Rows visible |
|---|---|---|---|---|---|
| **Movies, Series** | 220 × 330 (2:3) | 110 × 165 | 5 / 7 | 20 px | 2.46 |
| **Live TV** | 220 × 124 (16:9) | 110 × 62 | 5 / 7 | 20 px | ~4.4 |

Check: 5 × 220 + 4 × 20 = 1180 ≤ 1192, so 12 px of slack and no card is ever
clipped by overscan. Rail closed, 7 × 220 + 6 × 24 = 1684 ≤ 1728.

**Live TV must not use the poster card.** `stream_icon` for a live channel is
a *logo*, frequently wide, square, or on a transparent ground. Cropping a logo
to 2:3 destroys it. Live logos are `ContentScale.Fit`, letterboxed on a
neutral tile, never cropped. The image pipeline therefore has two targets:
`POSTER(220×330)` and `CHANNEL(220×124)`.

**No horizontal peek.** The grid never intentionally clips a focusable card at
the left or right edge — a partial card reads as a horizontal carousel in what
is a vertical grid, and invites D-pad travel into clipped focus. A partial
*bottom* row is different and is required: it is the only scroll affordance.

**Column count reflows** from 5 to 7 when the rail closes. The card size does
not change, so the cached bitmap stays valid. This is only safe because focus
is restored by stable item ID rather than by index (see Focus contract).

## Colour

Dark teal surfaces with a Claude-orange accent. Every pair below was computed,
not eyeballed; the ratio column is the WCAG contrast against the surface named.

| Token | Hex | Role |
|---|---|---|
| `--bg` | `#0A1619` | app background, deepest surface |
| `--surface` | `#0F2126` | the rail |
| `--elevated` | `#163036` | cards, inputs, menus |
| `--line` | `#1E4149` | borders, dividers |
| `--ink` | `#E8F1F2` | primary text |
| `--dim` | `#93ACB1` | secondary text, counts |
| `--accent` | `#D97757` | focus frame, focus fill, chips |
| `--accent-text` | `#E08466` | the accent **used as text** |
| `--on-accent` | `#1A0A05` | text inside an accent fill |

| Pair | Ratio | Needs | |
|---|---|---|---|
| `ink` on `bg` | 16.02 | 4.5 | pass |
| `ink` on `surface` | 14.45 | 4.5 | pass |
| `ink` on `elevated` | 12.11 | 4.5 | pass |
| `dim` on `bg` | 7.69 | 4.5 | pass |
| `dim` on `surface` | 6.93 | 4.5 | pass |
| `on-accent` on `accent` | 6.17 | 4.5 | pass |
| `accent` frame on `bg` | 5.89 | 3.0 | pass (non-text UI) |
| `accent` frame on `surface` | 5.31 | 3.0 | pass (non-text UI) |
| `accent-text` on `elevated` | 5.07 | 4.5 | pass |

**Why two orange tokens.** `#D97757` as *text* on `--elevated` measures 4.45,
which fails 4.5 by a hair. Rather than round the requirement down, accent text
uses the lightened `#E08466`. `#D97757` stays for fills and frames, where the
bar is 3:1 and it clears comfortably.

Teal is the only hue in the interface. The accent is the only warm colour, so
it means exactly one thing: *this is where focus is*. Nothing decorative uses
it.

## Typography

There is no design system yet (see "What does not exist"). This is the minimum
type scale required to build against.

| Role | Size | Line height | Notes |
|---|---|---|---|
| Card title (overlaid) | 32 px / 16sp | 40 px | 2 lines max, over a scrim |
| Rail label | 32 px / 16sp | 40 px | 1 line, ellipsised |
| Rail count | 28 px / 14sp | — | tabular figures, fixed 96 px column |
| Header / screen title | 38 px / 19sp | — | start-aligned to the grid's first column |
| Names-only row | 32 px / 16sp | 40 px | 1 line |
| Body, metadata | 32 px / 16sp | 40 px | |

**Roboto has no Arabic.** Android falls back to Noto Naskh, whose metrics
differ, so an English and an Arabic title in the same grid row will not share a
baseline unless line height is set explicitly. Set `lineHeight` on every text
style, `includeFontPadding = false`, and
`LineHeightStyle(trim = None, alignment = Center)`. Reserve a fixed two-line
height on card titles so rows never jitter between one- and two-line titles.

Card titles are **overlaid on the poster** over a bottom scrim, not placed
underneath it. Underneath costs ~90 px of every card on every row and drops the
grid from 2.46 visible rows to 1.85. Live TV is the exception: its card is too
short to overlay, so channel names sit below.

## Screens

| Screen | Purpose | Notes |
|---|---|---|
| **Login** | One server field, username, password | See Login below. Four failure states must look different: unreachable host, bad credentials, expired account, transport failure |
| **Home** | Three large buttons: Live TV, Movies, Series | Shown after authentication. Nearly fills the screen. This is where content-type scope is chosen, and it is what makes every search scope unambiguous |
| **Browse** | Rail + grid, one per content type | The main screen. Replaces the separate "category grid" and "stream list" screens — the rail *is* the category list |
| **Series detail** | Season + episode picker | The one extra layer movies and live do not have |
| **Player** | Fullscreen playback | D-pad transport. Scrub bar hidden for live |
| **Error** | Two renderers over one taxonomy | `ErrorState` full-screen, `ErrorFooter` inline. See Errors |
| **Diagnostics** | Hidden, redacted error log | Utility screen, low design priority |

There is **no movie detail screen**. OK on a card plays immediately. Everything
a detail screen would carry lives in the long-press context menu instead.

### The rail

Top to bottom, one scrolling list:

```
  TVIVO                                    ×  ← focusable, "Hide categories"
  🔍 Search Movies                            ← scope always named
  ─────────────────────────────────────
  ALL                                48,751
  FAVOURITES                              —
  CONTINUE WATCHING                      12
  RECENTLY ADDED                         30
  ─────────────────────────────────────
  ARABIC MOVIES 2026                     37
  ENGLISH MOVIES 2026                   419
  …120 more, from the panel, in panel order
```

- The four **virtual entries** are computed by the app, not returned by the
  panel. They sit above a divider so it is clear which is which.
- **Counts show blank, never `0`, until that category has synced.** `0` is a
  claim that the category is empty, which is a different and false statement.
- Category names truncate with ellipsis; the count column is fixed width so
  numbers never move. Panel names are arbitrary length and language, and are
  routinely longer than the app's own labels, so truncation is unavoidable.
- Counts come from one grouped `COUNT(*)` over Room, exposed as a `Flow`, so
  they fill in live as the background sync lands. This doubles as the sync
  progress indicator.

### Focus contract

This is the part a ten-foot UI lives or dies on, and it was previously
unspecified. All of it is required for Phase 2, because Phase 2 is the template
every other content type copies.

**Focus appearance.** Two states, not one:

| State | Rail row | Card |
|---|---|---|
| **Focused** | solid `--accent` fill, full row width, `--on-accent` text | static 4 dp `--accent` frame with dark outer separation |
| **Last-active** (other pane holds focus) | 6 dp `--accent` at 40% on the leading edge | 4 dp `--accent` at 40% |

**No scale on focus.** Scaling a focused card overlaps neighbours, disturbs
label spacing, and adds compositing work at exactly the moment the user is
traversing an image-heavy paged grid on a weak box. A static frame reads
reliably at 3 m and cannot stutter. No decorative shadows. If any motion is
added later it is alpha-only, 100–120 ms, no spring, and it respects the
system reduced-motion setting.

The last-active state exists so that when focus is in the rail, the user can
see where RIGHT will land before pressing it.

**BACK contract** — written out in full, because BACK doing two different
things depending on rail state is the fastest route to accidental app exit:

```
  player            → browse grid, rail state preserved, focus on the played item
  grid, rail closed → open the rail, focus the current category
  grid, rail open   → Home
  Home              → exit confirmation
```

**Rail open/close:**
- LEFT from grid column 0 opens the rail and focuses the current category.
- RIGHT from the rail returns focus to the previously focused grid card.
- Closing the rail moves focus to the grid's last-focused card, never to root.
  Dismissing while focused inside the rail otherwise destroys the focused node
  and the D-pad appears dead.
- Re-opening focuses the **currently selected** category, not the top of the
  list.
- OK on a category loads it and moves focus to grid item 0. RIGHT moves focus
  to the grid without changing category. Focus alone never changes category —
  loading on every focus tick makes rail scrolling feel heavy.

**RIGHT out of the rail must be declared, not inferred** (Q-9). Compose's 2D
focus search has no notion of "the grid is the content and the header is not",
so it resolved RIGHT to the header's `Refresh` and left the grid unreachable by
D-pad. The rail rows carry an explicit `focusProperties { right = ... }` at the
grid. The override only takes effect on the **focused node itself** — on the
rail's parent focus group it is silently ignored, and the symptom is identical
to never having written it.

**Focus restoration** — by stable item ID, never by index. A background refresh
can reorder or remove rows while the player is foregrounded.

- Save `{content type, category, query, view mode, rail state, item stable ID,
  index fallback}` before launching playback, in `SavedStateHandle` — the
  browse Activity *will* be killed under memory pressure while a stream decodes.
- Restore only once the target item is loaded *and* composed. Gate on the
  paging snapshot; `requestFocus()` in a bare `LaunchedEffect(Unit)` fires
  before Paging has delivered the item and focus silently falls to the rail.
- Fallback chain: exact `stream_id` → nearest surviving index → index 0.

**Focus across paging boundaries** — see `architecture.md` for the Paging
configuration this requires. In UI terms: the loading footer is never
focusable, placeholders are focusable but inert (they render the skeleton and
OK is a no-op), and the grid carries `focusGroup()` + `focusRestorer()`.

### Long-press context menu

OK plays. **Long-press OK** opens a small menu over the grid:

```
  Play
  Start over                    (only when a resume position exists)
  Add to favourites / Remove
  Remove from Continue Watching (only when present there)
```

This is the standard Android TV gesture and it is the only affordance that
works from a grid with no detail screen. It is also the *only* way a favourite
can ever be created, so the FAVOURITES empty state has to teach it:
"Nothing here yet. Press and hold OK on any title to add it."

### View modes

Two, toggled from the header, remembered per content type:

- **Posters** — the grid above.
- **Names only** — no artwork at all, 2 columns, rows 88 px / 44 dp, ~18
  titles visible. Immune to the missing-poster problem, and the escape hatch
  when two long titles truncate to look identical.

Rows are 44 dp, not the ~31 dp that "maximum density" first suggested: denser
than that leaves no room for a legible focus state and stops being readable at
3 m, which would make the dense mode the mode nobody can read.

### Series detail

The one extra layer. Built as the browse screen's own layout rather than a new
one: a **season rail** on the left (280 dp, narrower than browse's 360 dp — season
labels are short), an **episode list** on the right, the same `tvFocusFrame`, and
the same LEFT/RIGHT contract. A viewer arriving from the Movies grid should not
have to learn a second set of navigation rules for the same D-pad.

```
┌──────────────┬──────────────────────────────────────────────┐
│ Season 1  60 │  <show name>                        Refresh  │
│ Season 2  30 │ ┌──────────────────────────────────────────┐ │
│ Specials   4 │ │ 01   Episode title              41 min   │ │
│              │ ├──────────────────────────────────────────┤ │
│              │ │ 02   Episode title              40 min   │ │
└──────────────┴──────────────────────────────────────────────┘
```

Rules that are not styling:

- **Focus lands on the episode list, not the rail.** Playing an episode is why
  the user opened the show; the rail is one LEFT away. Without an explicit
  request the screen opens with focus on nothing and the first D-pad press is
  spent finding it — the same defect class as the login focus trap, and just as
  invisible in code review.
- **RIGHT from a season row targets the episode list explicitly.** Left to the
  2D focus search it picks the header's `Refresh`, exactly as it does in browse.
- **Episodes sort by season then episode number**, never alphabetically. This is
  the one list in the app where the panel's own numbering is meaningful.
- **Cached episodes always render, error or not.** A failed refresh must not take
  away a show the user could otherwise still play; the error goes beside the
  list, never instead of it.
- Season `0` is labelled **Specials**, not "Season 0", which reads as a bug.
- A show with no episodes says so ("No episodes listed for this show") and is
  kept distinct from an error state.

## Search — scoped by content type

Search is entered from inside a content type and covers **only** that type.
Three searches exist: Live, Movies, Series. The Home screen is what makes this
unambiguous — you chose Movies, so "Search Movies" means what it says.

- **The field always names its scope.** "Search Movies", never a bare "Search".
- Filtering as you type, debounced 300 ms, run on `Dispatchers.IO`.
- A separate **category filter** in the rail filters the ~120 category names.

**All three content types must be fully synced for this to be honest.** If
only movies were synced, someone searching for a channel would get a confident
"no results" for something that exists — which is exactly the failure mode
`decisions.md` rejected. Live is ~10–20k rows and series is metadata only, so
the cost over movies is marginal.

**ALL, global search, and RECENTLY ADDED stay disabled until the first full
index completes.** A half-synced catalog answering a search is the same lie in
a different place.

## States

Named states are not a specification. Every one below defines what the user
*sees*.

| | Movies / Series grid | Live grid | Rail | Search results |
|---|---|---|---|---|
| **Loading, nothing cached** | skeleton cards at exact final size, no shimmer (shimmer at 3 m reads as flicker); header shows category name immediately | same | skeleton rows | — |
| **Loading, cache present** | render cache instantly, 3 px indeterminate line under the header. Never block | same | counts fill in live | — |
| **Re-filtering a query** | nothing. Sub-300 ms results must not flash a state | same | — | — |
| **Empty** | "Nothing in this category" + Refresh | same | category stays listed with a warning glyph | — |
| **No results for query** | "No movies match *foo*" — must read differently from an empty category, and must name the scope | same | "No categories match *foo*" | |
| **Partial** | banner above the grid: "12 categories couldn't be loaded · Retry", focusable | same | failed categories show a warning glyph instead of a count | "Searching 12,430 of ~48,000 titles so far" |
| **Error** | see Errors | same | | |
| **Success after manual refresh** | inline confirmation for 2 s: "Up to date · 419 titles" or "12 new titles" | same | | |

A refresh that changes nothing is visually identical to a broken button. The
success state is not optional on a remote-driven UI.

**Partial is the normal case, not an edge case.** With 120 categories fetched
individually, "90 succeeded, 30 failed" is routine.

**Offline is fully browsable.** The whole catalog is local, so a dead network
must fail only at play time. Do not throw a full-screen error over data the app
already has.

### Player states

Previously absent entirely:

- **Within 100 ms of OK** — fullscreen scrim with the item's poster, title, and
  an indeterminate bar. A black screen for the 3–10 s a progressive `.mkv`
  takes to open reads as a crash. This scrim is also the surface the
  first-frame watchdog replaces with an error.
- **Resume prompt** — "Resume from 34:12" / "Start over", when a resume
  position exists.
- **Buffering past threshold**, **first-frame watchdog fired**, and
  **connection-limit probable** all map to `AppError` (see `architecture.md`).

## Errors

One `AppError` taxonomy, **two renderers**: `ErrorState` (full-screen, for
refresh-time failure) and `ErrorFooter` (inline, focusable, one line, for
paging *append* failure). A failed append must never take over the screen and
destroy 400 items the user is already reading.

Each error carries its own action. A retry button on an error that retrying
cannot fix teaches the user the app is broken:

| Error | Primary action | Copy principle |
|---|---|---|
| `Unreachable` | Retry + **Edit server** | name the thing to check |
| `AuthFailed` | **Edit credentials**, no retry | return to login with fields populated, focus password |
| `AccountExpired` | **Dismiss** only | use the real `exp_date`: "This account expired on 12 March 2026" |
| `ConnectionLimitReached` | Retry | probabilistic: "Another device may be using this account" |
| `StreamUnavailable` | Back to list, no retry | |
| `Timeout` | Retry | |
| `Empty` | Refresh | empty-state visual, not an error visual |

## Login

The first impression, on the worst input device. A realistic credential set is
~44 characters, which on a D-pad grid keyboard is 200+ directional presses.

1. **One server field, not two.** Accept `host:port`, `http://host:port`, or a
   bare `host`, and parse it. Deletes a whole field, deletes the numeric-keyboard
   problem on TVs whose IME ignores `KeyboardType.Number`, and matches how
   credentials are actually received. If the port is omitted, correct it from
   `server_info` once auth succeeds.
2. **Show-password toggle.** A masked field typed blind on a remote with no way
   to verify guarantees a failed login costing another minute.
3. **Never clear the form on failure.** On `AuthFailed` keep every field, focus
   password, select-all so overtyping replaces.
4. **Validate incrementally.** Resolve the host when the server field is
   committed, so a typo surfaces inline before a password is typed.
5. Open the IME automatically on the first field; OK advances to the next.

## Cold start and sync

Credentials are entered once. The daily refresh is the repeated case, and it is
the one that matters.

**The sync never blocks.** Categories are one fast call, so a content type is
usable in about a second. The full catalog syncs in the background behind it,
with a **visible percentage that dismisses at 100%**. The rail counts filling in
category by category are the secondary progress signal.

`ALL` and search stay disabled until that first index completes, then activate.

## Content realities that break a naive design

From real panel responses, not assumptions:

- **Titles are frequently Arabic (RTL) or mixed Arabic/English in one string.**
  `"HD  للعدالة وجه آخر"` begins with a strong LTR run, so a naive renderer
  resolves the whole paragraph as LTR and puts the ellipsis on the wrong visual
  edge. Truncation then looks random across a large fraction of the catalog.
  Render a `name_display` column, resolve direction from the first strong
  character, align to `Start`, and bidi-isolate embedded Latin and numeric runs.
- **Titles carry junk.** `"HD  للعدالة وجه آخر"` — a quality prefix and a
  doubled space. `"السادة الافاضل (2026) FHD"` — a quality suffix. Expect noisy,
  inconsistent, sometimes very long names.
- **Quality markers become a chip**, lifted out of the title into a poster
  corner — but only on strict parsing. Match standalone prefix/suffix tokens
  only, after normalisation; strip from the display title only when parsing
  succeeds; show one chip with priority `4K > FHD > HD`; hide on ambiguity.
  Blind stripping removes legitimate title text.
- **Ratings are usually `0`** but not always — the reference app shows populated
  values on some titles. Show the badge when present, never reserve space for it.
- **Metadata is often empty strings.** `plot`, `genre`, `cast`, `director`,
  `releaseDate`, `youtube_trailer` are frequently `""`, `backdrop_path` often
  `[]`. This is the main reason there is no movie detail screen.
- **Artwork may be missing or fail to load.** Posters are on a different domain
  from the API. Placeholder treatment: typeset the title *inside* the card over
  a deterministic tint from `hash(stream_id) % 8` across a fixed palette, so the
  same film always looks the same and a wall of missing posters reads as a
  designed mosaic rather than a wall of errors. Reserve the artwork bounds from
  first composition so success or failure never shifts layout. Static, never
  per-card shimmer.
- **Categories are unordered and uncurated.** ~120, mixing language, era, genre
  and studio with no hierarchy, rendered as-is. The rail filter is what makes
  that navigable.

## Decided (see `decisions.md` for rationale)

- **Compose for TV** (`androidx.tv:tv-material`) for all browse UI.
- **Player is Media3 `PlayerView`** in an `AndroidView`, not Compose-native.
- **Lists are paged** (Paging 3), with `enablePlaceholders = true` and stable
  keys — both required for focus restoration to work at all.
- **Full catalog sync** for all three content types, accepted deliberately.
- **Search scoped to content type**, entered from Home.
- **Scrub bar hidden for live** — progressive live streams cannot seek.
- **Card sizes fixed** at `POSTER(220×330)` and `CHANNEL(220×124)`.
- **OK plays; long-press OK opens the context menu.** No movie detail screen.
- **Static focus frame, no scale.**
- **`ALL` renders a grid in panel order.** Accepted cost: with 9,750 rows and
  no jump affordance, the first screen of `ALL` is effectively a random 10 of
  48,751, and the end of the list is unreachable in practice.
- **Category grids sort alphabetically on `name_normalized`**, plain code-unit
  order (no ICU collator). Latin-named titles group before Arabic-named ones
  rather than interleaving. See `decisions.md`. The **episode list is the one
  exception** — season then `episode_num`.
- **A show is not playable.** Activating one opens the season/episode picker;
  only movies, channels and episodes reach the player. See Series detail.

## Continue watching

- A row appears after **>60 s watched** and while **<92% complete**.
- Removed on crossing 92%.
- Ordered by last-played descending, **capped at 50**, oldest evicted.
- Removable explicitly from the context menu.
- **Never mutate the list while the user is standing in it.** If they finish a
  film and press BACK, the card they are focused on must not vanish from under
  them. Re-sort on next entry, not on return.
- **Live is excluded** — a live stream has no meaningful resume point.

## What already exists to reuse

- `androidx.tv:tv-material` `Surface` ships TV-tuned focus defaults. Adopt its
  focus plumbing rather than inventing focus handling; override the *appearance*
  to the static frame above.
- Media3 `PlayerView` ships a TV transport layout with D-pad handling already
  correct. That is why the player stays on it.
- Google's JetStream sample is the reference for grid + focus + paging together.
- A screenshot of a working IPTV client the user already uses is the layout this
  design adapts. It is deliberately **not committed** — this repo is public and
  the image carries another provider's branding. Everything it settled is
  written down here and in `decisions.md`, so nothing depends on having it.

## What does not exist

- **No DESIGN.md.** Colour, contrast, typography, layout and focus are now
  specified above, but there is still no motion spec, no icon set, and no
  defined wordmark. Run `/design-consultation` before Phase 5 to finish the
  system rather than improvising the rest.
- **No product identity.** Both outside reviewers flagged this: the first
  screen is currently a wall of provider posters with nothing that says what
  app this is. The Home screen is the opportunity to fix it.

## Not in scope

Design decisions considered and explicitly deferred:

- **EPG / program guide** — a whole second data model and UI; unrelated to the
  browse-and-play loop this POC exists to prove.
- **Timeshift / catch-up** — `tv_archive` is `0` on the observed channels.
- **Cross-content-type search** — Home makes the scope unambiguous, so grouping
  results across types buys clarity nothing and costs a result-type model.
- **A curated category taxonomy** — inventing hierarchy over 120 arbitrary
  panel names is guesswork; the filter solves navigation instead.
- **Illustrated empty and error states** — text-only until a design system
  exists to draw them against.
- **Fast-scroll / jump-to-letter in `ALL`** — accepted cost of the panel-order
  decision above; revisit if `ALL` proves unusable.
- **Voice search** — most Android TV remotes have a microphone, but wiring it
  up is a whole integration for the one screen that already has a keyboard.
- **Settings beyond credentials** and the hidden diagnostics screen.
- **LG webOS** — out of scope until the Android POC works end to end.

## Still open

1. **Motion.** No durations, no easing curves, no reduced-motion policy. Focus
   is deliberately static, so this only matters for transitions between screens.
2. **Iconography and wordmark.** The rail shows a text wordmark as a placeholder.
3. **Idle behaviour.** A static rail on an OLED panel needs a dim or
   screensaver after ~5 minutes.
4. **Where the manual refresh control lives**, and how it is reached by D-pad.
5. **Key-repeat and fast-scroll behaviour** when holding a direction on a long
   list.
