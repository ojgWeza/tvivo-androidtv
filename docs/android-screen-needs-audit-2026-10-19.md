# Android screen-by-screen needs audit — 2026-10-19

**Scope.** I read the source on `android/finalize-parity`, including uncommitted files. I did not run Gradle, an emulator or adb, so "reachable" means reachable in the code, not observed on a device. I compared against `windows-spike/src/Tvivo.App` (WinUI), `docs/android-parity-audit-2026-10-16.md` and `PRODUCT-BIBLE.md`. The question for each screen: what does a person watching films, series or live TV need, or enjoy, and can they find it on a **phone**, a **tablet** and a **TV** (D-pad)?

**Legend.** ✅ reachable and visible · ◐ exists but hard to find, or only partly done · ✗ missing. **Need** = a real user need (it causes friction, a dead end or a broken promise). **Nice** = it adds fun or polish. Priority: **P0** blocks basic use, or a user gets stuck · **P1** a clear gap a normal user will hit · **P2** polish or delight.

Already decided, so not re-argued here: TV Back leaves the player on the first press (`docs/decisions.md` 2026-10-18). Every card opens a detail page first. Voice search, casting/DLNA and the EPG guide are out of scope (`PRODUCT-BIBLE.md` "Out of Scope").

---

## 1. How you get around (shell)

| Form factor | What exists (`ui/shell/AppShell.kt`) | Problem |
|---|---|---|
| TV | Top bar: For You · Movies · Series · Live TV, plus Search, Sort, Refresh, Theme, Settings icons (l.76-103) | ✅ Complete. Icon labels only appear when focused, which is fine on a TV. |
| Phone (Compact) | Bottom bar: My Tvivo · Movies · Series · Live · Search. **Settings is filtered out** (l.116). The only way in is a gear in the Home **list header** (`HomeScreen.kt` l.250), which scrolls away and is missing from Movies, Series, Live and Search. Tabs are text only; Search is the only tab with an icon. | **P0.** Settings, theme, account and diagnostics are effectively hidden. Text-only tabs are harder to read for seniors. |
| Tablet (Medium/Expanded) | Side rail lists every tab, including Search and Settings (l.129) | ✅ Fine. |
| Phone and tablet, outside the shell | Item detail, Series detail, Subscription, Diagnostics and Network diagnostics are separate routes (`MainActivity.kt` l.634-770). They have **no top bar, no back arrow and no bottom bar**: none of those screens has an on-screen back control. | **P0.** This is the "no visible navigation bar" problem. The bottom bar disappears and the only way out is the system Back gesture. |

## 2. Screen-by-screen

| Screen | What the user wants to do | Android today: phone / tablet / TV | WinUI | Gaps |
|---|---|---|---|---|
| **My Tvivo (Home)** | Pick up where I left off; see what's new; get to favourites; find something | Shelves: Continue watching, Recently added, Last watched, Favourites, Suggestions, visited folders (`HomeRepository.kt`). Long press (or the TV Menu key) opens a menu with Play / Favourite / Clear resume. Pull to refresh on phone. ✅ / ✅ / ✅ | The same, plus Spotlight, a chooser for which shelves show and in what order (`AccountPage.xaml`), and "N min left" on Continue watching (`HomePage.xaml.cs` l.123) | No "min left" text on cards (P2). No Spotlight hero (P2). No shelf chooser (P2, a Plus feature). Settings gear scrolls away (P0, see §1). |
| **Movies / Series / Live landing** | Browse categories; jump to a category; sort | Category rows; a Categories picker (phone: edge swipe or button); a Refresh icon on phone/tablet; category sort inside the picker. ✅ / ✅ / ✅ | Sort lives in the top bar | No in-type search box on the landing page, only the global Search (P2). No "Surprise me" (P2 delight). |
| **Opened category grid (See all)** | Scan many titles; filter; sort | Paged grid, filter field, sort menu (on TV it's in the top bar), "N of M" count (`BrowseScreen.kt`). ✅ / ✅ / ✅ | Same | "Clear filter" and "N of M" are hard-coded English (P1, i18n). |
| **Search** | Find a title by name across all types | Global search over saved titles, with All/Movies/Series/Live chips and a See all per type (`SearchScreen.kt`). Phone: bottom-bar tab. TV: top bar. ✅ / ✅ / ✅ | Search box in the top bar | No recent searches (P2). Voice search is declined. |
| **Movie / Live detail** | Decide, then Play or Resume; favourite; trailer | Hero, facts, Resume ("· x left"), Start over, Favourite, Trailer (`ItemDetailScreen.kt`). Phone: **no back arrow or bar**. ◐ / ◐ / ✅ | Info lives in a player side panel, plus "More from this folder" | No back arrow (P0, §1). Labels "Play", "Resume", "View episodes", "Add to/Remove from favourites" are **hard-coded English** (l.119-140) (P1). No "More from this category" row (P2). No mark watched/unwatched (P1). For a live channel, the detail page is an extra step before watching (P2, needs an owner decision). |
| **Series detail** | Continue the next episode; pick a season; see what I watched | Continue / Next / Start over, Favourite, season chips, episode rows with ✓ and progress (`SeriesDetailScreen.kt`). The long-press menu **only opens when the episode has a resume point** (l.140) and only offers Clear resume. ◐ / ✅ / ✅ | Season shows "x/y watched" (`MainWindow.xaml.cs` l.2485) | No back arrow on phone (P0). No mark watched/unwatched for an episode or a season (P1; `decisions.md` notes it as absent). No "x/y watched" per season (P2). |
| **Player: full screen** | Play/pause; seek; change audio/subtitles; next episode; leave | Play/pause, ±10 s with hold to accelerate, scrubber, double-tap seek, Start over, Audio/Subtitle menus, Next, Sleep timer, retry/reconnect states (`PlayerOverlay.kt`) | Adds previous/next episode, a playlist panel (channels, "More from this folder"), favourite, ⓘ About this title, mute/volume, Shuffle/Autoplay toggles, mini player | See the P0/P1 list. **Every kind of seek is disabled when the length is unknown.** No previous episode, episode list, live channel zapping, favourite, info, aspect ratio or PiP button. |
| **Player: mini / PiP** | Keep watching while browsing | Phone/tablet: PiP opens **only** when you leave the app (Home button / auto-enter, `PipPolicy.kt`). There is no in-app mini player, and Back stops playback. TV: no PiP. | In-app mini player, plus a setting for what minimize does | No PiP button in the overlay, so most people never find it (P1). No "Back → mini player" choice (P2). |
| **Notification / lock screen** | Pause, skip, stop without opening the app | Previous, Play/Pause, Next, −10/+10, Stop, timer +15/Off (`PlaybackService.kt` l.965-989) ✅ | n/a | Artwork is the app icon, not the poster (P2). Button labels are hard-coded English (P1, i18n). |
| **Sleep timer** | Fall asleep safely | Presets, custom 5-240 min, end of item, warning dialog, +15 from the notification ✅ | Same | — |
| **Settings** | Theme, language, playback defaults, account, troubleshooting | Theme, Language, Episodes/Movies play-next, preferred Audio/Subtitles, Refresh library, Subscription, Switch account, Diagnostics, Network diagnostics, Sign out (`SettingsScreen.kt`) | Tabs (Library / Player / Account), refresh policy, Spotlight, shelf chooser, clear artwork cache, playback attempts | A "Player" heading with nothing under it, directly followed by "Appearance" (l.122-123). Rows **cycle on tap** (Off→Next→Shuffle), so you can't see the options. **Movies play-next does nothing**: movies start with no playlist (`MainActivity.playItem` l.182-190; `PlayerActivity.kt` l.376 builds the playlist from episode extras only). Missing: text size, Wi-Fi-only sync / metered warning (a bible promise), refresh policy, clear cache, clear history, About/version/licences/privacy. |
| **Login / accounts** | Add a source without typing twice; switch between sources | One server/user/password form; `CredentialsStore` holds **one** credential set | Single account | Bible "free always" promises with no flow yet: ≥2 saved sources, setup QR, phone-to-TV pairing (P1, release scope). Switching means retyping. |
| **Subscription / Diagnostics / Network diagnostics** | Check expiry; send a report | Present, with export/copy ✅ | Subscription facts on Account | No back arrow on phone (P0, §1). |
| **Parental** | Hide adult categories; PIN | Schema only (`PROFILE_ADULT`/Kids in `Entities.kt`); no UI | — | Bible "free always" promise: parental PIN and adult-category hiding (P1, release scope). |
| **TV launcher integration** | Continue watching from the TV home screen | None (no Watch Next / TvProvider code) | n/a | Bible "free always" promise (P1). |

## 3. Ranked gaps with proposed controls

Reuse what already exists: `OverlayButton`, `TrackMenu`/`DropdownMenu`, `DetailTextButton`, `ItemContextMenu`, `ConfirmDialog`, `tvFocusFrame`/`tvClickable`, `HeaderIconButton`, `NextPlaybackSelector`, `PlaybackHandoff.QueueItem`.

### P0

| # | Gap | Phone / tablet | TV (D-pad) | Evidence |
|---|---|---|---|---|
| 1 | **No seek when the length is unknown.** `seekTo` returns when `durationMs == 0` (`PlayerActivity.kt` l.829), and `seekBy` goes through it. `controls()` hides the scrubber, ±10 s, Start over and double-tap unless the duration is known (`PlayerOverlayLogic.kt` l.45). | Split the two cases. **Relative seek** (±10 s, hold-to-accelerate, double-tap) only needs `isCurrentMediaItemSeekable && !live`. Show an **elapsed-only bar** ("12:34 · length unknown") with a **Jump to time** button that opens an mm:ss dialog. When the length becomes known, swap in the normal slider. | Same buttons in the same centre row; Left/Right while hidden already call `seekOnKey`, so they work once the guard is lifted. Jump to time goes in the bottom row, using the `SleepTimerMenu` −/+ dialog pattern (±1 min / ±10 min). | `PlayerActivity.kt` l.826-842; `PlayerOverlay.kt` l.136, 335-352, 392-402 |
| 2 | **Settings unreachable on phone** except a gear that scrolls away on Home | Recommended: bottom bar = **My Tvivo · Movies · Series · Live · Settings** (icon + label each). Search moves to a pinned **top app bar** (title + Search icon) on every top-level tab. Alternative: keep 5 tabs and pin a gear in that top bar. | Already done (top-bar gear) | `AppShell.kt` l.116; `HomeScreen.kt` l.250 |
| 3 | **No on-screen way back / bars disappear** on Detail, Series, Subscription, Diagnostics, Network diagnostics | Add one shared `ScreenTopBar(title, onBack)`: a 48 dp arrow that flips for RTL, plus the title. Also render Detail/Series **inside `AppShell`** with `selected = detailOriginTab`, so the bottom bar stays. | Leave off by default (Back key). Optional: a focusable "‹ Back" as the first item on Diagnostics screens. | `MainActivity.kt` l.634-770 |
| 4 | **Live TV has no zapping.** Live starts with an empty playlist, so there is no CH+/CH−, channel list or last channel. Every channel change takes Back → grid → detail → Play. | Overlay: a **Channels** button opens a bottom sheet with the current category's channels (reuse the `ContentGrid` item row). Swipe up/down on the video = next/previous channel. "↺ Last channel" button. | `KEYCODE_CHANNEL_UP/DOWN` always zap. While controls are hidden on live, Up/Down zap and **Left opens the channel list** (Left/Right currently call a no-op seek on live). Number keys type a channel position (P2). | `MainActivity.kt` l.182-190; `PlayerActivity.kt` l.376, 854-879; WinUI `PlayAdjacentEpisode`/`PlayRelatedChannel` `MainWindow.xaml.cs` l.2223 |

### P1

| # | Gap | Phone / tablet | TV | Evidence |
|---|---|---|---|---|
| 5 | Movies "Play next: Next/Shuffle" setting is **inert**: no movie playlist is ever passed | Pass the opened category (or shelf) as a `QueueItem` playlist from `playItem`, like episodes. Until then, hide the row. | Same | `PlayerActivity.kt` l.376-387; `SettingsScreen.kt` l.145 |
| 6 | No **previous episode** in the overlay (the notification has one); no **episode list** in the player | Bottom row: ⏮ Previous before Next, plus an **Episodes** button opening a season sheet (reuse `SeriesEpisodeList` rows) | Same buttons. `KEYCODE_MEDIA_PREVIOUS` → previous. | `PlayerOverlay.kt` l.416; `PlaybackService.kt` l.969 |
| 7 | No **PiP button**; PiP only fires on Home/auto-enter | Top-right overlay button "Picture-in-picture" (only when `PipPolicy.entry != None`) | n/a (no PiP on TV) | `PipPolicy.kt` |
| 8 | **Aspect / zoom** missing (IPTV channels are often 4:3 or letterboxed) | Bottom-row button cycling Fit · Fill · Zoom (`PlayerView.resizeMode`); pinch to zoom on phone | Same button, D-pad reachable | `PlayerActivity.kt` (PlayerView) |
| 9 | No **favourite** or **title info** in the player (WinUI has both) | ♡ in the top row beside the title; ⓘ opens plot/year/quality (reuse `DetailHero` text) | Same, on the top focus row | WinUI `MainWindow.xaml` "Toggle favorite", "Show title description" |
| 10 | No **mark watched / unwatched** (episode, season, movie) | Long-press menu on every card and episode: Mark watched / Mark unwatched. Season chip menu: "Mark season watched". | Menu key / long-OK opens the same menu (it currently opens only when a resume point exists) | `SeriesDetailScreen.kt` l.140; `ItemContextMenu.kt`; `decisions.md` A4 |
| 11 | **Hard-coded English** on the detail page, Home refresh line, filter, and notification buttons (Arabic users see English) | Move to `strings.xml` / `values-ar` | Same | `ItemDetailScreen.kt` l.119-140; `HomeScreen.kt` l.260; `BrowseScreen.kt` l.577, 825; `PlaybackService.kt` l.970-986 |
| 12 | Settings structure: empty "Player" heading; tap-to-cycle rows hide the choices | Group into **Library / Player / Appearance / Account / Help**. Each choice row opens a radio dialog (reuse `ConfirmDialog` styling). | Same; the dialog's first option takes focus | `SettingsScreen.kt` l.120-156 |
| 13 | Missing settings: **text size** (the accessibility-for-all goal), **Wi-Fi-only sync + metered warning** (bible), refresh policy (WinUI: on open / 1 day / 7 days / manual), clear artwork cache, clear watch history, **About** (version, licences, privacy; Play needs these) | New rows in the groups above | Same | `PRODUCT-BIBLE.md` §Operating Context; WinUI `AccountPage.xaml` |
| 14 | **Bible "free always" promises with no flow yet**: ≥2 saved sources with a quick switch, setup QR, phone-to-TV pairing, parental PIN + adult hiding, TV Watch Next row | Settings → Account → "Sources" list; Login → "Scan setup QR" | Login → "Pair with phone" (4-digit code); Watch Next channel published from Continue watching | `CredentialsStore.kt`; `PRODUCT-BIBLE.md` §Business Model |
| 15 | Long-press menu is thin (Play / Favourite / Clear resume / Close) | Add: **Open details**, **Mark watched**, **More from this category**, **Remove from Continue watching** (new wording for Clear resume) | Same, via the Menu key | `ItemContextMenu.kt` |

### P2

| # | Gap | Phone / tablet | TV |
|---|---|---|---|
| 16 | Bottom-bar tabs are text only | Icon + label on each tab (Home, Movie, Series, Live, Settings) | — |
| 17 | Continue-watching cards show a bar but no "23 min left" (WinUI does) | Caption under the title | Same |
| 18 | No "More from this category" row on detail | Shelf under the hero (reuse `ShelfRow`) | Same; Down from the actions |
| 19 | Live channel opens a detail page first | Owner decision: OK plays live directly, long press = details | Same |
| 20 | Notification/lock-screen artwork is the app icon | Use the poster/channel logo via `setArtworkUri` | n/a |
| 21 | No recent searches | Chips under the search field | Focusable chip row |
| 22 | No Spotlight hero / shelf chooser on For You (WinUI has both; shelf chooser = Plus home customization) | Settings → For You | Same |
| 23 | Pull to refresh only on Home | Add to the landing pages | n/a |

## 4. Delight ideas, cheap ones first

| Idea | Need or nice | Phone / tablet | TV | Reuse / cost |
|---|---|---|---|---|
| Relative seek and double-tap even without a known length | **Need** (= P0 #1) | Double-tap left/right thirds | Left/Right | Remove the guard · S |
| Channel up/down and last channel | **Need** (= P0 #4) | Vertical swipe on the video | CH± keys, Up/Down while hidden | Queue from the category · M |
| Mark/unmark favourite everywhere (card menu, detail, player) | **Need** (player is missing) | ♡ in the overlay | Top focus row | `toggleFavourite` · S |
| Continue-where-you-left cards with "x min left" | Need (WinUI parity) | Card caption | Same | `DetailPlaybackPolicy.remainingLabel` · S |
| Long-press quick actions (details, watched, category) | Need | Long press | Menu key | `ItemContextMenu` · S |
| Aspect ratio Fit/Fill/Zoom | Need for IPTV | Button + pinch | Button | `resizeMode` · S |
| Playback speed 0.75-2× (VOD only) | Nice | Bottom-row menu | Same | `setPlaybackSpeed` in `TrackMenu` style · S |
| Lock controls (kids, pocket) | Nice | 🔒 button; hold to unlock | n/a | Overlay state flag · S |
| Brightness (left edge) / volume (right edge) swipes | Nice | Vertical drag | n/a (remote) | Window brightness + AudioManager · S-M |
| Swipe down on the video → PiP | Nice | Gesture | n/a | `PipPolicy` · S |
| "Surprise me" random pick in a category / Movies landing | Nice (fun) | Header icon 🎲 | Header action | `NextPlaybackSelector` shuffle · S |
| Recently added / NEW badges | Exists ✅ | — | — | — |
| Audio/subtitle pickers + saved preferences | Exists ✅ | — | — | — |
| Sleep timer | Exists ✅ | — | — | — |
| Copy title / share info (title and year only, **never the stream URL**: it carries the credentials) | Nice | Detail overflow → Share | n/a | Share sheet · S |
| Subtitle size/style | Nice (Plus "advanced subtitles") | Settings → Player | Same | Media3 `CaptionStyle` · M |
| Remember the last tab and last channel on cold start | Nice | — | — | `savedTab` exists · S |
| Cast hint | **Declined** (casting out of scope) | — | — | Do not build |
| Voice search | **Declined** | — | — | Do not build |

## 5. Suggested order

1. **P0 #1 (seek with unknown length) and #2/#3 (navigation chrome).** Small and settled, so luna can implement them.
2. **P0 #4 + P1 #5/#6.** These all need "the player gets a real queue": live channels, movies and episodes go through one `QueueItem` path. This needs a plan (sol), because it touches the playback-service handoff.
3. **P1 #7-#12.** Player buttons, watched marks, i18n strings and the Settings regroup. Settled wiring.
4. **P1 #13-#14.** Release-scope promises (sources, QR/pairing, parental, Watch Next, About). Product and planning first.
5. P2 and delight items, interleaved as filler.

None of this has been checked on a device. Every proposed control needs the usual phone, tablet and TV focus/visual pass before it can be called done.
