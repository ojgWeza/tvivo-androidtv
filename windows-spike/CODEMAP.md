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
| [`AddFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1075) | CALL | — ⚠️ | [`AddFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1075) | — | — | — |
| [`CatalogCategoryMatchCount`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L8) | CALL | — ⚠️ | [`CatalogCategoryMatchCount`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L8) | — | — | — |
| [`CatalogCategoryShelfDescriptor`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L9) | CALL | — ⚠️ | [`CatalogCategoryShelfDescriptor`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L9) | — | — | — |
| [`CatalogPage`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L7) | CALL | — ⚠️ | [`CatalogPage`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L7) | — | — | — |
| [`Dispose`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1378) | CALL | — ⚠️ | [`Dispose`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1378) | — | — | — |
| [`For`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L14) | CALL | — ⚠️ | [`For`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L14) | — | — | — |
| [`GetAllChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L685) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetAllChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L685) | — | — | — |
| [`GetArtworkFirstPreview`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L601) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetArtworkFirstPreview`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L601) | — | — | — |
| [`GetCategoryChannelIds`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L586) | CALL | `MainWindow.xaml.cs`<br>`PlayerCategoryBrowser.cs` | [`GetCategoryChannelIds`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L586) | `GetCategoryChannelIds` | _direct SQL, see §7_ | `items` |
| [`GetCategoryMatchCounts`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L669) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetCategoryMatchCounts`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L669) | `GetCategoryMatchCounts` | _direct SQL, see §7_ | `items` |
| [`GetCategoryShelfDescriptors`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L549) | CALL | `Pages/AccountPage.xaml.cs`<br>`Pages/CatalogLandingPage.xaml.cs` | [`GetCategoryShelfDescriptors`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L549) | `GetCategoryShelfDescriptors` | — | `categories`<br>`items` |
| [`GetChannel`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L618) | CALL | `MainWindow.xaml.cs`<br>`Pages/CatalogLandingPage.xaml.cs`<br>`PlayerCategoryBrowser.cs` | [`GetChannel`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L618) | `GetChannel` | _direct SQL, see §7_ | `items` |
| [`GetChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L582) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L582) | `GetChannels` | _direct SQL, see §7_ | `items` |
| [`GetChannelsGroupedByCategory`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L704) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetChannelsGroupedByCategory`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L704) | `GetChannelsGroupedByCategory` | _direct SQL, see §7_ | `items` |
| [`GetContinueWatching`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L989) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetContinueWatching`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L989) | `GetContinueWatching` | _direct SQL, see §7_ | `media_progress` |
| [`GetEpisodeEstimatedFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L207) | CALL | `MainWindow.xaml.cs` | [`GetEpisodeEstimatedFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L207) | `GetEpisodeEstimatedFileSize` | _direct SQL, see §7_ | `media_progress` |
| [`GetEpisodeProgressForSeries`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1111) | CALL | `MainWindow.xaml.cs`<br>`PlayerCategoryBrowser.cs` | [`GetEpisodeProgressForSeries`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1111) | `GetEpisodeProgressForSeries` | _direct SQL, see §7_ | `media_progress` |
| [`GetEpisodeResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1004) | CALL | `MainWindow.xaml.cs` | [`GetEpisodeResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1004) | `GetEpisodeResumePosition` | _direct SQL, see §7_ | `media_progress` |
| [`GetFavorites`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L957) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetFavorites`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L957) | `GetFavorites` | _direct SQL, see §7_ | `favorites` |
| [`GetFinishedMovieIds`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L605) | CALL | `MainWindow.xaml.cs` | [`GetFinishedMovieIds`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L605) | `GetFinishedMovieIds` | _direct SQL, see §7_ | `items`<br>`media_progress` |
| [`GetGroups`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L531) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetGroups`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L531) | `GetGroups` | _direct SQL, see §7_ | `categories` |
| [`GetLastRefreshSuccess`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L473) | CALL | `Pages/AccountPage.xaml.cs`<br>`Pages/CatalogLandingPage.xaml.cs` | [`GetLastRefreshSuccess`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L473) | `GetLastRefreshSuccess` | _direct SQL, see §7_ | `catalog_refresh_state` |
| [`GetMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L292) | CALL | `MainWindow.xaml.cs`<br>`Pages/CatalogLandingPage.xaml.cs` | [`GetMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L292) | `GetMetadata` | _direct SQL, see §7_ | `items` |
| [`GetMovieFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L282) | CALL | `MainWindow.xaml.cs` | [`GetMovieFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L282) | `GetMovieFileSize` | _direct SQL, see §7_ | `items` |
| [`GetParserVersion`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L484) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetParserVersion`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L484) | `GetParserVersion` | _direct SQL, see §7_ | `catalog_parser_versions` |
| [`GetPlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1130) | CALL | `MainWindow.xaml.cs`<br>`Pages/CatalogLandingPage.xaml.cs` | [`GetPlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1130) | `GetPlaybackProgress` | _direct SQL, see §7_ | `items`<br>`media_progress`<br>`series_playback` |
| [`GetRecentlyAdded`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L789) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetRecentlyAdded`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L789) | `GetRecentlyAdded` | _direct SQL, see §7_ | `items` |
| [`GetRecentlyPlayed`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L960) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetRecentlyPlayed`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L960) | — | — | — |
| [`GetResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1014) | CALL | `MainWindow.xaml.cs` | [`GetResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1014) | `GetResumePosition` | _direct SQL, see §7_ | `items` |
| [`GetSpotlightCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L875) | CALL | — ⚠️ | [`GetSpotlightCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L875) | `GetSpotlightCandidates` | _direct SQL, see §7_ | `favorites`<br>`items`<br>`media_progress`<br>`series_playback` |
| [`GetSpotlightCategoryCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L831) | CALL | — ⚠️ | [`GetSpotlightCategoryCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L831) | `GetSpotlightCategoryCandidates` | _direct SQL, see §7_ | `items` |
| [`GetSpotlightRecentlyAddedCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L817) | CALL | — ⚠️ | [`GetSpotlightRecentlyAddedCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L817) | `GetSpotlightRecentlyAddedCandidates` | _direct SQL, see §7_ | `items` |
| [`GetSpotlightUnexploredCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L860) | CALL | — ⚠️ | [`GetSpotlightUnexploredCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L860) | — | — | — |
| [`GetSuggestions`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1025) | CALL | — ⚠️ | [`GetSuggestions`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1025) | `GetSuggestions` | _direct SQL, see §7_ | `items` |
| [`GetWatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L971) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetWatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L971) | `GetWatched` | _direct SQL, see §7_ | `media_progress`<br>`series_final_episode` |
| [`HasAnyCatalogRows`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L540) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`HasAnyCatalogRows`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L540) | `HasAnyCatalogRows` | _direct SQL, see §7_ | `categories`<br>`items` |
| [`HasItems`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L492) | CALL | — ⚠️ | [`HasItems`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L492) | `HasItems` | _direct SQL, see §7_ | `items` |
| [`IsFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1079) | CALL | `MainWindow.xaml.cs`<br>`PlayerCategoryBrowser.cs` | [`IsFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1079) | `IsFavorite` | _direct SQL, see §7_ | `favorites` |
| [`MarkEpisodeUnwatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1215) | CALL | — ⚠️ | [`MarkEpisodeUnwatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1215) | `MarkEpisodeUnwatched` | `items`<br>`media_progress`<br>`series_playback` | `media_progress`<br>`series_playback` |
| [`MarkEpisodeWatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1214) | CALL | — ⚠️ | [`MarkEpisodeWatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1214) | — | — | — |
| [`PlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L11) | CALL | — ⚠️ | [`PlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L11) | — | — | — |
| [`PlaybackProgressPresentation`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L12) | CALL | — ⚠️ | [`PlaybackProgressPresentation`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L12) | — | — | — |
| [`RecordSeriesFinalEpisode`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1178) | CALL | `MainWindow.xaml.cs` | [`RecordSeriesFinalEpisode`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1178) | — | — | — |
| [`RecordVisit`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1237) | CALL | — ⚠️ | [`RecordVisit`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1237) | — | — | — |
| [`RecordVisits`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1240) | CALL | `MainWindow.xaml.cs` | [`RecordVisits`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1240) | `RecordVisits` | `items` | — |
| [`RefreshSeriesArtwork`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L508) | CALL | — ⚠️ | [`RefreshSeriesArtwork`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L508) | `RefreshSeriesArtwork` | `catalog_parser_versions`<br>`items` | — |
| [`RemoveFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1077) | CALL | — ⚠️ | [`RemoveFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1077) | — | — | — |
| [`ReplaceSnapshot`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L439) | CALL | — ⚠️ | [`ReplaceSnapshot`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L439) | `ReplaceSnapshot` | `catalog_refresh_state`<br>`categories`<br>`incoming_item_ids`<br>`items` | `categories`<br>`incoming_item_ids`<br>`items` |
| [`SaveEpisodeEstimatedFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L218) | CALL | `MainWindow.xaml.cs` | [`SaveEpisodeEstimatedFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L218) | `SaveEpisodeEstimatedFileSize` | `media_progress` | — |
| [`SaveEpisodeFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L182) | CALL | `MainWindow.xaml.cs` | [`SaveEpisodeFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L182) | `SaveEpisodeFileSize` | `media_progress` | — |
| [`SaveMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L251) | CALL | `MainWindow.xaml.cs` | [`SaveMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L251) | `SaveMetadata` | `items` | — |
| [`SaveMovieFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L270) | CALL | `MainWindow.xaml.cs` | [`SaveMovieFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L270) | `SaveMovieFileSize` | `items` | — |
| [`SaveProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1190) | CALL | `MainWindow.xaml.cs` | [`SaveProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1190) | `SaveProgress` | `items`<br>`media_progress`<br>`series_playback` | — |
| [`SelectResumeEpisode`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1158) | CALL | `MainWindow.xaml.cs` | [`SelectResumeEpisode`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1158) | — | — | — |
| [`SetFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1058) | CALL | — ⚠️ | [`SetFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1058) | `SetFavorite` | `favorites`<br>`items` | — |
| [`SetParserVersion`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L500) | CALL | — ⚠️ | [`SetParserVersion`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L500) | `SetParserVersion` | `catalog_parser_versions` | — |
| [`ToggleFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1090) | CALL | `MainWindow.xaml.cs` | [`ToggleFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1090) | — | — | — |
| [`UpdateResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1098) | CALL | — ⚠️ | [`UpdateResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1098) | `UpdateResumePosition` | `items` | — |
| [`UpdateSeriesPlayback`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1274) | CALL | — ⚠️ | [`UpdateSeriesPlayback`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1274) | — | — | — |

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

- [`GetWatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L971) — 4 inline statement(s)
- [`GetFavorites`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L957) — 3 inline statement(s)
- [`GetPlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1130) — 3 inline statement(s)
- [`GetChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L582) — 2 inline statement(s)
- [`GetChannelsGroupedByCategory`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L704) — 2 inline statement(s)
- [`GetContinueWatching`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L989) — 2 inline statement(s)
- [`GetRecentlyAdded`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L789) — 2 inline statement(s)
- [`GetSpotlightRecentlyAddedCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L817) — 2 inline statement(s)
- [`ReplaceSnapshot`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L439) — 2 inline statement(s)
- [`GetCategoryChannelIds`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L586) — 1 inline statement(s)
- [`GetCategoryMatchCounts`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L669) — 1 inline statement(s)
- [`GetChannel`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L618) — 1 inline statement(s)
- [`GetEpisodeEstimatedFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L207) — 1 inline statement(s)
- [`GetEpisodeProgressForSeries`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1111) — 1 inline statement(s)
- [`GetEpisodeResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1004) — 1 inline statement(s)
- [`GetFinishedMovieIds`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L605) — 1 inline statement(s)
- [`GetGroups`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L531) — 1 inline statement(s)
- [`GetLastRefreshSuccess`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L473) — 1 inline statement(s)
- [`GetMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L292) — 1 inline statement(s)
- [`GetMovieFileSize`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L282) — 1 inline statement(s)
- [`GetParserVersion`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L484) — 1 inline statement(s)
- [`GetResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1014) — 1 inline statement(s)
- [`GetSpotlightCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L875) — 1 inline statement(s)
- [`GetSpotlightCategoryCandidates`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L831) — 1 inline statement(s)
- [`GetSuggestions`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1025) — 1 inline statement(s)
- [`HasAnyCatalogRows`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L540) — 1 inline statement(s)
- [`HasItems`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L492) — 1 inline statement(s)
- [`IsFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1079) — 1 inline statement(s)
- [`MarkEpisodeUnwatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L1215) — 1 inline statement(s)

## 8. Table → which procs touch it

Reverse index over each backend method's own inline SQL (there is no separate proc layer here).

| Table | Written by | Read by |
|---|---|---|
| `catalog_parser_versions` | `RefreshSeriesArtwork`<br>`SetParserVersion` | `GetParserVersion` |
| `catalog_refresh_state` _(external)_ | `ReplaceSnapshot` | `GetLastRefreshSuccess` |
| `categories` | `ReplaceSnapshot` | `GetCategoryShelfDescriptors`<br>`GetGroups`<br>`HasAnyCatalogRows`<br>`ReplaceSnapshot` |
| `favorites` | `SetFavorite` | `GetFavorites`<br>`GetSpotlightCandidates`<br>`IsFavorite` |
| `incoming_item_ids` | `ReplaceSnapshot` | `ReplaceSnapshot` |
| `items` | `MarkEpisodeUnwatched`<br>`RecordVisits`<br>`RefreshSeriesArtwork`<br>`ReplaceSnapshot`<br>`SaveMetadata`<br>`SaveMovieFileSize`<br>`SaveProgress`<br>`SetFavorite`<br>`UpdateResumePosition` | `GetCategoryChannelIds`<br>`GetCategoryMatchCounts`<br>`GetCategoryShelfDescriptors`<br>`GetChannel`<br>`GetChannels`<br>`GetChannelsGroupedByCategory`<br>`GetFinishedMovieIds`<br>`GetMetadata`<br>`GetMovieFileSize`<br>`GetPlaybackProgress`<br>`GetRecentlyAdded`<br>`GetResumePosition`<br>`GetSpotlightCandidates`<br>`GetSpotlightCategoryCandidates`<br>`GetSpotlightRecentlyAddedCandidates`<br>`GetSuggestions`<br>`HasAnyCatalogRows`<br>`HasItems`<br>`ReplaceSnapshot` |
| `media_progress` _(external)_ | `MarkEpisodeUnwatched`<br>`SaveEpisodeEstimatedFileSize`<br>`SaveEpisodeFileSize`<br>`SaveProgress` | `GetContinueWatching`<br>`GetEpisodeEstimatedFileSize`<br>`GetEpisodeProgressForSeries`<br>`GetEpisodeResumePosition`<br>`GetFinishedMovieIds`<br>`GetPlaybackProgress`<br>`GetSpotlightCandidates`<br>`GetWatched`<br>`MarkEpisodeUnwatched` |
| `series_final_episode` _(external)_ | — | `GetWatched` |
| `series_playback` | `MarkEpisodeUnwatched`<br>`SaveProgress` | `GetPlaybackProgress`<br>`GetSpotlightCandidates`<br>`MarkEpisodeUnwatched` |

---

Indexed 59 routes · 59 backend methods · 40 procs · 9 tables · 20 frontend files · 0 SQL files.
