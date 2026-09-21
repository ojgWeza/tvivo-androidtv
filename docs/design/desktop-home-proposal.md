# Desktop Home proposal

This is a product handoff for D-Desktop-16 and the cross-platform Tvivo Home direction. It defines Home, personal activity, library tabs, and discovery behaviour; it does not authorise playback, catalog, schema, sync, or tracking changes by itself.

## Product intent

Tvivo should open into the user's media world, not an empty dashboard and not a destination-choice screen. The first screen is My Tvivo: it remembers the user's last meaningful activity, exposes recent history, and helps discover something new. Movies, Series, and Live TV remain distinct libraries with honest, useful shelves. The UI must work from cached catalog data when the network is unavailable.

## My Tvivo arrival screen

After sign-in or normal launch, open My Tvivo. The primary feature is Pick up where you left off: the most recent resumable or reopenable item, regardless of content type. It may be a movie, series episode, channel, folder, or browse destination. The card's action must match its type: Resume, View episode, Tune in, or Open.

Under it, show Recent activity as a mixed-content horizontal shelf ordered by last meaningful interaction, newest on the left in LTR layouts and newest on the right in RTL layouts. It may contain movies, episodes, channels, folders, and library destinations. Each item displays its content type and action; it must not pretend that every item is resumable.

Recent activity is history, not recommendation. It is deterministic, account-scoped, bounded for display, and supports See all for the complete history. Opening an item updates its recency without duplicating it.

## Tvivo Spotlight

My Tvivo may include a large cinematic Tvivo Spotlight panel. This is an interactive discovery surface, not a static list and not a system screensaver.

The panel selects a session-stable pool of up to 20 eligible movies or series from cached data. It displays one title at a time:

- large poster or backdrop with a designed missing-art fallback;
- title and content-type label;
- only metadata verified by the provider;
- one clear action, such as Open details, Play, or View episodes;
- progress dots or a compact position indicator;
- previous/next controls for keyboard and pointer input.

Automatic rotation is deliberately restrained:

- hold the first frame for about 9 seconds;
- hold subsequent frames for about 7–8 seconds;
- use a 500–700 ms crossfade or equivalent non-distracting transition;
- pause immediately on pointer hover, keyboard focus, text entry, or explicit interaction;
- resume only after the user leaves the panel and no control has focus;
- support reduced motion by replacing the transition with an immediate or short fade;
- never autoplay a trailer or provider stream in the Spotlight.

The 20-item pool is not necessarily 20 visible slides in a fixed order. It is a candidate pool ranked from available evidence, with already-completed or repeatedly dismissed items down-ranked. A session must remain stable so the panel does not change while the user is deciding.

## Library tabs

The persistent top navigation owns My Tvivo, Movies, Series, Live TV, Search, and Account/Settings. The three content tabs are the main browsing surfaces. Each tab begins with its own content-aware shelves and retains the category/folder browse path behind visible See all actions.

| Tab | Default shelves |
| --- | --- |
| My Tvivo | Pick up where you left off; Recent activity; Tvivo Spotlight; Favorites; high-use folder shelves |
| Movies | Recently added/indexed; Suggestions; Continue watching; Favorites; high-use movie folders |
| Series | Recently added/indexed; Suggestions; Continue watching; Next episode; Favorites; high-use series folders |
| Live TV | Recently watched channels; Suggestions; Favorites; high-use channel groups; newly indexed channels when trustworthy |

TV must not show Continue Watching unless the provider and player support a meaningful resume concept. Use Recently watched for ordinary live channels. Series cannot be represented as directly playable; they open the episode picker.

## High-use folders

A folder or provider group can become a personalized shelf when the user repeatedly opens it or watches items from it. Do not create a permanent shelf after one accidental visit. The ranking signal should combine open count, recency, actual item interaction, and explicit favorites.

A high-use folder shelf:

- is labelled From [folder name];
- contains the folder's real items in a horizontally scrollable virtualized collection;
- shows as many whole cards as fit, with See all for the complete folder;
- preserves the provider's folder name and account scope;
- can be hidden, reordered, or removed from My Tvivo;
- does not claim that a folder is a genre or curated recommendation unless provider data proves it.

The screen must not become a wall of shelves. Apply a visible-shelf limit, rank candidates, and place the remainder behind Customize My Tvivo or See all.

## Shelf rules

- Render a shelf only when it has content; empty continuation and favorite sections do not occupy unexplained space.
- Recently added requires a reliable provider timestamp. Otherwise use Recently indexed.
- Suggestions in the first implementation are session-stable and labelled Suggestions, not Recommended for you, because true personalization is not yet established.
- Suggestions must not duplicate visible Recent activity, Continue watching, or Favorites unless the candidate pool is too small.
- Every card states its destination: Movies and Live TV are playable; Series says View episodes; folders say Open folder.
- Cached rows remain visible during refresh and after refresh failure.
- Preserve tab, scroll position, and focused/selected card when returning from browse, details, episode selection, or Player.

## Personalization boundary

Do not silently infer or upload preference data. v0 can use local recency, explicit favorites, folder opens, and playback completion to shape My Tvivo. Future recommendation personalization requires an explicit Account opt-in, a clear explanation, and a disable/delete path. Cross-device sync is a separate Tvivo-account capability and must not be implied by local history.

## Acceptance evidence

- A first-run user with no history sees an honest next action and no empty fake shelves.
- A returning user sees the last meaningful item in Pick up where you left off, regardless of content type.
- Recent activity is mixed-content, deterministic, newest-first, and updates without duplicates.
- Spotlight holds long enough to understand, pauses on interaction, supports manual controls, and respects reduced motion.
- Spotlight content remains stable during a session and does not autoplay a stream or trailer.
- Movies, Series, and Live TV expose different eligible shelves; TV does not fake resume.
- Repeated folder usage creates a ranked, removable shelf; one-off folder visits do not.
- See all opens the correct browse context and preserves return state.
- Offline, empty, and failed-refresh states keep cached content reachable and expose the correct next action.

## Implementation TODO

1. Define the cross-type RecentActivityItem contract and stable identity rules.
2. Define the resumable/reopenable state contract for movies, episodes, channels, folders, and browse destinations.
3. Add account-scoped local history persistence with explicit retention and clear-history behaviour.
4. Define the candidate-pool query for Spotlight and its session seed/stability rules.
5. Define the high-use-folder scoring thresholds and visible-shelf cap.
6. Confirm provider timestamp trust rules for Recently added versus Recently indexed.
7. Define missing-art fallback for poster/backdrop and fitted channel-logo artwork separately.
8. Define Windows keyboard/pointer focus, hover pause, manual navigation, and reduced-motion behaviour.
9. Add telemetry only after the Account opt-in decision; no silent personalisation instrumentation.
10. Add controlled acceptance fixtures for mixed recent history, 20 Spotlight candidates, repeated folders, empty cache, partial refresh, and refresh failure.
11. Design and validate the My Tvivo, Movies, Series, and Live TV states in Figma before changing WinUI XAML.
12. After visual approval, implement one vertical slice in WinUI: My Tvivo shell, Spotlight, Recent activity, and one folder shelf, while preserving existing catalog behaviour.

## Scope boundary

This proposal intentionally does not approve Movies/Series ingestion, Tvivo account sync, provider metadata enrichment, EPG, trailer autoplay, or production recommendation ranking. Those require separate contracts and acceptance gates.