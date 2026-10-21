# CODEMAP — generated address directory

> **GENERATED FILE — do not edit by hand.** Regenerate with `node scripts/codemap.mjs`;
> `node scripts/codemap.mjs check` exits non-zero if it is stale.
>
> This is an **index**, not a source of truth. It records *where things live and what
> calls what* — never what any of it means. For meaning, the oracle is
> `../PROJECT-BIBLE.md`.
>
> Carries no timestamp or commit hash by design: identical source ⇒ identical file, so an
> empty `git diff` after a run is a real staleness check.

**Known limits of the extraction** — it is regex over source, not a compiler:

- Controller → backend is followed **one hop only**. A backend method that reaches the DB
  through another backend method shows no procs of its own.
- Proc → proc calls are not followed; §8 reflects table references inside each proc body.
- Dynamic SQL, and any proc or table named only in a variable, are invisible.
- §6 lists *where* a proc is defined more than once, not whether the bodies differ.

## 1. Stack traces — frontend → route → backend → proc → tables

| Route (`SqliteCatalogRepository.<Method>()`) | Verb | Called from | Backend method | Procs | Writes | Reads |
|---|---|---|---|---|---|---|
| [`AddFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L962) | CALL | — ⚠️ | [`AddFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L962) | — | — | — |
| [`CatalogCategoryMatchCount`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L8) | CALL | — ⚠️ | [`CatalogCategoryMatchCount`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L8) | — | — | — |
| [`CatalogCategoryShelfDescriptor`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L9) | CALL | — ⚠️ | [`CatalogCategoryShelfDescriptor`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L9) | — | — | — |
| [`CatalogPage`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L7) | CALL | — ⚠️ | [`CatalogPage`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L7) | — | — | — |
| [`Dispose`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1247) | CALL | — ⚠️ | [`Dispose`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1247) | — | — | — |
| [`For`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L14) | CALL | — ⚠️ | [`For`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L14) | — | — | — |
| [`GetAllChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L598) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetAllChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L598) | — | — | — |
| [`GetCategoryMatchCounts`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L582) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetCategoryMatchCounts`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L582) | `GetCategoryMatchCounts` | _direct SQL, see §7_ | `items` |
| [`GetCategoryShelfDescriptors`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L494) | CALL | `Pages/AccountPage.xaml.cs`<br>`Pages/CatalogLandingPage.xaml.cs` | [`GetCategoryShelfDescriptors`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L494) | `GetCategoryShelfDescriptors` | — | `categories`<br>`items` |
| [`GetChannel`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L531) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetChannel`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L531) | `GetChannel` | _direct SQL, see §7_ | `items` |
| [`GetChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L527) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L527) | `GetChannels` | _direct SQL, see §7_ | `items` |
| [`GetChannelsGroupedByCategory`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L617) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetChannelsGroupedByCategory`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L617) | `GetChannelsGroupedByCategory` | _direct SQL, see §7_ | `items` |
| [`GetContinueWatching`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L881) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetContinueWatching`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L881) | — | — | — |
| [`GetEpisodeProgressForSeries`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L998) | CALL | `MainWindow.xaml.cs` | [`GetEpisodeProgressForSeries`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L998) | `GetEpisodeProgressForSeries` | _direct SQL, see §7_ | `media_progress` |
| [`GetEpisodeResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L891) | CALL | `MainWindow.xaml.cs` | [`GetEpisodeResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L891) | `GetEpisodeResumePosition` | _direct SQL, see §7_ | `media_progress` |
| [`GetFavorites`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L867) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetFavorites`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L867) | `GetFavorites` | _direct SQL, see §7_ | `favorites` |
| [`GetGroups`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L476) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetGroups`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L476) | `GetGroups` | _direct SQL, see §7_ | `categories` |
| [`GetLastRefreshSuccess`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L418) | CALL | `Pages/AccountPage.xaml.cs`<br>`Pages/CatalogLandingPage.xaml.cs` | [`GetLastRefreshSuccess`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L418) | `GetLastRefreshSuccess` | _direct SQL, see §7_ | `catalog_refresh_state` |
| [`GetMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L239) | CALL | `MainWindow.xaml.cs`<br>`Pages/CatalogLandingPage.xaml.cs` | [`GetMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L239) | `GetMetadata` | _direct SQL, see §7_ | `items` |
| [`GetParserVersion`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L429) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetParserVersion`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L429) | `GetParserVersion` | _direct SQL, see §7_ | `catalog_parser_versions` |
| [`GetPlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1017) | CALL | `MainWindow.xaml.cs`<br>`Pages/CatalogLandingPage.xaml.cs` | [`GetPlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1017) | `GetPlaybackProgress` | _direct SQL, see §7_ | `items`<br>`media_progress`<br>`series_playback` |
| [`GetRecentlyAdded`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L700) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetRecentlyAdded`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L700) | `GetRecentlyAdded` | _direct SQL, see §7_ | `items` |
| [`GetRecentlyPlayed`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L870) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetRecentlyPlayed`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L870) | — | — | — |
| [`GetResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L901) | CALL | `MainWindow.xaml.cs` | [`GetResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L901) | `GetResumePosition` | _direct SQL, see §7_ | `items` |
| [`GetSpotlightCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L786) | CALL | — ⚠️ | [`GetSpotlightCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L786) | `GetSpotlightCandidates` | _direct SQL, see §7_ | `favorites`<br>`items`<br>`media_progress`<br>`series_playback` |
| [`GetSpotlightCategoryCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L742) | CALL | — ⚠️ | [`GetSpotlightCategoryCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L742) | `GetSpotlightCategoryCandidates` | _direct SQL, see §7_ | `items` |
| [`GetSpotlightRecentlyAddedCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L728) | CALL | — ⚠️ | [`GetSpotlightRecentlyAddedCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L728) | `GetSpotlightRecentlyAddedCandidates` | _direct SQL, see §7_ | `items` |
| [`GetSpotlightUnexploredCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L771) | CALL | — ⚠️ | [`GetSpotlightUnexploredCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L771) | — | — | — |
| [`GetSuggestions`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L912) | CALL | — ⚠️ | [`GetSuggestions`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L912) | `GetSuggestions` | _direct SQL, see §7_ | `items` |
| [`HasAnyCatalogRows`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L485) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`HasAnyCatalogRows`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L485) | `HasAnyCatalogRows` | _direct SQL, see §7_ | `categories`<br>`items` |
| [`HasItems`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L437) | CALL | — ⚠️ | [`HasItems`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L437) | `HasItems` | _direct SQL, see §7_ | `items` |
| [`IsFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L966) | CALL | `MainWindow.xaml.cs` | [`IsFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L966) | `IsFavorite` | _direct SQL, see §7_ | `favorites` |
| [`MarkEpisodeUnwatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1088) | CALL | — ⚠️ | [`MarkEpisodeUnwatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1088) | `MarkEpisodeUnwatched` | `items`<br>`media_progress`<br>`series_playback` | `media_progress`<br>`series_playback` |
| [`MarkEpisodeWatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1087) | CALL | — ⚠️ | [`MarkEpisodeWatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1087) | — | — | — |
| [`PlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L11) | CALL | — ⚠️ | [`PlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L11) | — | — | — |
| [`PlaybackProgressPresentation`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L12) | CALL | — ⚠️ | [`PlaybackProgressPresentation`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L12) | — | — | — |
| [`RecordVisit`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1110) | CALL | — ⚠️ | [`RecordVisit`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1110) | — | — | — |
| [`RecordVisits`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1113) | CALL | `MainWindow.xaml.cs` | [`RecordVisits`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1113) | `RecordVisits` | `items` | — |
| [`RefreshSeriesArtwork`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L453) | CALL | — ⚠️ | [`RefreshSeriesArtwork`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L453) | `RefreshSeriesArtwork` | `catalog_parser_versions`<br>`items` | — |
| [`RemoveFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L964) | CALL | — ⚠️ | [`RemoveFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L964) | — | — | — |
| [`ReplaceSnapshot`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L385) | CALL | — ⚠️ | [`ReplaceSnapshot`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L385) | `ReplaceSnapshot` | `catalog_refresh_state`<br>`categories`<br>`incoming_item_ids`<br>`items` | `categories`<br>`incoming_item_ids`<br>`items` |
| [`SaveEpisodeFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L164) | CALL | `MainWindow.xaml.cs` | [`SaveEpisodeFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L164) | `SaveEpisodeFileSize` | `media_progress` | — |
| [`SaveMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L209) | CALL | `MainWindow.xaml.cs` | [`SaveMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L209) | `SaveMetadata` | `items` | — |
| [`SaveMovieFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L227) | CALL | `MainWindow.xaml.cs` | [`SaveMovieFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L227) | `SaveMovieFileSize` | `items` | — |
| [`SaveProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1065) | CALL | `MainWindow.xaml.cs` | [`SaveProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1065) | `SaveProgress` | `items`<br>`media_progress`<br>`series_playback` | — |
| [`SelectResumeEpisode`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1045) | CALL | `MainWindow.xaml.cs` | [`SelectResumeEpisode`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1045) | — | — | — |
| [`SetFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L945) | CALL | — ⚠️ | [`SetFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L945) | `SetFavorite` | `favorites`<br>`items` | — |
| [`SetParserVersion`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L445) | CALL | — ⚠️ | [`SetParserVersion`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L445) | `SetParserVersion` | `catalog_parser_versions` | — |
| [`ToggleFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L977) | CALL | `MainWindow.xaml.cs` | [`ToggleFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L977) | — | — | — |
| [`UpdateResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L985) | CALL | — ⚠️ | [`UpdateResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L985) | `UpdateResumePosition` | `items` | — |
| [`UpdateSeriesPlayback`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1147) | CALL | — ⚠️ | [`UpdateSeriesPlayback`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1147) | — | — | — |

## 2. Frontend calls a route the controllers do not define

Broken at runtime, or a route renamed on one side only.

- `GetEpisodeFileSize` — called from `MainWindow.xaml.cs`

## 3. Route defined but never called from the frontend

Dead endpoint, or called by a host page / another consumer outside this repo.

- `AddFavorite` (CALL)
- `CatalogCategoryMatchCount` (CALL)
- `CatalogCategoryShelfDescriptor` (CALL)
- `CatalogPage` (CALL)
- `Dispose` (CALL)
- `For` (CALL)
- `GetSpotlightCandidates` (CALL)
- `GetSpotlightCategoryCandidates` (CALL)
- `GetSpotlightRecentlyAddedCandidates` (CALL)
- `GetSpotlightUnexploredCandidates` (CALL)
- `GetSuggestions` (CALL)
- `HasItems` (CALL)
- `MarkEpisodeUnwatched` (CALL)
- `MarkEpisodeWatched` (CALL)
- `PlaybackProgress` (CALL)
- `PlaybackProgressPresentation` (CALL)
- `RecordVisit` (CALL)
- `RefreshSeriesArtwork` (CALL)
- `RemoveFavorite` (CALL)
- `ReplaceSnapshot` (CALL)
- `SetFavorite` (CALL)
- `SetParserVersion` (CALL)
- `UpdateResumePosition` (CALL)
- `UpdateSeriesPlayback` (CALL)

## 4. Proc referenced from the backend but defined in no `.sql` in this repo

Fails at runtime unless the proc exists only on the server (or is owned by another module).

_Not applicable — no `sql` layer configured._

## 5. Proc defined but never referenced from the backend

Unused, called by another proc, or called by a consumer outside this repo.

_Not applicable — no `sql` layer configured._

## 6. Proc defined in more than one `.sql` file

Expected when incremental and consolidated deploy scripts sit side by side; listed so a divergent body is visible. Whichever definition is applied last wins.

_Not applicable — no `sql` layer configured._

## 7. Backend methods issuing SQL directly instead of via a proc

No separate proc/`.sql` layer in this repo — each method's own tables are already in sections 1 and 8.

- [`GetFavorites`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L867) — 3 inline statement(s)
- [`GetPlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1017) — 3 inline statement(s)
- [`GetChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L527) — 2 inline statement(s)
- [`GetChannelsGroupedByCategory`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L617) — 2 inline statement(s)
- [`GetRecentlyAdded`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L700) — 2 inline statement(s)
- [`GetSpotlightRecentlyAddedCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L728) — 2 inline statement(s)
- [`ReplaceSnapshot`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L385) — 2 inline statement(s)
- [`GetCategoryMatchCounts`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L582) — 1 inline statement(s)
- [`GetChannel`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L531) — 1 inline statement(s)
- [`GetEpisodeProgressForSeries`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L998) — 1 inline statement(s)
- [`GetEpisodeResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L891) — 1 inline statement(s)
- [`GetGroups`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L476) — 1 inline statement(s)
- [`GetLastRefreshSuccess`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L418) — 1 inline statement(s)
- [`GetMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L239) — 1 inline statement(s)
- [`GetParserVersion`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L429) — 1 inline statement(s)
- [`GetResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L901) — 1 inline statement(s)
- [`GetSpotlightCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L786) — 1 inline statement(s)
- [`GetSpotlightCategoryCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L742) — 1 inline statement(s)
- [`GetSuggestions`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L912) — 1 inline statement(s)
- [`HasAnyCatalogRows`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L485) — 1 inline statement(s)
- [`HasItems`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L437) — 1 inline statement(s)
- [`IsFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L966) — 1 inline statement(s)
- [`MarkEpisodeUnwatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1088) — 1 inline statement(s)

## 8. Table → which procs touch it

Reverse index over each backend method's own inline SQL (there is no separate proc layer here).

| Table | Written by | Read by |
|---|---|---|
| `catalog_parser_versions` | `RefreshSeriesArtwork`<br>`SetParserVersion` | `GetParserVersion` |
| `catalog_refresh_state` _(external)_ | `ReplaceSnapshot` | `GetLastRefreshSuccess` |
| `categories` | `ReplaceSnapshot` | `GetCategoryShelfDescriptors`<br>`GetGroups`<br>`HasAnyCatalogRows`<br>`ReplaceSnapshot` |
| `favorites` | `SetFavorite` | `GetFavorites`<br>`GetSpotlightCandidates`<br>`IsFavorite` |
| `incoming_item_ids` | `ReplaceSnapshot` | `ReplaceSnapshot` |
| `items` | `MarkEpisodeUnwatched`<br>`RecordVisits`<br>`RefreshSeriesArtwork`<br>`ReplaceSnapshot`<br>`SaveMetadata`<br>`SaveMovieFileSize`<br>`SaveProgress`<br>`SetFavorite`<br>`UpdateResumePosition` | `GetCategoryMatchCounts`<br>`GetCategoryShelfDescriptors`<br>`GetChannel`<br>`GetChannels`<br>`GetChannelsGroupedByCategory`<br>`GetMetadata`<br>`GetPlaybackProgress`<br>`GetRecentlyAdded`<br>`GetResumePosition`<br>`GetSpotlightCandidates`<br>`GetSpotlightCategoryCandidates`<br>`GetSpotlightRecentlyAddedCandidates`<br>`GetSuggestions`<br>`HasAnyCatalogRows`<br>`HasItems`<br>`ReplaceSnapshot` |
| `media_progress` _(external)_ | `MarkEpisodeUnwatched`<br>`SaveEpisodeFileSize`<br>`SaveProgress` | `GetEpisodeProgressForSeries`<br>`GetEpisodeResumePosition`<br>`GetPlaybackProgress`<br>`GetSpotlightCandidates`<br>`MarkEpisodeUnwatched` |
| `series_playback` | `MarkEpisodeUnwatched`<br>`SaveProgress` | `GetPlaybackProgress`<br>`GetSpotlightCandidates`<br>`MarkEpisodeUnwatched` |

---

Indexed 51 routes · 51 backend methods · 33 procs · 8 tables · 19 frontend files · 0 SQL files.
