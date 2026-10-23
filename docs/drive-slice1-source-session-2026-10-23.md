# Drive slice 1 — source/session contract and implementation map

Binding parent: [Drive sign-in plan](drive-signin-plan-2026-10-23.md). This is a design map for slices 4–8, not an active Android/Room change. `driveSourceEnabled` is false by default through slice 8. The Xtream path remains the active default.

## Identity and session boundary

```kotlin
sealed interface SourceSession {
    val sourceId: SourceId
    data class Xtream(val credentials: Credentials, override val sourceId: SourceId) : SourceSession
    data class Drive(override val sourceId: SourceId, val folderId: String?) : SourceSession
}
```

`SourceSession` belongs in the Android app in slice 4; it is deliberately absent from slice 1 code. `SourceId.value` is the Room `accountId`/`accountKey`. For Xtream, use the existing `AccountIdentity.of(credentials)` **unchanged**, a 32-character lowercase SHA-256 prefix over the current `host|port|username` canonical form. Prefixing or rehashing it would orphan existing catalogue and user rows. For Drive, `SourceId.drive(permissionId)` is `drive:` plus the first 32 lowercase hex characters of SHA-256 over the exact nonblank Drive `about.get` `user.permissionId`. Neither email nor folder ID is an account key. An empty/missing permission ID blocks activation; never create an unstable fallback key. The source kind is explicit in the session, not guessed from a stored credential blob.

Slice 4 defines one Android `driveSourceEnabled` flag, false in the release/default configuration. The flag gates the Drive login action, Drive session activation, Drive workers and Drive navigation together. When false, startup loads the legacy Xtream session even if a Drive record was previously saved; it leaves that record encrypted and its Room rows intact. Tests can enable the flag through one injected configuration seam. A missing Drive runtime configuration is a clear unavailable state, never a silent Xtream credential fallback.

All item identity is `(sourceId, contentType, itemKey)`. `ItemKey.value` preserves existing Xtream numeric IDs as decimal strings and Xtream episode IDs as their original strings; Drive movie/show/episode/song keys are canonical UUID strings from the index. `vod`, `series`, `live` remain the existing Room content keys; Drive movies use `vod`, Drive shows and **episodes** use `series`, Drive songs use new `music`, and Drive has no `live`. Show IDs and episode IDs share the `series` content namespace, so the v1 parser must reject any show/episode duplicate UUID within a series index; a show is not a resume target. Keep item IDs stable across title, URL and group edits. UI keys include source, content type and item key where state can survive a source switch; persisted user-data keys already include account/profile/type/item. `ItemKey` itself is only the innermost ID, never a globally unique key.

Category IDs are `drive-group:` plus SHA-256 of UTF-8 `contentType + NUL + exact group` (first 32 lowercase hex characters). Exact group labels remain in `categories.name` and wrap to two lines; the hash prevents cross-type collisions and survives index reordering. Changing the exact group text intentionally makes a new category. Index `items` array order, then season/episode array order, is stored as `sortOrder`, not inferred from UUID or title. The `__` virtual folder IDs remain reserved.

The encrypted multi-source store is a versioned envelope in the existing DataStore/Tink keyset, with a saved-source map and one active source ID. On first read, decode the current v1 Xtream blob, derive its legacy account key, and write the envelope before removing the old blob; retain the keyset. Source switching has `active`, `candidate`, `generation`: acquire authorization, obtain permission ID, validate/find or bootstrap the folder, save candidate, then change the active pointer in one DataStore edit. Back, denial, quota, corruption or cancellation discards candidate state and preserves the prior active session. Async sync/playback work captures source ID + generation and rejects completion after switching. Stopping use of a source and remote revocation are separate actions; neither clears Room user rows. Existing Xtream `CredentialsStore.wipe()` must never be used for a switch.

## Exact dispatch map for later slices

| Current Xtream-shaped place | Concrete Drive/source change |
| --- | --- |
| `MainActivity.kt` `Route.Main/Home/Browse/ItemDetail/SeriesDetail/Settings/Subscription/Diagnostics/NetworkDiagnostics` carry `Credentials`; detail IDs are `Int`; `browseRoute` takes credentials. Startup `store.load()` chooses Login/Main; `switchingAccount` holds credentials. | Routes carry `SourceSession`; detail routes carry `ItemKey` and type. Startup loads active source from the encrypted envelope, allowing cached Drive Room rows to open offline. Preserve the Xtream route branch and Back behavior. Subscription and Xtream network diagnostics are available only for `Xtream`; Drive account and sync facts use Drive settings. Keep candidate switch outside active routes until accepted. |
| `auth/CredentialsStore.kt`, `LoginViewModel.kt`, `auth/AuthRepository.kt`, `ui/settings/AccountViewModel.kt`, `SettingsScreen.kt` save/load/wipe one credential, and refresh/account UI uses Xtream server facts. | Migrate the v1 encrypted blob in place to multiple saved source records. Login keeps the Xtream form plus the Drive action behind the flag. Dispatch validate/refresh/account data by session. Keep old sign-in operational; source switch never calls `wipe()`. Reauthorization preserves Room data. |
| `BrowseViewModel.kt` loads `CredentialsStore`, derives `AccountIdentity`, constructs `VodRepository`/`SeriesRepository`/`LiveRepository` and `CatalogSyncer`; `CatalogSource` wraps only these; `typeKey` has three cases. | Inject active session/factory; Xtream factory returns those same classes. Drive factory returns Drive movie/show/music catalogue adapters backed by new DAOs and Drive sync state. No Drive Live source. Add `music` branch; use `SourceId.value` for saved sort/category/filter keys, sync progress, virtual folders and playback state. Cancel stale requests on source generation change. |
| `BrowseItem.kt` has `id: Int`; `BrowseScreen.kt`, `ContentGrid.kt`, `BrowseViewModel.pendingFocusItemId` and callbacks use `Int` for focus, favourites, resume and card activation. | Convert the shared browse-facing ID to `ItemKey` or canonical `String` across callbacks and `SavedStateHandle`. Keep numeric conversion only at the Xtream repository edge. Preserve `focusProperties { enter }` and explicit requestFocus by stable item ID. Drive cards use existing poster dimensions; Music gets a source-aware card/detail shape. |
| `VodRepository.kt`, `SeriesRepository.kt`, `LiveRepository.kt`, `CachedFetch.kt`, `sync/CatalogSyncer.kt`, Xtream parsers and DAOs use credentials, integer stream/show IDs, per-category TTL, and `get_series_info`. | Leave Xtream implementations and parsers intact. Add Drive DAOs/repositories using string UUIDs, group categories and per-file fingerprint/generation sync. Drive show seasons/episodes come only from the series index, never `get_series_info`. An invalid index never replaces last-good rows. A valid `items: []` clears only that account/type generation and its categories, leaving user-state rows intact. |
| `ItemDetailViewModel.kt` and `SeriesDetailViewModel.kt` independently reload credentials; detail screens take integer IDs. Series detail fetches info and queues Xtream episodes. | Resolve by passed source session + string key. Drive detail reads local movie/show/episode metadata and validated URL from Room, without a panel info call. Series detail reads normalized Drive seasons/episodes. Favourites/resume use the same account/type/string key. A removed media row shows not-found without deleting a saved favourite. |
| `SearchScreen.kt` constructs `HomeRepository(accountId)`; `HomeRepository.kt` uses three Xtream DAOs, `toIntOrNull()` in recent/favourite/continue resolution, and `HomeCard.key = type.name + Int`. `HomeScreen.kt` uses that repository and typed shelves. | Source-aware repository/factory resolves string keys against Drive DAOs. Search Movies/Series/Music for Drive, Movies/Series/Live for Xtream; My Tvivo includes Music favourites/recent/Continue Listening where duration is known. Keep search and snapshot caches partitioned by `SourceId`; use composite stable card keys. No `toIntOrNull()` on Drive IDs. |
| `MainActivity.playItem()` and series play block call `StreamUrlBuilder.movie/live/episode(credentials, id, ext)`, put URL and episode URL arrays into player Intents, and record recent plays. | Introduce typed `PlayableMedia` resolved from the active source's Room row **at activation**. Xtream resolution continues through `StreamUrlBuilder`; Drive resolution uses the validated direct URL. Pass source ID, type, string item key and request ID to player; resolve/revalidate queued Drive URLs by key when advancing so index edits cannot replay stale Intent URL arrays. Never attach a Drive bearer token to external media. Recent play is recorded against the same composite key. |
| `PlayerActivity.kt` requires `EXTRA_URL`, has `EXTRA_ITEM_ID/CONTENT_TYPE/ACCOUNT_ID` strings, but Live navigation parses ID to `Int` and episode queues carry URL arrays. `PlaybackHandoff.Item/QueueItem` and `PlaybackService.kt` own URL/queue, save resume by string ID, fetch movie duration by `toIntOrNull()`, and choose movie/episode modes. | Keep a single `PlaybackService`/MediaSession. Make launch/handoff carry source identity plus item key and media kind; resolve Drive media from Room just before opening and after queue transitions. Keep Live and Xtream duration branches guarded to Xtream. Add music audio attributes, metadata, queue, background controls, noisy/audio-focus behavior and mini player in slice 8. `EXTRA_URL` can remain for the Xtream compatibility branch, but Drive launches must not depend on a serialized stale URL. |
| `PlaybackStateRepository.kt` and `SupportDaos.kt` already take string `itemId`; `favourites`, `resume_positions`, `watched`, `recently_played`, `profile_allow`, `visits`, `shelf_visits`, `categories`, `sync_meta`, `catalog_sync` are account/type scoped. Current `savePosition()` marks watched only for `series`/`vod`. | Reuse the existing tables/DAOs with Drive `SourceId.value` and stable string keys. Ensure a default profile row exists for each Drive source before user-state writes. Add `music` resume/finish policy only for known duration, and recent/favourite shelves; shows stay nonplayable. Preserve old Xtream rows and profile foreign keys. Source-scoped search history, saved sort state, refresh timestamps and Home snapshot keys also need partitioning. |
| `TvivoImageLoader.kt` has one sized Coil disk cache keyed by image URL; card dimensions are pipeline inputs. | Reuse the sized pipeline. Drive artwork URL is validated and never fetched during index sync. If account separation is required for an identical URL with changed content, use source + URL as an explicit cache key without changing 220×330 poster or 220×124 channel decode sizes. |

## Pure shared-core ports added in this slice

`source/SourceKeys.kt` defines `SourceId` and `ItemKey` value classes and their canonical factories. `source/SourcePorts.kt` defines the adapter-independent `IndexStore` and `SourceAuthorization` signatures:

```kotlin
suspend fun listChildren(parentId: String?, pageToken: String? = null): IndexPage
suspend fun createFolder(parentId: String?, name: String): IndexMetadata
suspend fun readIndex(fileId: String): ByteArray
suspend fun writeIndex(parentId: String, name: String, bytes: ByteArray): IndexMetadata
suspend fun metadata(fileId: String): IndexMetadata

suspend fun begin(): AuthorizationStep
suspend fun continueAuthorization(): AuthorizationStep
suspend fun accessToken(): SourceAccessToken?
suspend fun revoke(): Boolean
```

`IndexPage` carries pagination; metadata carries ID/name/MIME/size/modified time/version/MD5. Each `SourceAuthorization` instance belongs to one candidate or saved source. `PhoneConsent` carries only an opaque local request ID; its Android adapter owns the actual consent resolution. `DeviceCode` carries the TV URL, case-sensitive code, expiry and polling interval. The TV adapter handles `authorization_pending`, `slow_down`, token rotation and encrypted refresh storage; the phone adapter asks Play services for a fresh token and persists no token. `continueAuthorization()` advances the adapter state after consent or a scheduled poll, and returns `Pending`, `Ready`, `Denied` or `Expired`; caller cancellation closes the flow. `accessToken()` obtains a fresh short-lived bearer token for Drive API calls only. `SourceAccessToken.toString()` is redacted. No UI, Room, Google SDK or network implementation is in shared-core.

## Room v8 design (slice 4)

New tables below are additive to v7. Names are the intended Room `@Entity` schema; `TEXT` means non-null unless marked `NULL`. Rows in every table are scoped by `accountId = SourceId.value`. `generation` is a committed per-list generation stored in `drive_index_state`; reads join/filter to that value. A sync may write replacement rows to a new generation, then flip the state row and delete old rows in one short transaction. Category replacement for that account/type occurs in the same flip. No foreign key cascades from media rows to favourites/resume: removed titles must not erase user choices.

```sql
CREATE TABLE drive_movies (
  accountId TEXT NOT NULL, id TEXT NOT NULL, categoryId TEXT NOT NULL,
  title TEXT NOT NULL, nameDisplay TEXT NOT NULL, nameNormalized TEXT NOT NULL,
  directUrl TEXT NOT NULL, posterUrl TEXT NULL, year INTEGER NULL, plot TEXT NULL,
  sortOrder INTEGER NOT NULL, generation INTEGER NOT NULL,
  PRIMARY KEY(accountId,id,generation));
CREATE INDEX index_drive_movies_accountId_categoryId_sortOrder ON drive_movies(accountId,categoryId,sortOrder);
CREATE INDEX index_drive_movies_accountId_nameNormalized ON drive_movies(accountId,nameNormalized);
CREATE INDEX index_drive_movies_accountId_generation ON drive_movies(accountId,generation);

CREATE TABLE drive_shows (
  accountId TEXT NOT NULL, id TEXT NOT NULL, categoryId TEXT NOT NULL,
  title TEXT NOT NULL, nameDisplay TEXT NOT NULL, nameNormalized TEXT NOT NULL,
  posterUrl TEXT NULL, year INTEGER NULL, plot TEXT NULL,
  sortOrder INTEGER NOT NULL, generation INTEGER NOT NULL,
  PRIMARY KEY(accountId,id,generation));
CREATE INDEX index_drive_shows_accountId_categoryId_sortOrder ON drive_shows(accountId,categoryId,sortOrder);
CREATE INDEX index_drive_shows_accountId_nameNormalized ON drive_shows(accountId,nameNormalized);
CREATE INDEX index_drive_shows_accountId_generation ON drive_shows(accountId,generation);

CREATE TABLE drive_seasons (
  accountId TEXT NOT NULL, showId TEXT NOT NULL, seasonNumber INTEGER NOT NULL,
  title TEXT NULL, sortOrder INTEGER NOT NULL, generation INTEGER NOT NULL,
  PRIMARY KEY(accountId,showId,seasonNumber,generation),
  FOREIGN KEY(accountId,showId,generation) REFERENCES drive_shows(accountId,id,generation) ON DELETE CASCADE);
CREATE INDEX index_drive_seasons_accountId_showId_sortOrder ON drive_seasons(accountId,showId,sortOrder);

CREATE TABLE drive_episodes (
  accountId TEXT NOT NULL, id TEXT NOT NULL, showId TEXT NOT NULL,
  seasonNumber INTEGER NOT NULL, episodeNumber INTEGER NOT NULL,
  title TEXT NOT NULL, nameDisplay TEXT NOT NULL, nameNormalized TEXT NOT NULL,
  directUrl TEXT NOT NULL, posterUrl TEXT NULL, plot TEXT NULL,
  sortOrder INTEGER NOT NULL, generation INTEGER NOT NULL,
  PRIMARY KEY(accountId,id,generation),
  FOREIGN KEY(accountId,showId,seasonNumber,generation) REFERENCES drive_seasons(accountId,showId,seasonNumber,generation) ON DELETE CASCADE);
CREATE INDEX index_drive_episodes_accountId_showId_seasonNumber_sortOrder ON drive_episodes(accountId,showId,seasonNumber,sortOrder);
CREATE INDEX index_drive_episodes_accountId_generation ON drive_episodes(accountId,generation);

CREATE TABLE music_tracks (
  accountId TEXT NOT NULL, id TEXT NOT NULL, categoryId TEXT NOT NULL,
  title TEXT NOT NULL, nameDisplay TEXT NOT NULL, nameNormalized TEXT NOT NULL,
  directUrl TEXT NOT NULL, artist TEXT NULL, album TEXT NULL, artworkUrl TEXT NULL,
  year INTEGER NULL, durationSeconds INTEGER NULL,
  sortOrder INTEGER NOT NULL, generation INTEGER NOT NULL,
  PRIMARY KEY(accountId,id,generation));
CREATE INDEX index_music_tracks_accountId_categoryId_sortOrder ON music_tracks(accountId,categoryId,sortOrder);
CREATE INDEX index_music_tracks_accountId_nameNormalized ON music_tracks(accountId,nameNormalized);
CREATE INDEX index_music_tracks_accountId_generation ON music_tracks(accountId,generation);

CREATE TABLE drive_index_state (
  accountId TEXT NOT NULL, kind TEXT NOT NULL,
  folderId TEXT NULL, fileId TEXT NULL, version TEXT NULL, md5Checksum TEXT NULL,
  modifiedAtMillis INTEGER NULL, sizeBytes INTEGER NULL,
  generation INTEGER NOT NULL DEFAULT 0, lastSuccessfulSyncAt INTEGER NULL,
  bootstrapState TEXT NOT NULL DEFAULT 'none',
  PRIMARY KEY(accountId,kind));
CREATE INDEX index_drive_index_state_accountId_bootstrapState ON drive_index_state(accountId,bootstrapState);
```

`drive_index_state.kind` is `library`, `movies`, `series` or `music`. The `library` row holds root folder ID and `library.json` marker ID; list rows hold child folder and index file IDs. `bootstrapState` is `none`, `pending` or `complete`; pending root ID is persisted before creating children. `version` is Drive's file version string, separate from index JSON schema version. `md5Checksum` and `modifiedAtMillis` are fallback fingerprints; missing strong fingerprints force a read. `MIGRATION_7_8` creates these six tables and indices, adds no columns to v7 user tables, and is registered after migrations 3→4→5→6→7. Update `AppDatabase` entities/DAOs, export `8.json`, and test 5→8, 6→8, 7→8 against exported schemas with seeded favourites, resume, profiles, watched and recent rows preserved. Room schema JSON, not this SQL sketch, is the final migration equality oracle. Never add destructive fallback.

## Ordered next work

1. **Slice 2:** shared-core v1 parser/validation and golden JSON vectors; enforce UUID uniqueness across show and episode namespace, item/file/URL limits and exact group hashing. No Android dependency.
2. **Slice 3:** sibling mock Drive server and fake `IndexStore`/`SourceAuthorization`, including paging, two accounts, interrupted create, quota/permission/rate errors and device-code states. Keep the Xtream mock intact.
3. **Slice 4:** encrypted multi-source migration, `SourceSession`, v8 schema/DAOs, `PlayableMedia` resolution contract, active-source factory and string browse/detail/home/search keys behind the disabled flag. Make Xtream regression and migration tests the gate before Drive activation.
4. **Slice 5:** Drive repository, root discovery/bootstrap and transactional per-file sync against the mock. Persist pending root state, compare strong fingerprints, retain last good generations and use only mock seed links.
5. **Slice 6:** phone Play-services authorization and TV device-code adapters, local token handling, staged switch/revoke, and fake-clock auth tests. Real client IDs remain untracked.
6. **Slice 7:** adaptive login/bootstrap/settings plus Drive movie/show browse, detail, search and My Tvivo. Offline rows open without authorization round trip; no implicit playback.
7. **Slice 8:** Music repository/UI and audio-only session queue in the one PlaybackService, including notification controls, background continuation, resume policy and TV focus. Keep the release flag off until mock acceptance and the separately approved device QA gate.

## Plan corrections and explicit checks

- The parent plan names movie/show/episode/music tables but a normalized season hierarchy needs **`drive_seasons`** to retain season title/order even when a season has no episodes. This design adds that sixth v8 table.
- The parent plan's `DriveSource` adapter description omits pagination from `listChildren`; the real Drive files list can paginate. `IndexStore.listChildren` therefore returns `IndexPage` and takes `pageToken`.
- The current `BrowseItem.id` and focus paths are integer-shaped, and `HomeRepository` drops nonnumeric IDs via `toIntOrNull()`. A source factory alone cannot make Drive browsable; slice 4 must widen those shared UI contracts while preserving Xtream mapping.
- The current player Intent and queue serialize URLs. The parent plan's “resolve from Room at activation” also has to cover queue advance, replay and reopen to prevent stale Drive URLs after an index edit. A Drive branch must resolve by key again there.
- The project bible's v7 is the current development schema; the product bible's v5 line describes an older shipped state. Migration coverage still starts at real v5.
- Existing `AccountIdentity.of` does not include the HTTP/HTTPS scheme in its Xtream hash (the TODO already calls for a separation test). This slice preserves its exact output to avoid orphaning user rows; a future change to that identity needs an explicit migration rather than a silent recompute.
- A valid empty list and a corrupt/missing file have distinct outcomes. Each list can commit independently after its own validation; an invalid list retains its last-good generation, even when another changed list is valid.

## Security follow-up (automated review of slice 2, 2026-10-23) — required before the feature flag is enabled

LibraryParser's URL check is only a first filter. To do:
1. Parser: strip a trailing dot before host comparison; reject single-label hosts and `.lan`, `.home.arpa`, `.internal`, `.local`; reject non-canonical numeric hosts (all-digit/hex/octal, fewer than 4 dotted parts); add fc00::/7, 100.64.0.0/10, unspecified, IPv4-mapped and NAT64/6to4-embedded private IPv4 to the private ranges.
2. Fetch time (slice 5 sync + playback/image layer): resolve the host, reject private/loopback/link-local/multicast results, and connect to the exact checked address (pin the IP to stop DNS rebinding); re-check on every redirect hop. The mock's explicit local HTTP origin stays allowed only via the allowMockHttp flag in debug builds.

### Follow-up 2 (security review of slice 5, 2026-10-23) — UrlGuard must deny by default
Make `UrlGuard`/`isPublic` in shared-core/.../source/drive/DriveSyncEngine.kt allow-list based: IPv6 allowed only in global unicast 2000::/3, then reject 2001::/32 (Teredo, re-check embedded IPv4), 2001:db8::/32, IPv4-compatible ::/96, 64:ff9b:1::/48, and re-check the IPv4 embedded in NAT64/6to4/mapped forms. Add IANA special-purpose IPv4 blocks 192.0.0.0/24, 198.18.0.0/15, 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24 (plus existing private/CGNAT/loopback/link-local/multicast/reserved). Tests for each. Run after slice 5b (same files).
