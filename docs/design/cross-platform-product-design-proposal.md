# Tvivo cross-platform media product design

**Status: Working draft — open for review and iterative edits; not approved for implementation.**

This proposal records the binding product direction supplied by the owner on 2026-09-20. It is a design document, not implementation authorization. The Windows Live TV work is the first Windows capability; it does not define Tvivo as a channel-list utility. Tvivo is a complete cross-platform media product from the beginning.

## Product promise

**Tvivo brings live channels, films, and series into one personal media experience. Find something worth watching, pick up where you left off, and keep your choices with you across Tvivo devices.**

Home, Movies, Series, Live TV, Search, Favorites, Continue Watching, Account, Settings, and Player belong to one product model. A platform or provider may not yet support every capability, but gaps must be represented honestly rather than narrowing the product identity.

## Market references and design opportunity

| Reference | Strong pattern | What Tvivo should improve |
|---|---|---|
| Netflix TV | Clear content-type destinations, profiles, and a personal destination for saved and in-progress titles. [Netflix TV experience](https://help.netflix.com/en/node/321880164349028) | Avoid making Home depend on opaque recommendations or on a single content service's editorial choices. Make each shelf traceable to actual provider data or the user's own actions. |
| Plex big-screen apps | Home combines recommendations and Continue Watching; navigation can be customized around the libraries a person uses. [Plex navigation](https://support.plex.tv/articles/navigating-the-big-screen-apps/), [recommendation management](https://support.plex.tv/articles/manage-recommendations/) | Keep discovery and library access connected without requiring users to configure a source sidebar before finding content. |
| Apple TV app | Up Next carries viewing intent between devices; supported connected apps can contribute to that experience. [Apple TV app and Up Next](https://apps.apple.com/us/story/id1462931150), [cross-device availability](https://support.apple.com/en-gb/guide/iphone/iphbf1137ac0/ios) | Make Tvivo's own queue dependable and label its scope. Do not imply that every provider or content type participates when that integration is unavailable. |

These references show effective patterns, not a visual template to copy. Tvivo's opportunity is a coherent media home across sources and devices with a signal-led identity, honest provider-data boundaries, and direct access to both discovery and the full library. Avoid recommendation rows that crowd out destinations or claim knowledge the provider does not supply.

## Shared visual language

Retain the deep teal-black and coral signal direction. It provides a distinct, calm foundation already present in the Android reference palette: `#0A1619` background, `#0F2126` and `#163036` layered surfaces, pale ink for primary text, and coral `#D97757` for active emphasis. The lighter `#E08466` is reserved for text where contrast requires it. Treat these as semantic roles to translate into each platform's native theme, not a requirement that every platform render identical chrome.

The emotional tone is **late-night signal room**: quiet, assured, and focused on artwork and video. Use generous dark space around media, restrained linework, and coral for meaningful actions and current position. The signal mark may inform small progress or state treatments; avoid ambient decoration and persistent animation.

Artwork is core interface content:

- Movies and Series use supplied poster or cover art. Missing art gets a branded, quiet fallback; valid playable items remain visible.
- Live TV uses fitted channel logos on a neutral surface. Logos keep their original proportions and transparency; they are not cropped into movie posters.
- A large hero appears only when useful artwork and a supported next action exist. Do not fabricate backdrops, schedules, ratings, genres, or availability.
- Home shelves derive from cached items. Use honest labels such as **Continue Watching**, **Recently Indexed**, or **Suggestions**, and omit shelves with no qualifying content.

## Shared navigation model

Keep destinations and meaning consistent across platforms while adapting their placement and input behavior to TV remote, mouse and keyboard, touch, and future platforms.

**Primary:** Home, Movies, Series, Live TV, Search.

**Personal library:** Favorites and Continue Watching, grouped under a clear destination such as **My Tvivo**. Both remain reachable from Home and have their own browse destination.

**Account:** Account and Settings are separate from everyday content browsing. Account covers Tvivo identity and connected provider accounts. Settings covers playback, appearance, accessibility, and sync preferences.

**Player:** a focused playback destination reached from a title or channel. Returning from it restores the originating item and browse state. Player is not a competing content category.

For Windows, use WinUI-native adaptive `NavigationView` for the shared destinations, allowing its native expanded/compact presentation to respond to available space. Keep artwork and media browsing in the content canvas rather than hard-coding a fixed sidebar width. Android TV retains a remote-readable rail with reliable D-pad traversal. Future compact platforms adapt the same destinations to an appropriate native navigation pattern. Back follows the actual navigation path.

Search is a global product destination with visible Movies, Series, and Live TV scope filters. Results must disclose which types are indexed and ready. This is a proposed product-model change from the Android UI design, where search is entered inside a selected content type.

## Screen compositions

### Home

Home answers **“What can I pick up?”** and **“Where do I want to browse?”**

1. A restrained Tvivo identity/header area, current profile or provider context, and direct Search action.
2. **Continue Watching** when resumable Movies or Series exist, showing progress only when position and duration are known.
3. A featured discovery area using real cached artwork and one clear action: open details, play, browse, or continue.
4. Prominent Movies, Series, and Live TV entry points, with poster art distinguished from channel artwork.
5. Optional Recently Indexed, Favorites, or Suggestions shelves only when supported by data.

Home is not a blocking idle carousel. If there is no history or artwork, show deliberate first-run routes into Movies, Series, Live TV, and provider setup. A failed refresh preserves cached rows and gives a clear retry action.

### Movies

A poster-led browse surface with a clear title and active filters. A compact category selector, search, sorting/filter actions, and a virtualized poster grid support discovery and large catalogs. Activating a movie opens a pre-play detail surface with available artwork, title, known metadata, favorite action, and Play. Do not leave a blank synopsis region when description data is absent.

### Series

Use the same browse grammar and artwork treatment as Movies, followed by a series detail/episode-selection step before playback. Distinguish **Continue Watching** from **Next Episode** only when episode history supports both. A show is not presented as directly playable.

### Live TV

Design a channel discovery surface, not an admin table. Browse by group, search channel names, and show fitted logos, channel names, and a clear tune action. Desktop may show a denser virtualized card layout when space allows; TV prioritizes remote focus and legibility. Do not show an EPG or “Now Playing” treatment until verified program data is available.

### Search, Favorites, Continue Watching

Search combines selected types only when their indexes are ready; filters and empty/error states explain scope. Favorites is a browsable destination organized by type, or by user-created groups only if that capability is supported. Continue Watching contains Movies and Series with valid resume positions. Live TV belongs in recent channels or favorites, not in a fabricated resume queue.

### Account and Settings

Keep Tvivo identity separate from provider credentials. Account presents connected services, current profile, sync state, and explicit add/switch/remove actions. Settings exposes sync and privacy choices in plain language. Switching provider accounts must never silently mix catalogs or playback state.

### Player

Playback is picture-first. The video surface owns the content area; controls sit in a clear, accessible region appropriate to the platform. On Windows, preserve the existing design proposal's boundary: do not put WinUI controls over a heavyweight native video surface if they can become invisible or unclickable. Keyboard controls and future remote mappings should provide consistent Play/Pause, seek, audio/subtitle, and Back behavior. Omit or disable unsupported actions instead of implying support.

## Discovery and browsing flows

- **Home to playback:** Continue Watching resumes a known position; a movie opens its pre-play detail; a series opens episode selection; a channel tunes directly or through its supported channel route.
- **Home to library:** a content-type entry opens its media destination with cached content first, then refresh status. “See all” from a shelf opens the matching filtered destination.
- **Search to playback:** choose or retain visible type scope, review matching results, then follow the same title/channel route as ordinary browsing.
- **Favorite to playback:** favorite from details or a card action; Favorites resolves to the corresponding type-specific browse surface.
- **Return paths:** details, episode picker, and Player return to the previous selection and scroll position where the platform supports it.

Do not make Home the only route to content. Users can enter a library directly, browse categories, search, or return to a favorite/continuation destination.

## Cross-device account behavior

Model two identities separately:

- **Tvivo account/profile:** the person or household identity used to carry Tvivo preferences and viewing state between Tvivo apps.
- **Provider connection:** the user-entered media-service credentials and catalog source used to browse and play.

A provider login alone does not establish Tvivo-wide synchronization. Current Android data scopes catalog, favorites, and resume locally by provider account; desktop also stores favorites and resume locally. Cross-platform sync therefore needs a Tvivo service or another deliberate shared-state mechanism that is not established by the inspected product evidence.

The intended experience is for a user to sign in to one Tvivo profile on Android, Windows, and later platforms; opted-in favorites, resume positions, and shared preferences follow them. Each device keeps a local cache for fast, offline browsing. Sync status is visible, reconnection reconciles predictably, and users can disable or clear synchronized activity.

Before promising this, define content matching across providers. Numeric provider IDs can be reused or source-specific; sync keys need a stable provider/source identity and content type, or a verified cross-provider identity. Matching titles by name alone is unsafe. Decide whether the product supports individual profiles, household profiles, or both, and which histories are shared.

## Interaction, motion, focus, and performance

- **Mouse and keyboard:** hover previews an action without stealing keyboard focus; click activates. Tab follows reading/action order. Enter/Space activates, Escape/Back returns, and arrows navigate media grids without hijacking text input or sliders.
- **Remote readiness:** every visible action has a D-pad path, unmistakable focus, predictable entry/return focus, and TV-distance legibility. Keep focus in the TV-safe region and keep text entry limited.
- **State distinction:** hover uses a quiet surface lift; keyboard/remote focus uses the coral signal treatment; selection remains visible after focus moves. Never rely on color alone.
- **Motion:** short fades and restrained card elevation/scale; gentle artwork crossfades. Avoid auto-playing trailers, large parallax, continuous hero movement, and transitions that delay browsing. Honor reduced-motion settings.
- **Catalog scale:** use native virtualized grids/lists, stable item identity, incremental loading where available, and appropriately sized cached artwork. Bound Home shelves to useful visible items; “See all” opens the full browse destination. Do not load full-resolution artwork for every result.
- **Network states:** show cached content immediately when present, with a discreet refreshing state. Failed refresh preserves cached content and offers Retry. Without cache, distinguish sign-in, offline, and genuinely empty states.
- **Metadata truth:** distinguish no content, not yet indexed, missing artwork, offline, and authentication failure. Show schedule, rating, genre, availability, or “recently added” only when data verifies the claim.

## Capability boundaries

Current project sources support Live TV, Movies, Series, provider authentication, local catalog caching, local favorites, local resume state, and playback routes in Android/Compose Desktop. The WinUI prototype provides Home/setup, provider setup, a channel group/channel catalog, and a Player surface. The desktop discovery document is a proposal; Android product documents are TV-focused and do not yet describe this cross-platform promise.

Keep three truths distinct:

1. **Product identity:** Movies, Series, and Live TV are peers in Tvivo from the beginning.
2. **Delivery order:** WinUI Live TV is only the first Windows capability.
3. **Verified service/data:** schedules, cross-provider identity, global synchronization, and personalized recommendations are not established by the inspected evidence.

The current WinUI Home is setup-oriented, the Catalog is text-first, and Player is a separate shell surface. These are starting materials to surpass. Android TV comps provide a useful tested palette and artwork reference, but their ten-foot layout and remote-only assumptions should not be copied literally to Windows.

## Smallest first Windows design slice

Design a **cross-type Tvivo Home and shell proof**, not a channel-list redesign:

- Present shared navigation for Home, Movies, Series, Live TV, Search, My Tvivo, Account, and Settings. Destinations without Windows capability must be represented honestly rather than fabricated as working flows.
- Make Home a media surface: one real artwork-led discovery feature, Continue Watching only when valid local state exists, and clear Movies/Series/Live TV entry points.
- Use available cached records and artwork. If a type lacks Windows data, show an honest “not available on this device yet” or designed empty state.
- Define shared poster cards, fitted channel-logo cards, hover/focus/selected states, and one detail-to-Player route that returns to the originating selection.
- Include cached, refreshing, empty, and failed-provider states in design review so recovery is part of the identity.

This is a review slice for product direction, not permission to implement Search, Tvivo sign-in, or cross-device sync.

## Current references reviewed

- Product authority and direction: [PRODUCT-BIBLE.md](../../PRODUCT-BIBLE.md), [PROJECT-BIBLE.md](../../PROJECT-BIBLE.md), [UI scope and Android TV reference](../ui-scope.md), [design decisions](../decisions.md).
- Compose Desktop: [desktop README](../../desktop/README.md), [Compose desktop shell](../../desktop/src/main/kotlin/com/dev/tvivo/desktop/DesktopShell.kt), [desktop Home proposal](desktop-home-proposal.md), [desktop Player proposal](desktop-player.md).
- WinUI starting point: [MainWindow.xaml](../../windows-spike/src/Tvivo.App/MainWindow.xaml), [HomePage.xaml](../../windows-spike/src/Tvivo.App/Pages/HomePage.xaml), [CatalogLandingPage.xaml](../../windows-spike/src/Tvivo.App/Pages/CatalogLandingPage.xaml), [core contracts](../../windows-spike/src/Tvivo.Core/Contracts.cs), [current project state](../../windows-spike/CURRENT-PROJECT-STATE.md).
- Android reference: [palette](../../app/src/main/java/com/dev/Tvivo/ui/theme/Palette.kt), [Home](../../app/src/main/java/com/dev/Tvivo/ui/home/HomeScreen.kt), [browse](../../app/src/main/java/com/dev/Tvivo/ui/browse/BrowseScreen.kt), [favorites/resume entities](../../app/src/main/java/com/dev/Tvivo/data/local/entities/Entities.kt), [approved comps](comps.html).
- Visual evidence: [WinUI Home](../../windows-spike/phase2-home.png), [Catalog error](../../windows-spike/phase2-catalog-error.png), [provider error](../../windows-spike/phase2-provider-error.png), [catalog/player frame](../../windows-spike/step4-catalog-player-firstframe.png), and [artwork references](img/).

## Unresolved product decisions

1. What is the Tvivo identity provider and sync service? Must Tvivo work fully in local-only mode?
2. Are profiles individual, household, or both? Which preferences and histories are shared?
3. How does Tvivo identify the same title across provider catalogs with reused or source-specific IDs?
4. Are Favorites and Continue Watching profile-specific, Tvivo-account-wide, or user-configurable?
5. Does global Search span every indexed type by default, or require choosing a type first?
6. Which provider metadata can Tvivo trust for artwork, release dates, ratings, genres, and Live TV schedules?
7. For Windows destinations before their capability is delivered, should navigation show disabled destinations, explain availability, or omit them until ready?
8. Which Home shelves can be populated from dependable data on each provider without presenting random or weakly personalized picks as recommendations?

## Review and approval log

- **Current round:** proposal saved for owner review; edits and discussion are welcome.
- **Implementation approval:** not granted by this document. Implementation planning or code changes require a separate explicit approval after the design direction is accepted.
