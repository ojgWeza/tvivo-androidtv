# Desktop Home proposal

This is a product handoff for D-Desktop-16. It defines the intended Home and
library-tab behaviour; it does not authorise playback, catalog, schema, or
tracking changes by itself.

## Product intent

Tvivo should open like an enjoyable media library, not an empty dashboard.
Discovery is immediate, while Movies, Series, and Live TV remain distinct
libraries with honest, useful shelves. The UI must work entirely from cached
catalog data when the network is unavailable.

## Arrival and idle screen

After a successful sign-in or a normal app launch, first show an immersive
discovery screen populated from the ten most recently added eligible items
across Movies and Series. Each frame shows one poster/backdrop, title, and a
clear content-type label. Selecting an item follows its ordinary route:
movies open their pre-run/play route; series open the episode picker.

The screen is an entry/discovery layer, not a blocking splash. Mouse movement,
keyboard input, click, or an explicit browse action immediately reveals the
last selected library tab. If cached eligible items do not exist, skip the
layer and enter Movies with an actionable cached/loading/empty state.

After five minutes without user input, show a dimmed version of this layer as
the in-app idle treatment. It must slowly rotate content and dim rather than
hold one static poster, to avoid OLED burn-in. Any user input returns to the
same tab, scroll position, and selected card. Never call it a system
screensaver or prevent the operating system's own lock/screen-saver policy.

"Recently added" requires a reliable provider timestamp. If it is absent or
untrustworthy, label the shelf and discovery source "Recently indexed" rather
than claiming it is new.

## Library tabs

The persistent top navigation owns Home, Movies, Series, Live TV, and Account.
The three content tabs are the main browsing surfaces. Each tab begins with
unfolded shelves, then retains the existing category rail/search browse path
behind a visible `See all` action.

| Tab | Ordered shelves |
| --- | --- |
| Movies | Recently added/indexed; Suggestions; Continue watching; favourite folders |
| Series | Recently added/indexed; Suggestions; Continue watching; favourite folders |
| Live TV | Recently watched channels; Suggestions; favourite folders; newly indexed channels when trustworthy |

Rules for every tab:

- Render a shelf only when it has content. Empty continuation or favourite
  panes must not occupy space or explain their absence.
- `See all` opens Browse with the matching virtual folder or filter selected.
- Preserve tab, scroll position, and focused/selected card when returning from
  Browse, details, an episode picker, or the player.
- Cards must state their destination: Movies and Live TV are playable; Series
  says `View episodes`. Do not make a series appear directly playable.
- Continue-watching cards show progress and remaining time where known. Series
  cards also show season and episode. Live TV has no resume progress.
- Loading, offline, partial-refresh, and failed-refresh states preserve cached
  shelves and surface a concise, actionable status near Refresh.

## Suggestions v0

Suggestions are a session-stable random sample from the current tab's cached
catalog. Generate it once at app open and keep it stable until the next app
open or an explicit refresh; avoid duplicates with visible shelves and exclude
unplayable/broken records. The label is deliberately `Suggestions`, not
`Recommended for you`: v0 has no personalisation data.

Suggested initial selection rules:

1. Select only from the active content type.
2. Prefer items with usable artwork, but do not hide valid items merely because
   artwork is missing.
3. Exclude completed items from continuation, not from ordinary Suggestions.
4. Cap each shelf to the number of whole cards that fits the current viewport;
   `See all` handles the remainder.

## Favourite folders

Show each non-empty favourite folder as its own pane. On first use, the app may
offer a small editable starter set, but must not silently create a cluttered
taxonomy. The user can rename, reorder, remove, and add folders; an empty
folder stays hidden from Home until it contains an item. Favourites and folders
remain local to the device and account-scoped.

## Future personalisation

Do not collect or infer preference data before an explicit Account opt-in:
`Use watch history on this device to improve Suggestions.` Explain the benefit,
keep the calculation local by default, and provide a one-step disable/delete
path. Favourite actions, completed/abandoned playback, and browsed categories
are sufficient first signals. Genre-based ranking is future work and is gated
on verified, consistently available provider genre metadata.

## Acceptance evidence for the implementation pass

- A first-run library with no history contains no empty continuation/favourite
  shelves and presents a clear next action.
- A cached library with qualifying movies/series shows the entry layer, and one
  input returns to the expected saved tab without losing selection.
- Five minutes of idle time enters the dimmed rotating treatment; input restores
  the previous UI state.
- Each content tab shows only its eligible shelves; `See all` opens the correct
  browse destination.
- Suggestions stay unchanged during an app session and change only on the next
  app open or explicit refresh.
- Offline and partial-refresh states keep cached content reachable and describe
  the recovery action without exposing provider details.
