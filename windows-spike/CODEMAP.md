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
| [`AddFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L672) | CALL | — ⚠️ | [`AddFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L672) | — | — | — |
| [`CatalogCategoryMatchCount`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L8) | CALL | — ⚠️ | [`CatalogCategoryMatchCount`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L8) | — | — | — |
| [`CatalogCategoryShelfDescriptor`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L9) | CALL | — ⚠️ | [`CatalogCategoryShelfDescriptor`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L9) | — | — | — |
| [`CatalogPage`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L7) | CALL | — ⚠️ | [`CatalogPage`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L7) | — | — | — |
| [`Dispose`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L941) | CALL | — ⚠️ | [`Dispose`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L941) | — | — | — |
| [`For`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L14) | CALL | — ⚠️ | [`For`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L14) | — | — | — |
| [`GetAllChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L437) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetAllChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L437) | — | — | — |
| [`GetCategoryMatchCounts`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L421) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetCategoryMatchCounts`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L421) | `GetCategoryMatchCounts` | _direct SQL, see §7_ | `items` |
| [`GetCategoryShelfDescriptors`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L353) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetCategoryShelfDescriptors`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L353) | `GetCategoryShelfDescriptors` | — | `categories`<br>`items` |
| [`GetChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L385) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L385) | `GetChannels` | _direct SQL, see §7_ | `items` |
| [`GetChannelsGroupedByCategory`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L456) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetChannelsGroupedByCategory`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L456) | `GetChannelsGroupedByCategory` | _direct SQL, see §7_ | `items` |
| [`GetContinueWatching`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L591) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetContinueWatching`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L591) | — | — | — |
| [`GetEpisodeProgressForSeries`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L708) | CALL | `MainWindow.xaml.cs` | [`GetEpisodeProgressForSeries`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L708) | `GetEpisodeProgressForSeries` | _direct SQL, see §7_ | `media_progress` |
| [`GetEpisodeResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L601) | CALL | `MainWindow.xaml.cs` | [`GetEpisodeResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L601) | `GetEpisodeResumePosition` | _direct SQL, see §7_ | `media_progress` |
| [`GetFavorites`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L577) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetFavorites`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L577) | `GetFavorites` | _direct SQL, see §7_ | `favorites` |
| [`GetGroups`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L344) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetGroups`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L344) | `GetGroups` | _direct SQL, see §7_ | `categories` |
| [`GetLastRefreshSuccess`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L333) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetLastRefreshSuccess`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L333) | `GetLastRefreshSuccess` | _direct SQL, see §7_ | `catalog_refresh_state` |
| [`GetMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L156) | CALL | `MainWindow.xaml.cs`<br>`Pages/CatalogLandingPage.xaml.cs` | [`GetMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L156) | `GetMetadata` | _direct SQL, see §7_ | `items` |
| [`GetPlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L727) | CALL | `MainWindow.xaml.cs`<br>`Pages/CatalogLandingPage.xaml.cs` | [`GetPlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L727) | `GetPlaybackProgress` | _direct SQL, see §7_ | `items`<br>`media_progress`<br>`series_playback` |
| [`GetRecentlyAdded`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L539) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetRecentlyAdded`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L539) | `GetRecentlyAdded` | _direct SQL, see §7_ | `items` |
| [`GetRecentlyPlayed`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L580) | CALL | `Pages/CatalogLandingPage.xaml.cs` | [`GetRecentlyPlayed`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L580) | — | — | — |
| [`GetResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L611) | CALL | `MainWindow.xaml.cs` | [`GetResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L611) | `GetResumePosition` | _direct SQL, see §7_ | `items` |
| [`GetSuggestions`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L622) | CALL | — ⚠️ | [`GetSuggestions`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L622) | `GetSuggestions` | _direct SQL, see §7_ | `items` |
| [`IsFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L676) | CALL | `MainWindow.xaml.cs` | [`IsFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L676) | `IsFavorite` | _direct SQL, see §7_ | `favorites` |
| [`MarkEpisodeUnwatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L798) | CALL | — ⚠️ | [`MarkEpisodeUnwatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L798) | `MarkEpisodeUnwatched` | `items`<br>`media_progress`<br>`series_playback` | `media_progress`<br>`series_playback` |
| [`MarkEpisodeWatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L797) | CALL | — ⚠️ | [`MarkEpisodeWatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L797) | — | — | — |
| [`PlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L11) | CALL | — ⚠️ | [`PlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L11) | — | — | — |
| [`PlaybackProgressPresentation`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L12) | CALL | — ⚠️ | [`PlaybackProgressPresentation`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L12) | — | — | — |
| [`RecordVisit`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L820) | CALL | `MainWindow.xaml.cs` | [`RecordVisit`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L820) | `RecordVisit` | `items` | — |
| [`RemoveFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L674) | CALL | — ⚠️ | [`RemoveFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L674) | — | — | — |
| [`ReplaceSnapshot`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L301) | CALL | — ⚠️ | [`ReplaceSnapshot`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L301) | `ReplaceSnapshot` | `catalog_refresh_state`<br>`categories`<br>`incoming_item_ids`<br>`items` | `categories`<br>`incoming_item_ids`<br>`items` |
| [`SaveMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L140) | CALL | `MainWindow.xaml.cs` | [`SaveMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L140) | `SaveMetadata` | `items` | — |
| [`SaveProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L775) | CALL | `MainWindow.xaml.cs` | [`SaveProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L775) | `SaveProgress` | `items`<br>`media_progress`<br>`series_playback` | — |
| [`SelectResumeEpisode`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L755) | CALL | `MainWindow.xaml.cs` | [`SelectResumeEpisode`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L755) | — | — | — |
| [`SetFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L655) | CALL | — ⚠️ | [`SetFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L655) | `SetFavorite` | `favorites`<br>`items` | — |
| [`ToggleFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L687) | CALL | `MainWindow.xaml.cs` | [`ToggleFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L687) | — | — | — |
| [`UpdateResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L695) | CALL | — ⚠️ | [`UpdateResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L695) | `UpdateResumePosition` | `items` | — |
| [`UpdateSeriesPlayback`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L843) | CALL | — ⚠️ | [`UpdateSeriesPlayback`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L843) | — | — | — |

## 2. Frontend calls a route the controllers do not define

Broken at runtime, or a route renamed on one side only.

_None._

## 3. Route defined but never called from the frontend

Dead endpoint, or called by a host page / another consumer outside this repo.

- `AddFavorite` (CALL)
- `CatalogCategoryMatchCount` (CALL)
- `CatalogCategoryShelfDescriptor` (CALL)
- `CatalogPage` (CALL)
- `Dispose` (CALL)
- `For` (CALL)
- `GetSuggestions` (CALL)
- `MarkEpisodeUnwatched` (CALL)
- `MarkEpisodeWatched` (CALL)
- `PlaybackProgress` (CALL)
- `PlaybackProgressPresentation` (CALL)
- `RemoveFavorite` (CALL)
- `ReplaceSnapshot` (CALL)
- `SetFavorite` (CALL)
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

- [`GetFavorites`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L577) — 3 inline statement(s)
- [`GetPlaybackProgress`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L727) — 3 inline statement(s)
- [`GetChannels`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L385) — 2 inline statement(s)
- [`GetChannelsGroupedByCategory`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L456) — 2 inline statement(s)
- [`GetRecentlyAdded`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L539) — 2 inline statement(s)
- [`ReplaceSnapshot`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L301) — 2 inline statement(s)
- [`GetCategoryMatchCounts`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L421) — 1 inline statement(s)
- [`GetEpisodeProgressForSeries`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L708) — 1 inline statement(s)
- [`GetEpisodeResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L601) — 1 inline statement(s)
- [`GetGroups`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L344) — 1 inline statement(s)
- [`GetLastRefreshSuccess`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L333) — 1 inline statement(s)
- [`GetMetadata`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L156) — 1 inline statement(s)
- [`GetResumePosition`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L611) — 1 inline statement(s)
- [`GetSuggestions`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L622) — 1 inline statement(s)
- [`IsFavorite`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L676) — 1 inline statement(s)
- [`MarkEpisodeUnwatched`](src/Tvivo.Infrastructure/SqliteCatalogRepository.cs#L798) — 1 inline statement(s)

## 8. Table → which procs touch it

Reverse index over each backend method's own inline SQL (there is no separate proc layer here).

| Table | Written by | Read by |
|---|---|---|
| `catalog_refresh_state` _(external)_ | `ReplaceSnapshot` | `GetLastRefreshSuccess` |
| `categories` | `ReplaceSnapshot` | `GetCategoryShelfDescriptors`<br>`GetGroups`<br>`ReplaceSnapshot` |
| `favorites` | `SetFavorite` | `GetFavorites`<br>`IsFavorite` |
| `incoming_item_ids` | `ReplaceSnapshot` | `ReplaceSnapshot` |
| `items` | `MarkEpisodeUnwatched`<br>`RecordVisit`<br>`ReplaceSnapshot`<br>`SaveMetadata`<br>`SaveProgress`<br>`SetFavorite`<br>`UpdateResumePosition` | `GetCategoryMatchCounts`<br>`GetCategoryShelfDescriptors`<br>`GetChannels`<br>`GetChannelsGroupedByCategory`<br>`GetMetadata`<br>`GetPlaybackProgress`<br>`GetRecentlyAdded`<br>`GetResumePosition`<br>`GetSuggestions`<br>`ReplaceSnapshot` |
| `media_progress` _(external)_ | `MarkEpisodeUnwatched`<br>`SaveProgress` | `GetEpisodeProgressForSeries`<br>`GetEpisodeResumePosition`<br>`GetPlaybackProgress`<br>`MarkEpisodeUnwatched` |
| `series_playback` | `MarkEpisodeUnwatched`<br>`SaveProgress` | `GetPlaybackProgress`<br>`MarkEpisodeUnwatched` |

---

Indexed 38 routes · 38 backend methods · 22 procs · 7 tables · 17 frontend files · 0 SQL files.
