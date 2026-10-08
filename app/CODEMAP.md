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
| [`addedCards`](src/main/java/com/dev/Tvivo/data/repository/HomeRepository.kt#L243) | CALL | — ⚠️ | [`liveDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L173)<br>[`seriesDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L188)<br>[`shelfVisitDao().lastVisitOrNow`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L147)<br>[`vodDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L170) | `liveDao().recentlyAdded`<br>`seriesDao().recentlyAdded`<br>`vodDao().recentlyAdded` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`authenticate`](src/main/java/com/dev/Tvivo/auth/AuthRepository.kt#L27) | CALL | `settings/AccountViewModel.kt` | — | — | — | — |
| [`byId`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L85) | CALL ×3 | `detail/ItemDetailViewModel.kt`<br>`series/SeriesDetailViewModel.kt` | [`liveDao().byId`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L163)<br>[`seriesDao().byId`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L172)<br>[`vodDao().byId`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L191) | `liveDao().byId`<br>`seriesDao().byId`<br>`vodDao().byId` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`byIds`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L82) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L185)<br>[`seriesDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L200)<br>[`vodDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L203) | `liveDao().byIds`<br>`seriesDao().byIds`<br>`vodDao().byIds` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`clearResume`](src/main/java/com/dev/Tvivo/data/repository/PlaybackStateRepository.kt#L85) | CALL | `detail/ItemDetailViewModel.kt`<br>`home/HomeScreen.kt` | [`resumeDao().remove`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L175) | `resumeDao().remove` | `resume_positions` | _direct SQL, see §7_ |
| [`continueCards`](src/main/java/com/dev/Tvivo/data/repository/HomeRepository.kt#L259) | CALL | — ⚠️ | [`resumeDao().continueSnapshot`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L145)<br>[`seriesDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L200)<br>[`seriesDao().episodesByIds`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L203)<br>[`vodDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L203) | `resumeDao().continueSnapshot`<br>`seriesDao().byIds`<br>`seriesDao().episodesByIds`<br>`vodDao().byIds` | _direct SQL, see §7_ | `resume_positions`<br>`series`<br>`series_episodes`<br>`vod_streams` |
| [`continueWatching`](src/main/java/com/dev/Tvivo/data/repository/PlaybackStateRepository.kt#L82) | CALL | `browse/BrowseViewModel.kt` | [`resumeDao().continueWatching`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L139) | `resumeDao().continueWatching` | _direct SQL, see §7_ | `resume_positions` |
| [`countAllFiltered`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L69) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().countAllFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L114)<br>[`seriesDao().countAllFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L115)<br>[`vodDao().countAllFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L112) | `liveDao().countAllFiltered`<br>`seriesDao().countAllFiltered`<br>`vodDao().countAllFiltered` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`countInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L54) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().countInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L69)<br>[`seriesDao().countInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L70)<br>[`vodDao().countInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L66) | `liveDao().countInCategoryFiltered`<br>`seriesDao().countInCategoryFiltered`<br>`vodDao().countInCategoryFiltered` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`countsByCategory`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L37) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().countsByCategory`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L157)<br>[`seriesDao().countsByCategory`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L166)<br>[`vodDao().countsByCategory`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L182) | `liveDao().countsByCategory`<br>`seriesDao().countsByCategory`<br>`vodDao().countsByCategory` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`emptyHintText`](src/main/java/com/dev/Tvivo/data/repository/HomeRepository.kt#L320) | CALL | — ⚠️ | — | — | — | — |
| [`favouriteCards`](src/main/java/com/dev/Tvivo/data/repository/HomeRepository.kt#L297) | CALL | — ⚠️ | [`favouriteDao().preview`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L197) | `favouriteDao().preview` | _direct SQL, see §7_ | `favourites` |
| [`favourites`](src/main/java/com/dev/Tvivo/data/repository/PlaybackStateRepository.kt#L91) | CALL | `browse/BrowseViewModel.kt` | [`favouriteDao().observe`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L193) | `favouriteDao().observe` | _direct SQL, see §7_ | `favourites` |
| [`fetchDetails`](src/main/java/com/dev/Tvivo/data/repository/VodRepository.kt#L97) | CALL | `detail/ItemDetailViewModel.kt` | [`vodDao().updateDetails`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L218) | `vodDao().updateDetails` | `vod_streams` | _direct SQL, see §7_ |
| [`isFavourite`](src/main/java/com/dev/Tvivo/data/repository/PlaybackStateRepository.kt#L88) | CALL | `detail/ItemDetailViewModel.kt`<br>`home/HomeScreen.kt` | [`favouriteDao().isFavourite`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L215) | `favouriteDao().isFavourite` | _direct SQL, see §7_ | `favourites` |
| [`isStale`](src/main/java/com/dev/Tvivo/data/repository/CachedFetch.kt#L54) | CALL | — ⚠️ | — | — | — | — |
| [`landingMatchingCounts`](src/main/java/com/dev/Tvivo/data/repository/HomeRepository.kt#L92) | CALL | — ⚠️ | [`liveDao().matchingCategoryCounts`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L145)<br>[`seriesDao().matchingCategoryCounts`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L149)<br>[`vodDao().matchingCategoryCounts`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L143) | `liveDao().matchingCategoryCounts`<br>`seriesDao().matchingCategoryCounts`<br>`vodDao().matchingCategoryCounts` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`landingMetrics`](src/main/java/com/dev/Tvivo/data/repository/HomeRepository.kt#L77) | CALL | `browse/CatalogLandingScreen.kt` | [`liveDao().categoryLatest`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L149)<br>[`seriesDao().categoryLatest`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L153)<br>[`visitDao().mostVisited`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L115)<br>[`vodDao().categoryLatest`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L147) | `liveDao().categoryLatest`<br>`seriesDao().categoryLatest`<br>`visitDao().mostVisited`<br>`vodDao().categoryLatest` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`visits`<br>`vod_streams` |
| [`landingPinned`](src/main/java/com/dev/Tvivo/data/repository/HomeRepository.kt#L56) | CALL | `browse/CatalogLandingScreen.kt` | [`recentlyPlayedDao().recentSnapshot`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L168) | `recentlyPlayedDao().recentSnapshot` | _direct SQL, see §7_ | `recently_played` |
| [`landingPreview`](src/main/java/com/dev/Tvivo/data/repository/HomeRepository.kt#L104) | CALL | `browse/CatalogLandingScreen.kt` | [`liveDao().allPreviewFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L84)<br>[`liveDao().categoryPreview`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L135)<br>[`liveDao().categoryPreviewFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L140)<br>[`liveDao().landingPreviewSorted`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L105)<br>[`seriesDao().allPreviewFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L85)<br>[`seriesDao().categoryPreview`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L136)<br>[`seriesDao().categoryPreviewFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L144)<br>[`seriesDao().landingPreviewSorted`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L106)<br>[`shelfVisitDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L144)<br>[`vodDao().allPreviewFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L82)<br>[`vodDao().categoryPreview`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L130)<br>[`vodDao().categoryPreviewFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L138)<br>[`vodDao().landingPreviewSorted`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L103) | `liveDao().allPreviewFiltered`<br>`liveDao().categoryPreview`<br>`liveDao().categoryPreviewFiltered`<br>`liveDao().landingPreviewSorted`<br>`seriesDao().allPreviewFiltered`<br>`seriesDao().categoryPreview`<br>`seriesDao().categoryPreviewFiltered`<br>`seriesDao().landingPreviewSorted`<br>`shelfVisitDao().get`<br>`vodDao().allPreviewFiltered`<br>`vodDao().categoryPreview`<br>`vodDao().categoryPreviewFiltered`<br>`vodDao().landingPreviewSorted` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`shelf_visits`<br>`vod_streams` |
| [`landingPreviewSorted`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L63) | CALL ×3 | — ⚠️ | [`liveDao().landingPreviewSorted`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L105)<br>[`seriesDao().landingPreviewSorted`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L106)<br>[`vodDao().landingPreviewSorted`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L103) | `liveDao().landingPreviewSorted`<br>`seriesDao().landingPreviewSorted`<br>`vodDao().landingPreviewSorted` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`load`](src/main/java/com/dev/Tvivo/data/repository/HomeRepository.kt#L159) | CALL | `home/HomeScreen.kt` | [`categoryDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L29)<br>[`liveDao().countSnapshot`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L160)<br>[`seriesDao().categoryCount`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L139)<br>[`seriesDao().categoryPreview`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L136)<br>[`seriesDao().countSnapshot`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L169)<br>[`seriesDao().suggestionCandidates`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L158)<br>[`shelfVisitDao().lastVisitOrNow`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L147)<br>[`visitDao().topThree`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L120)<br>[`vodDao().categoryCount`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L133)<br>[`vodDao().categoryPreview`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L130)<br>[`vodDao().countSnapshot`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L188)<br>[`vodDao().suggestionCandidates`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L153) | `categoryDao().byIds`<br>`liveDao().countSnapshot`<br>`seriesDao().categoryCount`<br>`seriesDao().categoryPreview`<br>`seriesDao().countSnapshot`<br>`seriesDao().suggestionCandidates`<br>`visitDao().topThree`<br>`vodDao().categoryCount`<br>`vodDao().categoryPreview`<br>`vodDao().countSnapshot`<br>`vodDao().suggestionCandidates` | _direct SQL, see §7_ | `categories`<br>`live_streams`<br>`series`<br>`visits`<br>`vod_streams` |
| [`observeCategories`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L34) | CALL ×3 | `browse/BrowseViewModel.kt` | [`categoryDao().observe`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L25) | `categoryDao().observe` | _direct SQL, see §7_ | `categories` |
| [`observeEpisodes`](src/main/java/com/dev/Tvivo/data/repository/SeriesRepository.kt#L97) | CALL | `series/SeriesDetailViewModel.kt` | [`seriesDao().observeEpisodes`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L234) | `seriesDao().observeEpisodes` | _direct SQL, see §7_ | `series_episodes` |
| [`pagingAll`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L57) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().pagingAll`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L76)<br>[`seriesDao().pagingAll`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L77)<br>[`vodDao().pagingAll`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L74) | `liveDao().pagingAll`<br>`seriesDao().pagingAll`<br>`vodDao().pagingAll` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`pagingAllFiltered`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L66) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().pagingAllFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L110)<br>[`seriesDao().pagingAllFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L111)<br>[`vodDao().pagingAllFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L108) | `liveDao().pagingAllFiltered`<br>`seriesDao().pagingAllFiltered`<br>`vodDao().pagingAllFiltered` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`pagingInCategory`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L39) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().pagingInCategory`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L30)<br>[`seriesDao().pagingInCategory`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L31)<br>[`vodDao().pagingInCategory`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L27) | `liveDao().pagingInCategory`<br>`seriesDao().pagingInCategory`<br>`vodDao().pagingInCategory` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`pagingInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L48) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().pagingInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L54)<br>[`seriesDao().pagingInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L55)<br>[`vodDao().pagingInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L51) | `liveDao().pagingInCategoryFiltered`<br>`seriesDao().pagingInCategoryFiltered`<br>`vodDao().pagingInCategoryFiltered` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`pagingSearch`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L74) | CALL ×3 | — ⚠️ | [`liveDao().pagingSearch`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L126)<br>[`seriesDao().pagingSearch`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L127)<br>[`vodDao().pagingSearch`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L121) | `liveDao().pagingSearch`<br>`seriesDao().pagingSearch`<br>`vodDao().pagingSearch` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`pagingSorted`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L59) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().pagingSorted`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L95)<br>[`seriesDao().pagingSorted`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L96)<br>[`vodDao().pagingSorted`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L93) | `liveDao().pagingSorted`<br>`seriesDao().pagingSorted`<br>`vodDao().pagingSorted` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`pinnedCount`](src/main/java/com/dev/Tvivo/data/repository/HomeRepository.kt#L304) | CALL | — ⚠️ | [`favouriteDao().count`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L201)<br>[`liveDao().countSnapshot`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L160)<br>[`recentlyPlayedDao().count`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L172)<br>[`resumeDao().continueCount`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L150)<br>[`seriesDao().countSnapshot`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L169)<br>[`vodDao().countSnapshot`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L188) | `favouriteDao().count`<br>`liveDao().countSnapshot`<br>`recentlyPlayedDao().count`<br>`resumeDao().continueCount`<br>`seriesDao().countSnapshot`<br>`vodDao().countSnapshot` | _direct SQL, see §7_ | `favourites`<br>`live_streams`<br>`recently_played`<br>`resume_positions`<br>`series`<br>`vod_streams` |
| [`recentlyAdded`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L78) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L173)<br>[`seriesDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L188)<br>[`vodDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L170) | `liveDao().recentlyAdded`<br>`seriesDao().recentlyAdded`<br>`vodDao().recentlyAdded` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`refreshCategories`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L87) | CALL ×3 | `browse/BrowseViewModel.kt`<br>`settings/AccountViewModel.kt` | [`categoryDao().replaceAll`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L42) | — | — | — |
| [`refreshCategory`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L109) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().replaceCategory`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L197)<br>[`seriesDao().replaceCategory`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L215)<br>[`vodDao().replaceCategory`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L235) | — | — | — |
| [`refreshSeriesInfo`](src/main/java/com/dev/Tvivo/data/repository/SeriesRepository.kt#L156) | CALL | `series/SeriesDetailViewModel.kt` | [`seriesDao().replaceEpisodes`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L248)<br>[`seriesDao().updateDetails`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L177) | `seriesDao().updateDetails` | `series` | _direct SQL, see §7_ |
| [`resolve`](src/main/java/com/dev/Tvivo/data/repository/HomeRepository.kt#L287) | CALL | — ⚠️ | [`liveDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L185)<br>[`seriesDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L200)<br>[`vodDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L203) | `liveDao().byIds`<br>`seriesDao().byIds`<br>`vodDao().byIds` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`resumePosition`](src/main/java/com/dev/Tvivo/data/repository/PlaybackStateRepository.kt#L26) | CALL | `detail/ItemDetailViewModel.kt`<br>`home/HomeScreen.kt` | [`resumeDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L117) | `resumeDao().get` | _direct SQL, see §7_ | `resume_positions` |
| [`savePosition`](src/main/java/com/dev/Tvivo/data/repository/PlaybackStateRepository.kt#L45) | CALL | `player/PlayerActivity.kt` | [`resumeDao().remove`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L175)<br>[`resumeDao().upsert`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L162)<br>[`watchedDao().mark`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L97)<br>[`watchedDao().unmark`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L104) | `resumeDao().remove`<br>`watchedDao().unmark` | `resume_positions`<br>`watched` | _direct SQL, see §7_ |
| [`search`](src/main/java/com/dev/Tvivo/data/repository/HomeRepository.kt#L139) | CALL | `shell/SearchScreen.kt` | [`liveDao().searchPreview`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L131)<br>[`seriesDao().searchPreview`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L132)<br>[`vodDao().searchPreview`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L126) | `liveDao().searchPreview`<br>`seriesDao().searchPreview`<br>`vodDao().searchPreview` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |
| [`toggleFavourite`](src/main/java/com/dev/Tvivo/data/repository/PlaybackStateRepository.kt#L93) | CALL | `detail/ItemDetailViewModel.kt`<br>`home/HomeScreen.kt` | [`favouriteDao().add`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L225)<br>[`favouriteDao().remove`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L238) | `favouriteDao().remove` | `favourites` | _direct SQL, see §7_ |
| [`totalCount`](src/main/java/com/dev/Tvivo/data/repository/LiveRepository.kt#L72) | CALL ×3 | `browse/BrowseViewModel.kt` | [`liveDao().totalCount`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L117)<br>[`seriesDao().totalCount`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L118)<br>[`vodDao().totalCount`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L185) | `liveDao().totalCount`<br>`seriesDao().totalCount`<br>`vodDao().totalCount` | _direct SQL, see §7_ | `live_streams`<br>`series`<br>`vod_streams` |

## 2. Frontend calls a route the controllers do not define

Broken at runtime, or a route renamed on one side only.

- `countFiltered` — called from `browse/BrowseViewModel.kt`

## 3. Route defined but never called from the frontend

Dead endpoint, or called by a host page / another consumer outside this repo.

- `addedCards` (CALL)
- `continueCards` (CALL)
- `emptyHintText` (CALL)
- `favouriteCards` (CALL)
- `isStale` (CALL)
- `landingMatchingCounts` (CALL)
- `landingPreviewSorted` (CALL ×3)
- `pagingSearch` (CALL ×3)
- `pinnedCount` (CALL)
- `resolve` (CALL)

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

- [`catalogSyncDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L95) — 1 inline statement(s)
- [`catalogSyncDao().observe`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L92) — 1 inline statement(s)
- [`categoryDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L29) — 1 inline statement(s)
- [`categoryDao().deleteAll`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L35) — 1 inline statement(s)
- [`categoryDao().observe`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L25) — 1 inline statement(s)
- [`favouriteDao().count`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L201) — 1 inline statement(s)
- [`favouriteDao().isFavourite`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L215) — 1 inline statement(s)
- [`favouriteDao().isFavouriteForProfile`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L219) — 1 inline statement(s)
- [`favouriteDao().observe`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L193) — 1 inline statement(s)
- [`favouriteDao().observeForProfile`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L205) — 1 inline statement(s)
- [`favouriteDao().preview`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L197) — 1 inline statement(s)
- [`favouriteDao().remove`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L238) — 1 inline statement(s)
- [`favouriteDao().removeForProfile`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L242) — 1 inline statement(s)
- [`liveDao().allPreview`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L79) — 1 inline statement(s)
- [`liveDao().allPreviewFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L84) — 1 inline statement(s)
- [`liveDao().byId`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L163) — 1 inline statement(s)
- [`liveDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L185) — 1 inline statement(s)
- [`liveDao().categoryLatest`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L149) — 1 inline statement(s)
- [`liveDao().categoryPreview`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L135) — 1 inline statement(s)
- [`liveDao().categoryPreviewFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L140) — 1 inline statement(s)
- [`liveDao().countAllFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L114) — 1 inline statement(s)
- [`liveDao().countInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L69) — 1 inline statement(s)
- [`liveDao().countsByCategory`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L157) — 1 inline statement(s)
- [`liveDao().countSnapshot`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L160) — 1 inline statement(s)
- [`liveDao().deleteCategory`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L191) — 1 inline statement(s)
- [`liveDao().deleteGenerationsUpTo`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L194) — 1 inline statement(s)
- [`liveDao().landingPreviewSorted`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L105) — 1 inline statement(s)
- [`liveDao().matchingCategoryCounts`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L145) — 1 inline statement(s)
- [`liveDao().pagingAll`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L76) — 1 inline statement(s)
- [`liveDao().pagingAllFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L110) — 1 inline statement(s)
- [`liveDao().pagingInCategory`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L30) — 1 inline statement(s)
- [`liveDao().pagingInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L54) — 1 inline statement(s)
- [`liveDao().pagingSearch`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L126) — 1 inline statement(s)
- [`liveDao().pagingSorted`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L95) — 1 inline statement(s)
- [`liveDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L173) — 1 inline statement(s)
- [`liveDao().searchPreview`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L131) — 1 inline statement(s)
- [`liveDao().totalCount`](src/main/java/com/dev/Tvivo/data/local/dao/LiveDao.kt#L117) — 1 inline statement(s)
- [`profileAllowDao().observe`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L67) — 1 inline statement(s)
- [`profileAllowDao().remove`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L80) — 1 inline statement(s)
- [`profileDao().deleteRow`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L51) — 1 inline statement(s)
- [`profileDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L44) — 1 inline statement(s)
- [`profileDao().observe`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L41) — 1 inline statement(s)
- [`profileWriteGuard().ensureAdult`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L24) — 1 inline statement(s)
- [`profileWriteGuard().profileExists`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L27) — 1 inline statement(s)
- [`recentlyPlayedDao().count`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L172) — 1 inline statement(s)
- [`recentlyPlayedDao().recent`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L164) — 1 inline statement(s)
- [`recentlyPlayedDao().recentSnapshot`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L168) — 1 inline statement(s)
- [`resumeDao().continueCount`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L150) — 1 inline statement(s)
- [`resumeDao().continueSnapshot`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L145) — 1 inline statement(s)
- [`resumeDao().continueWatching`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L139) — 1 inline statement(s)
- [`resumeDao().continueWatchingForProfile`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L156) — 1 inline statement(s)
- [`resumeDao().forItems`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L107) — 1 inline statement(s)
- [`resumeDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L117) — 1 inline statement(s)
- [`resumeDao().getForProfile`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L121) — 1 inline statement(s)
- [`resumeDao().remove`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L175) — 1 inline statement(s)
- [`resumeDao().removeForProfile`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L179) — 1 inline statement(s)
- [`seriesDao().allPreview`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L80) — 1 inline statement(s)
- [`seriesDao().allPreviewFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L85) — 1 inline statement(s)
- [`seriesDao().byId`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L172) — 1 inline statement(s)
- [`seriesDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L200) — 1 inline statement(s)
- [`seriesDao().categoryCount`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L139) — 1 inline statement(s)
- [`seriesDao().categoryLatest`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L153) — 1 inline statement(s)
- [`seriesDao().categoryPreview`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L136) — 1 inline statement(s)
- [`seriesDao().categoryPreviewFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L144) — 1 inline statement(s)
- [`seriesDao().countAllFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L115) — 1 inline statement(s)
- [`seriesDao().countInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L70) — 1 inline statement(s)
- [`seriesDao().countsByCategory`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L166) — 1 inline statement(s)
- [`seriesDao().countSnapshot`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L169) — 1 inline statement(s)
- [`seriesDao().deleteCategory`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L209) — 1 inline statement(s)
- [`seriesDao().deleteEpisodes`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L240) — 1 inline statement(s)
- [`seriesDao().deleteGenerationsUpTo`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L212) — 1 inline statement(s)
- [`seriesDao().episodesByIds`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L203) — 1 inline statement(s)
- [`seriesDao().landingPreviewSorted`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L106) — 1 inline statement(s)
- [`seriesDao().matchingCategoryCounts`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L149) — 1 inline statement(s)
- [`seriesDao().observeEpisodes`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L234) — 1 inline statement(s)
- [`seriesDao().pagingAll`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L77) — 1 inline statement(s)
- [`seriesDao().pagingAllFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L111) — 1 inline statement(s)
- [`seriesDao().pagingInCategory`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L31) — 1 inline statement(s)
- [`seriesDao().pagingInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L55) — 1 inline statement(s)
- [`seriesDao().pagingSearch`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L127) — 1 inline statement(s)
- [`seriesDao().pagingSorted`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L96) — 1 inline statement(s)
- [`seriesDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L188) — 1 inline statement(s)
- [`seriesDao().searchPreview`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L132) — 1 inline statement(s)
- [`seriesDao().suggestionCandidates`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L158) — 1 inline statement(s)
- [`seriesDao().totalCount`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L118) — 1 inline statement(s)
- [`seriesDao().updateDetails`](src/main/java/com/dev/Tvivo/data/local/dao/SeriesDao.kt#L177) — 1 inline statement(s)
- [`shelfVisitDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L144) — 1 inline statement(s)
- [`syncMetaDao().deleteForAccount`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L85) — 1 inline statement(s)
- [`syncMetaDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L57) — 1 inline statement(s)
- [`syncMetaDao().oldestStamp`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L66) — 1 inline statement(s)
- [`syncMetaDao().staleCategories`](src/main/java/com/dev/Tvivo/data/local/dao/SupportDaos.kt#L75) — 1 inline statement(s)
- [`visitDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L111) — 1 inline statement(s)
- [`visitDao().increment`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L130) — 1 inline statement(s)
- [`visitDao().mostVisited`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L115) — 1 inline statement(s)
- [`visitDao().seed`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L125) — 1 inline statement(s)
- [`visitDao().topThree`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L120) — 1 inline statement(s)
- [`vodDao().allPreview`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L77) — 1 inline statement(s)
- [`vodDao().allPreviewFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L82) — 1 inline statement(s)
- [`vodDao().byId`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L191) — 1 inline statement(s)
- [`vodDao().byIds`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L203) — 1 inline statement(s)
- [`vodDao().categoryCount`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L133) — 1 inline statement(s)
- [`vodDao().categoryLatest`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L147) — 1 inline statement(s)
- [`vodDao().categoryPreview`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L130) — 1 inline statement(s)
- [`vodDao().categoryPreviewFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L138) — 1 inline statement(s)
- [`vodDao().countAllFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L112) — 1 inline statement(s)
- [`vodDao().countInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L66) — 1 inline statement(s)
- [`vodDao().countsByCategory`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L182) — 1 inline statement(s)
- [`vodDao().countSnapshot`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L188) — 1 inline statement(s)
- [`vodDao().deleteCategory`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L225) — 1 inline statement(s)
- [`vodDao().deleteGenerationsUpTo`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L228) — 1 inline statement(s)
- [`vodDao().landingPreviewSorted`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L103) — 1 inline statement(s)
- [`vodDao().matchingCategoryCounts`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L143) — 1 inline statement(s)
- [`vodDao().pagingAll`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L74) — 1 inline statement(s)
- [`vodDao().pagingAllFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L108) — 1 inline statement(s)
- [`vodDao().pagingInCategory`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L27) — 1 inline statement(s)
- [`vodDao().pagingInCategoryFiltered`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L51) — 1 inline statement(s)
- [`vodDao().pagingSearch`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L121) — 1 inline statement(s)
- [`vodDao().pagingSorted`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L93) — 1 inline statement(s)
- [`vodDao().recentlyAdded`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L170) — 1 inline statement(s)
- [`vodDao().searchPreview`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L126) — 1 inline statement(s)
- [`vodDao().suggestionCandidates`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L153) — 1 inline statement(s)
- [`vodDao().totalCount`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L185) — 1 inline statement(s)
- [`vodDao().updateDetails`](src/main/java/com/dev/Tvivo/data/local/dao/VodDao.kt#L218) — 1 inline statement(s)
- [`watchedDao().forItems`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L88) — 1 inline statement(s)
- [`watchedDao().get`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L91) — 1 inline statement(s)
- [`watchedDao().unmark`](src/main/java/com/dev/Tvivo/data/local/dao/ProfileDaos.kt#L104) — 1 inline statement(s)

## 8. Table → which procs touch it

Reverse index over each backend method's own inline SQL (there is no separate proc layer here).

| Table | Written by | Read by |
|---|---|---|
| `catalog_sync` | — | `catalogSyncDao().get`<br>`catalogSyncDao().observe` |
| `categories` | `categoryDao().deleteAll` | `categoryDao().byIds`<br>`categoryDao().observe` |
| `favourites` | `favouriteDao().remove`<br>`favouriteDao().removeForProfile` | `favouriteDao().count`<br>`favouriteDao().isFavourite`<br>`favouriteDao().isFavouriteForProfile`<br>`favouriteDao().observe`<br>`favouriteDao().observeForProfile`<br>`favouriteDao().preview` |
| `live_streams` | `liveDao().deleteCategory`<br>`liveDao().deleteGenerationsUpTo` | `liveDao().allPreview`<br>`liveDao().allPreviewFiltered`<br>`liveDao().byId`<br>`liveDao().byIds`<br>`liveDao().categoryLatest`<br>`liveDao().categoryPreview`<br>`liveDao().categoryPreviewFiltered`<br>`liveDao().countAllFiltered`<br>`liveDao().countInCategoryFiltered`<br>`liveDao().countSnapshot`<br>`liveDao().countsByCategory`<br>`liveDao().landingPreviewSorted`<br>`liveDao().matchingCategoryCounts`<br>`liveDao().pagingAll`<br>`liveDao().pagingAllFiltered`<br>`liveDao().pagingInCategory`<br>`liveDao().pagingInCategoryFiltered`<br>`liveDao().pagingSearch`<br>`liveDao().pagingSorted`<br>`liveDao().recentlyAdded`<br>`liveDao().searchPreview`<br>`liveDao().totalCount` |
| `profile` _(external)_ | `profileDao().deleteRow` | `profileDao().get`<br>`profileDao().observe`<br>`profileWriteGuard().profileExists` |
| `profile_allow` _(external)_ | `profileAllowDao().remove` | `profileAllowDao().observe` |
| `recently_played` _(external)_ | — | `recentlyPlayedDao().count`<br>`recentlyPlayedDao().recent`<br>`recentlyPlayedDao().recentSnapshot` |
| `resume_positions` | `resumeDao().remove`<br>`resumeDao().removeForProfile` | `resumeDao().continueCount`<br>`resumeDao().continueSnapshot`<br>`resumeDao().continueWatching`<br>`resumeDao().continueWatchingForProfile`<br>`resumeDao().forItems`<br>`resumeDao().get`<br>`resumeDao().getForProfile` |
| `series` | `seriesDao().deleteCategory`<br>`seriesDao().deleteGenerationsUpTo`<br>`seriesDao().updateDetails` | `seriesDao().allPreview`<br>`seriesDao().allPreviewFiltered`<br>`seriesDao().byId`<br>`seriesDao().byIds`<br>`seriesDao().categoryCount`<br>`seriesDao().categoryLatest`<br>`seriesDao().categoryPreview`<br>`seriesDao().categoryPreviewFiltered`<br>`seriesDao().countAllFiltered`<br>`seriesDao().countInCategoryFiltered`<br>`seriesDao().countSnapshot`<br>`seriesDao().countsByCategory`<br>`seriesDao().landingPreviewSorted`<br>`seriesDao().matchingCategoryCounts`<br>`seriesDao().pagingAll`<br>`seriesDao().pagingAllFiltered`<br>`seriesDao().pagingInCategory`<br>`seriesDao().pagingInCategoryFiltered`<br>`seriesDao().pagingSearch`<br>`seriesDao().pagingSorted`<br>`seriesDao().recentlyAdded`<br>`seriesDao().searchPreview`<br>`seriesDao().suggestionCandidates`<br>`seriesDao().totalCount` |
| `series_episodes` | `seriesDao().deleteEpisodes` | `seriesDao().episodesByIds`<br>`seriesDao().observeEpisodes` |
| `shelf_visits` _(external)_ | — | `shelfVisitDao().get` |
| `sync_meta` | `syncMetaDao().deleteForAccount` | `syncMetaDao().get`<br>`syncMetaDao().oldestStamp`<br>`syncMetaDao().staleCategories` |
| `visits` _(external)_ | `visitDao().increment` | `visitDao().get`<br>`visitDao().mostVisited`<br>`visitDao().topThree` |
| `vod_streams` | `vodDao().deleteCategory`<br>`vodDao().deleteGenerationsUpTo`<br>`vodDao().updateDetails` | `vodDao().allPreview`<br>`vodDao().allPreviewFiltered`<br>`vodDao().byId`<br>`vodDao().byIds`<br>`vodDao().categoryCount`<br>`vodDao().categoryLatest`<br>`vodDao().categoryPreview`<br>`vodDao().categoryPreviewFiltered`<br>`vodDao().countAllFiltered`<br>`vodDao().countInCategoryFiltered`<br>`vodDao().countSnapshot`<br>`vodDao().countsByCategory`<br>`vodDao().landingPreviewSorted`<br>`vodDao().matchingCategoryCounts`<br>`vodDao().pagingAll`<br>`vodDao().pagingAllFiltered`<br>`vodDao().pagingInCategory`<br>`vodDao().pagingInCategoryFiltered`<br>`vodDao().pagingSearch`<br>`vodDao().pagingSorted`<br>`vodDao().recentlyAdded`<br>`vodDao().searchPreview`<br>`vodDao().suggestionCandidates`<br>`vodDao().totalCount` |
| `watched` _(external)_ | `watchedDao().unmark` | `watchedDao().forItems`<br>`watchedDao().get` |

---

Indexed 41 routes · 155 backend methods · 124 procs · 15 tables · 47 frontend files · 0 SQL files.
