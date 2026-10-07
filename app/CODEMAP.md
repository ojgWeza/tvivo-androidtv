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

| Route (`Repository.<method>()`) | Verb | Called from | Backend method | Procs | Writes | Reads |
|---|---|---|---|---|---|---|
| [`authenticate`](src/main/java/com/dev/Tvivo/auth/AuthRepository.kt#L27) | CALL | `settings/AccountViewModel.kt` | — | — | — | — |
| [`byId`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L69) | CALL ×3 | `detail/ItemDetailViewModel.kt`<br>`series/SeriesDetailViewModel.kt` | [`liveDao().byId`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L96)<br>[`seriesDao().byId`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L97)<br>[`vodDao().byId`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L118) | `liveDao().byId`<br>`seriesDao().byId`<br>`vodDao().byId` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`byIds`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L66) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L118)<br>[`seriesDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L119)<br>[`vodDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L130) | `liveDao().byIds`<br>`seriesDao().byIds`<br>`vodDao().byIds` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`clearResume`](src/main/java/com/dev/Tvivo/data/repository/PlaybackStateRepository.kt#L65) | CALL | `detail/ItemDetailViewModel.kt` | [`resumeDao().remove`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L154) | `resumeDao().remove` | `resume_positions` | _direct SQL, see §7_ |
| [`continueWatching`](src/main/java/com/dev/Tvivo/data/repository/PlaybackStateRepository.kt#L62) | CALL | `browse/BrowseViewModel.kt` | [`resumeDao().continueWatching`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L129) | `resumeDao().continueWatching` | _direct SQL, see §7_ | `resume_positions` |
| [`countInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L53) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().countInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L69)<br>[`seriesDao().countInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L70)<br>[`vodDao().countInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L66) | `liveDao().countInCategoryFiltered`<br>`seriesDao().countInCategoryFiltered`<br>`vodDao().countInCategoryFiltered` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`countsByCategory`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L36) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().countsByCategory`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L93)<br>[`seriesDao().countsByCategory`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L94)<br>[`vodDao().countsByCategory`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L112) | `liveDao().countsByCategory`<br>`seriesDao().countsByCategory`<br>`vodDao().countsByCategory` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`favourites`](src/main/java/com/dev/Tvivo/data/repository/PlaybackStateRepository.kt#L71) | CALL | `browse/BrowseViewModel.kt` | [`favouriteDao().observe`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L172) | `favouriteDao().observe` | _direct SQL, see §7_ | `favourites` |
| [`fetchPlot`](src/main/java/com/dev/Tvivo/data/repository/VodRepository.kt#L81) | CALL | `detail/ItemDetailViewModel.kt` | [`vodDao().updatePlot`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L141) | `vodDao().updatePlot` | `vod_streams` | _direct SQL, see §7_ |
| [`isFavourite`](src/main/java/com/dev/Tvivo/data/repository/PlaybackStateRepository.kt#L68) | CALL | `detail/ItemDetailViewModel.kt` | [`favouriteDao().isFavourite`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L186) | `favouriteDao().isFavourite` | _direct SQL, see §7_ | `favourites` |
| [`isStale`](src/main/java/com/dev/Tvivo/data/repository/CachedFetch.kt#L54) | CALL | — ⚠️ | — | — | — | — |
| [`observeCategories`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L33) | CALL ×3 | `browse/BrowseViewModel.kt` | [`categoryDao().observe`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L25) | `categoryDao().observe` | _direct SQL, see §7_ | `categories` |
| [`observeEpisodes`](src/main/java/com/dev/Tvivo/data/repository/SeriesRepository.kt#L81) | CALL | `series/SeriesDetailViewModel.kt` | [`seriesDao().observeEpisodes`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L150) | `seriesDao().observeEpisodes` | _direct SQL, see §7_ | `series_episodes` |
| [`pagingAll`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L56) | CALL ×3 | — ⚠️ | [`liveDao().pagingAll`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L76)<br>[`seriesDao().pagingAll`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L77)<br>[`vodDao().pagingAll`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L74) | `liveDao().pagingAll`<br>`seriesDao().pagingAll`<br>`vodDao().pagingAll` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`pagingInCategory`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L38) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().pagingInCategory`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L30)<br>[`seriesDao().pagingInCategory`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L31)<br>[`vodDao().pagingInCategory`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L27) | `liveDao().pagingInCategory`<br>`seriesDao().pagingInCategory`<br>`vodDao().pagingInCategory` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`pagingInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L47) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().pagingInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L54)<br>[`seriesDao().pagingInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L55)<br>[`vodDao().pagingInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L51) | `liveDao().pagingInCategoryFiltered`<br>`seriesDao().pagingInCategoryFiltered`<br>`vodDao().pagingInCategoryFiltered` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`pagingSearch`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L58) | CALL ×3 | — ⚠️ | [`liveDao().pagingSearch`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L85)<br>[`seriesDao().pagingSearch`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L86)<br>[`vodDao().pagingSearch`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L83) | `liveDao().pagingSearch`<br>`seriesDao().pagingSearch`<br>`vodDao().pagingSearch` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`recentlyAdded`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L62) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L106)<br>[`seriesDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L107)<br>[`vodDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L100) | `liveDao().recentlyAdded`<br>`seriesDao().recentlyAdded`<br>`vodDao().recentlyAdded` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`refreshCategories`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L71) | CALL ×3 | `browse/BrowseViewModel.kt`<br>`settings/AccountViewModel.kt` | [`categoryDao().replaceAll`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L38) | — | — | — |
| [`refreshCategory`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L93) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().replaceCategory`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L130)<br>[`seriesDao().replaceCategory`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L131)<br>[`vodDao().replaceCategory`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L157) | — | — | — |
| [`refreshSeriesInfo`](src/main/java/com/dev/Tvivo/data/repository/SeriesRepository.kt#L140) | CALL | `series/SeriesDetailViewModel.kt` | [`seriesDao().replaceEpisodes`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L164) | — | — | — |
| [`resumePosition`](src/main/java/com/dev/Tvivo/data/repository/PlaybackStateRepository.kt#L20) | CALL | `detail/ItemDetailViewModel.kt` | [`resumeDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L108) | `resumeDao().get` | _direct SQL, see §7_ | `resume_positions` |
| [`savePosition`](src/main/java/com/dev/Tvivo/data/repository/PlaybackStateRepository.kt#L37) | CALL | `player/PlayerActivity.kt` | [`resumeDao().remove`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L154)<br>[`resumeDao().upsert`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L141) | `resumeDao().remove` | `resume_positions` | _direct SQL, see §7_ |
| [`toggleFavourite`](src/main/java/com/dev/Tvivo/data/repository/PlaybackStateRepository.kt#L73) | CALL | `detail/ItemDetailViewModel.kt` | [`favouriteDao().add`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L196)<br>[`favouriteDao().remove`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L209) | `favouriteDao().remove` | `favourites` | _direct SQL, see §7_ |

## 2. Frontend calls a route the controllers do not define

Broken at runtime, or a route renamed on one side only.

- `countFiltered` — called from `browse/BrowseViewModel.kt`
- `pagingFiltered` — called from `browse/BrowseViewModel.kt`

## 3. Route defined but never called from the frontend

Dead endpoint, or called by a host page / another consumer outside this repo.

- `isStale` (CALL)
- `pagingAll` (CALL ×3)
- `pagingSearch` (CALL ×3)

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

- [`catalogSyncDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L91) — 1 inline statement(s)
- [`catalogSyncDao().observe`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L88) — 1 inline statement(s)
- [`categoryDao().deleteAll`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L31) — 1 inline statement(s)
- [`categoryDao().observe`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L25) — 1 inline statement(s)
- [`favouriteDao().isFavourite`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L186) — 1 inline statement(s)
- [`favouriteDao().isFavouriteForProfile`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L190) — 1 inline statement(s)
- [`favouriteDao().observe`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L172) — 1 inline statement(s)
- [`favouriteDao().observeForProfile`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L176) — 1 inline statement(s)
- [`favouriteDao().remove`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L209) — 1 inline statement(s)
- [`favouriteDao().removeForProfile`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L213) — 1 inline statement(s)
- [`liveDao().byId`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L96) — 1 inline statement(s)
- [`liveDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L118) — 1 inline statement(s)
- [`liveDao().countInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L69) — 1 inline statement(s)
- [`liveDao().countsByCategory`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L93) — 1 inline statement(s)
- [`liveDao().deleteCategory`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L124) — 1 inline statement(s)
- [`liveDao().deleteGenerationsUpTo`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L127) — 1 inline statement(s)
- [`liveDao().pagingAll`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L76) — 1 inline statement(s)
- [`liveDao().pagingInCategory`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L30) — 1 inline statement(s)
- [`liveDao().pagingInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L54) — 1 inline statement(s)
- [`liveDao().pagingSearch`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L85) — 1 inline statement(s)
- [`liveDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L106) — 1 inline statement(s)
- [`profileAllowDao().observe`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L67) — 1 inline statement(s)
- [`profileAllowDao().remove`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L80) — 1 inline statement(s)
- [`profileDao().deleteRow`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L51) — 1 inline statement(s)
- [`profileDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L44) — 1 inline statement(s)
- [`profileDao().observe`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L41) — 1 inline statement(s)
- [`profileWriteGuard().ensureAdult`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L24) — 1 inline statement(s)
- [`profileWriteGuard().profileExists`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L27) — 1 inline statement(s)
- [`recentlyPlayedDao().recent`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L155) — 1 inline statement(s)
- [`resumeDao().continueWatching`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L129) — 1 inline statement(s)
- [`resumeDao().continueWatchingForProfile`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L135) — 1 inline statement(s)
- [`resumeDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L108) — 1 inline statement(s)
- [`resumeDao().getForProfile`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L112) — 1 inline statement(s)
- [`resumeDao().remove`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L154) — 1 inline statement(s)
- [`resumeDao().removeForProfile`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L158) — 1 inline statement(s)
- [`seriesDao().byId`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L97) — 1 inline statement(s)
- [`seriesDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L119) — 1 inline statement(s)
- [`seriesDao().countInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L70) — 1 inline statement(s)
- [`seriesDao().countsByCategory`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L94) — 1 inline statement(s)
- [`seriesDao().deleteCategory`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L125) — 1 inline statement(s)
- [`seriesDao().deleteEpisodes`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L156) — 1 inline statement(s)
- [`seriesDao().deleteGenerationsUpTo`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L128) — 1 inline statement(s)
- [`seriesDao().observeEpisodes`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L150) — 1 inline statement(s)
- [`seriesDao().pagingAll`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L77) — 1 inline statement(s)
- [`seriesDao().pagingInCategory`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L31) — 1 inline statement(s)
- [`seriesDao().pagingInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L55) — 1 inline statement(s)
- [`seriesDao().pagingSearch`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L86) — 1 inline statement(s)
- [`seriesDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L107) — 1 inline statement(s)
- [`shelfVisitDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L135) — 1 inline statement(s)
- [`syncMetaDao().deleteForAccount`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L81) — 1 inline statement(s)
- [`syncMetaDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L53) — 1 inline statement(s)
- [`syncMetaDao().oldestStamp`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L62) — 1 inline statement(s)
- [`syncMetaDao().staleCategories`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L71) — 1 inline statement(s)
- [`visitDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L107) — 1 inline statement(s)
- [`visitDao().increment`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L121) — 1 inline statement(s)
- [`visitDao().mostVisited`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L111) — 1 inline statement(s)
- [`visitDao().seed`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L116) — 1 inline statement(s)
- [`vodDao().byId`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L118) — 1 inline statement(s)
- [`vodDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L130) — 1 inline statement(s)
- [`vodDao().countInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L66) — 1 inline statement(s)
- [`vodDao().countsByCategory`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L112) — 1 inline statement(s)
- [`vodDao().deleteCategory`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L147) — 1 inline statement(s)
- [`vodDao().deleteGenerationsUpTo`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L150) — 1 inline statement(s)
- [`vodDao().pagingAll`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L74) — 1 inline statement(s)
- [`vodDao().pagingInCategory`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L27) — 1 inline statement(s)
- [`vodDao().pagingInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L51) — 1 inline statement(s)
- [`vodDao().pagingSearch`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L83) — 1 inline statement(s)
- [`vodDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L100) — 1 inline statement(s)
- [`vodDao().totalCount`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L115) — 1 inline statement(s)
- [`vodDao().updatePlot`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L141) — 1 inline statement(s)
- [`watchedDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L87) — 1 inline statement(s)
- [`watchedDao().unmark`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L100) — 1 inline statement(s)

## 8. Table → which procs touch it

Reverse index over each backend method's own inline SQL (there is no separate proc layer here).

| Table | Written by | Read by |
|---|---|---|
| `catalog_sync` | — | `catalogSyncDao().get`<br>`catalogSyncDao().observe` |
| `categories` | `categoryDao().deleteAll` | `categoryDao().observe` |
| `favourites` | `favouriteDao().remove`<br>`favouriteDao().removeForProfile` | `favouriteDao().isFavourite`<br>`favouriteDao().isFavouriteForProfile`<br>`favouriteDao().observe`<br>`favouriteDao().observeForProfile` |
| `live_streams` | `liveDao().deleteCategory`<br>`liveDao().deleteGenerationsUpTo` | `liveDao().byId`<br>`liveDao().byIds`<br>`liveDao().countInCategoryFiltered`<br>`liveDao().countsByCategory`<br>`liveDao().pagingAll`<br>`liveDao().pagingInCategory`<br>`liveDao().pagingInCategoryFiltered`<br>`liveDao().pagingSearch`<br>`liveDao().recentlyAdded` |
| `profile` _(external)_ | `profileDao().deleteRow` | `profileDao().get`<br>`profileDao().observe`<br>`profileWriteGuard().profileExists` |
| `profile_allow` _(external)_ | `profileAllowDao().remove` | `profileAllowDao().observe` |
| `recently_played` _(external)_ | — | `recentlyPlayedDao().recent` |
| `resume_positions` | `resumeDao().remove`<br>`resumeDao().removeForProfile` | `resumeDao().continueWatching`<br>`resumeDao().continueWatchingForProfile`<br>`resumeDao().get`<br>`resumeDao().getForProfile` |
| `series` | `seriesDao().deleteCategory`<br>`seriesDao().deleteGenerationsUpTo` | `seriesDao().byId`<br>`seriesDao().byIds`<br>`seriesDao().countInCategoryFiltered`<br>`seriesDao().countsByCategory`<br>`seriesDao().pagingAll`<br>`seriesDao().pagingInCategory`<br>`seriesDao().pagingInCategoryFiltered`<br>`seriesDao().pagingSearch`<br>`seriesDao().recentlyAdded` |
| `series_episodes` | `seriesDao().deleteEpisodes` | `seriesDao().observeEpisodes` |
| `shelf_visits` _(external)_ | — | `shelfVisitDao().get` |
| `sync_meta` | `syncMetaDao().deleteForAccount` | `syncMetaDao().get`<br>`syncMetaDao().oldestStamp`<br>`syncMetaDao().staleCategories` |
| `visits` _(external)_ | `visitDao().increment` | `visitDao().get`<br>`visitDao().mostVisited` |
| `vod_streams` | `vodDao().deleteCategory`<br>`vodDao().deleteGenerationsUpTo`<br>`vodDao().updatePlot` | `vodDao().byId`<br>`vodDao().byIds`<br>`vodDao().countInCategoryFiltered`<br>`vodDao().countsByCategory`<br>`vodDao().pagingAll`<br>`vodDao().pagingInCategory`<br>`vodDao().pagingInCategoryFiltered`<br>`vodDao().pagingSearch`<br>`vodDao().recentlyAdded`<br>`vodDao().totalCount` |
| `watched` _(external)_ | `watchedDao().unmark` | `watchedDao().get` |

---

Indexed 24 routes · 101 backend methods · 70 procs · 15 tables · 34 frontend files · 0 SQL files.
