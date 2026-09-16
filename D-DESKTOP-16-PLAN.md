# D-Desktop-16 Implementation Plan

**Status:** Partially implemented; desktop verification pending
**Approved:** 2026-09-16  
**Batch:** 6 user requirements across suggestions, account, recently-added, fullscreen/OSC, back control, search UI

---

## Requirement 1: Suggested Movies & Series Regenerate on Every App Launch

**Current State:** Suggestions cached in `suggestionIds` (mutable in-memory map), persist until explicit Home refresh.

**Codex Review Finding:** DO NOT regenerate unconditionally in HomeScreen LaunchedEffect(Unit) — Home leaves/re-enters composition during navigation, would reshuffle mid-session. Current regenerateSuggestions() is deterministic (sorts by rating/title), so calling twice yields identical results.

**Plan:**
- Generate ONE random session snapshot after initial successful load, store in DesktopCatalogRepository (session-scoped, not volatile in-memory).
- Keep snapshot stable for entire Home↔Browse/Detail navigation within one app session.
- On app reopen (new DesktopCatalogRepository instance), generate new random snapshot.
- Make randomness injectable/deterministic in tests (pass seeded Random or mock suggestion method).

**Affected Code:**
- `DesktopCatalogRepository.kt:` Add session-scoped `suggestionSnapshot` field; generate once after first successful refresh; use same snapshot across all `ensureSuggestions()` calls.
- `DesktopShell.kt:HomeScreen:` Remove `regenerateSuggestions()` call from `LaunchedEffect(Unit)`.

**Acceptance Criteria:**
- ✅ App session: suggestions stable across Home↔Browse/Detail navigation
- ✅ Close app, reopen: new random suggestions on Home
- ✅ Hitting "Refresh library": suggestions snapshot cleared, new random set generated
- ✅ Test (deterministic): pass seeded Random fixture, verify exact suggestion IDs (never flaky "different two launches")

---

## Requirement 2: Account Displays Authenticated Username, Not Server Hostname

**Current State:** AccountScreen shows `credentials.hostAndPort()` (server endpoint).

**Plan:**
- Add optional `username: String? = null` field to `AccountInfo` data class (provider-returned).
- In `DesktopCatalogRepository.accountInfo()`: Extract `user_info.username` if present, set to `null` if missing.
- In `DesktopShell.kt:AccountScreen`: Display username with priority:
  1. `accountInfo.username` (provider-returned) if not null/blank
  2. Fall back to `credentials.username` (always available)
  3. Never display hostname, server address, or blank text

**Affected Code:**
- `DesktopCatalogRepository.kt:AccountInfo` (line 41): Add `username: String? = null`.
- `DesktopCatalogRepository.kt:accountInfo()` (lines 282-286): Extract and pass `user_info.username` or `null`.
- `DesktopShell.kt:AccountScreen` (line 354): Display `accountInfo.username ?: credentials.username`.

**Test Fixture Assertions:**
- ✅ Provider returns `user_info.username` → AccountScreen displays that username
- ✅ Provider omits/nulls `user_info.username` → AccountScreen falls back to `credentials.username`
- ✅ Never displays hostname, server address, or blank text
- ✅ Both scenarios verified in `DesktopCatalogRepositoryTest`

---

## Requirement 3: Recently Added Shows Max 500 Titles, Ordered by Provider Timestamp

**Current State:** Browse's `__recent` category orders by `first_indexed_at DESC` (local first-indexed time). No limit enforced. Missing provider `added_at` falls back to `System.currentTimeMillis()`, violating the requirement.

**Codex Review Finding:** `added_at` column already exists from schema v1, populated. No migration needed. CRITICAL: current missing provider timestamp fallback is `System.currentTimeMillis()`, violating the requirement. Local timestamp must NEVER determine Recently Added.

**Plan:**
- Use `added_at` column (already exists, provider-populated).
- Change `__recent` sort to `added_at DESC` (not `first_indexed_at DESC`).
- Add deterministic tie-breaker (e.g., `id ASC`) for same-timestamp items.
- Add LIMIT 500 to `__recent` query only.
- Add database index on `(account_id, type, added_at DESC)`.
- Store `0` (or `NULL`) for missing provider timestamp, sort `0` last.
- Document that contaminated cached timestamps are corrected on next refresh.

**Affected Code:**
- `DesktopCatalogRepository.kt:items()` (line 129): Change `__recent` clause to `ORDER BY added_at DESC, id ASC LIMIT 500`.
- `DesktopCatalogRepository.kt:insertItem()`: Do NOT fall back `added_at` to `System.currentTimeMillis()`; use `0` if provider timestamp missing.
- Database migration or schema comment: document that `added_at = 0` sorts last.

**Acceptance Criteria:**
- ✅ Browse → Recently added max 500 items
- ✅ Items ordered by provider `added_at DESC`, never `first_indexed_at`
- ✅ Missing provider timestamp (0 or NULL) sorts last
- ✅ Same-timestamp items break ties consistently (by id ASC)
- ✅ Other filters (__all, __continue, __favourites) NOT limited to 500
- ✅ Test: refresh with >500 items, verify last item is oldest by provider timestamp

---

## Requirement 4: Player Renders True Full-Screen Video & mpv OSC Shows Catalog Title

**Codex Review Findings:**
- TRUE FULLSCREEN: DO NOT use `GraphicsEnvironment.setFullScreenWindow()` on standalone Frame. App is already Compose Window with `WindowPlacement.Fullscreen`/undecorated. Canvas has no standalone Frame. Current issue: header consumes video height. Solution: hide/replace header when `fullScreen = true`.
- OSC TITLE: osd-title-part-B is wrong. Use `force-media-title` (sets `media-title`, which OSC displays). Set on mpv owner thread BEFORE loadfile.

**Plan:**

**TRUE FULLSCREEN:**
- Hide/replace header Row when `fullScreen = true`.
- Keep existing Compose window lifecycle.
- Do NOT let embedded mpv manage window fullscreen.

**OSC TITLE:**
- Add `setMediaTitle(title: String)` method to MpvPlayer to set `force-media-title`.
- Pass display title through `play()/playUrl()`, set BEFORE loadfile command.
- Cover all cases: movie, live, episode, fixture.

**Affected Code:**
- `MpvPlayer.kt:` Add `setMediaTitle(title: String)` method.
- `MpvPlayer.kt:playUrl():` Accept optional title parameter, set on owner thread before loadfile.
- `DesktopShell.kt:DesktopPlayerScreen:` Pass `title` to `player.playUrl()`.
- `DesktopShell.kt:DesktopPlayerScreen:` Hide header Row when `fullScreen = true` (conditional visibility).
- `MpvPlayer.kt:handleEvent():` Ensure MPV_EVENT_FILE_LOADED executes on owner thread.

**Acceptance Criteria:**
- ✅ Toggle fullscreen → video fills screen, header hidden
- ✅ Fullscreen Back button: overlay or right-side control (see Requirement 5)
- ✅ mpv OSC displays catalog title (movie/series/episode/live name), not filename
- ✅ Escape/F key or Back exits fullscreen, header returns
- ✅ Test (fixture only): fullscreen toggle, verify title in OSC

---

## Requirement 5: Right-Side Back Control Consistent Across Player, Detail, Browse, Episodes

**Codex Review Finding:** Detail and Player buttons are OutlinedButtons but NOT actually trailing right-side controls. Extract one common right-aligned Back component for all screens. Fullscreen needs overlay/right-side Back without keeping title/status header.

**Plan:**
- Create new `@Composable fun BackControl(onClick: () -> Unit)` component (OutlinedButton with icon/text, right-aligned, consistent styling).
- Use BackControl in Browse, Detail, Episodes, Player.
- When `!fullScreen`: Back in header with title/status (existing layout).
- When `fullScreen`: Back as overlay right-side control (semi-transparent or floating).
- Escape behavior: On Browse, Escape closes expanded search first; Back then routes Browse → Home.

**Affected Code:**
- `DesktopShell.kt:` Create new `BackControl` composable.
- `DesktopShell.kt:BrowseScreen:` Include `BackControl` in top-right.
- `DesktopShell.kt:DetailScreen:` Replace inline `OutlinedButton` with `BackControl`.
- `DesktopShell.kt:EpisodeScreen:` Add `BackControl` in top-right.
- `DesktopShell.kt:DesktopPlayerScreen:` Conditional Back rendering (header vs. overlay based on fullScreen).

**Acceptance Criteria:**
- ✅ Browse, Detail, Episodes, Player all have Back in same visual position (right-aligned)
- ✅ All styled consistently (OutlinedButton with icon)
- ✅ Fullscreen: Back visible as overlay without consuming header space
- ✅ Escape closes search expand before routing Back
- ✅ Test: visual consistency across all four screens

---

## Requirement 6: Browse Search Mirrors Android UI (Magnifier Closed, Expands on Click)

**Codex Review Finding:** Add `searchExpanded` to BrowseSavedState (per CatalogType), autofocus input on expansion. Clear X should clear query ONLY, not category filter. Escape closes search first. Prevent stale async results while typing.

**Plan:**
- Add `searchExpanded: Boolean` to `BrowseSavedState` (per `CatalogType`).
- Start with `searchExpanded = false` (magnifier icon only, input hidden).
- On magnifier click: `searchExpanded = true`, show input field + clear X, **autofocus** input.
- **Clear X:** Clears `query` ONLY, does NOT reset category filter (`filter` stays unchanged).
- **Escape:** Closes expanded search first (`searchExpanded = false`); subsequent Escape routes Back.
- **Prevent stale async:** Cancel in-flight `load()` when query changes.

**Affected Code:**
- `DesktopShell.kt:BrowseSavedState:` Add `searchExpanded` field.
- `DesktopShell.kt:BrowseScreen:` 
  - Magnifier icon button: on click, set `searchExpanded = true`.
  - Conditional rendering: if `searchExpanded`, show OutlinedTextField + clear X; else show magnifier only.
  - Clear X onClick: set `query = ""` (do NOT reset `filter`).
  - TextField: autofocus when expanded.
  - Keyboard handler: Escape closes search first, then Back routes to Home.
  - Async load cancellation on query change.

**Acceptance Criteria:**
- ✅ Browse starts with search collapsed (magnifier icon visible, input hidden)
- ✅ Click magnifier → search expands, input autofocused
- ✅ Type query → results filter real-time
- ✅ Click clear X → query cleared, category filter preserved
- ✅ Escape → search collapses back to magnifier
- ✅ Navigate away (Detail → Browse) → search state preserved
- ✅ Test (fixture): search query survives Browse→Detail→Browse; no stale async results

---

## Constraints & Edge Cases

- **Provider-agnostic credentials:** No changes to storage/passing; all work is UI/DB/player-internal.
- **No real stream opening in tests:** All manual verification uses local fixture only, never a real provider stream.
- **Build/deploy approval:** Blocked until user reviews this revision and approves.

---

## Implementation Sequence

1. **Requirement 3 (Recently Added):** Simplest; no UI changes, schema already supports it.
2. **Requirement 2 (Account Username):** Quick repository + UI change.
3. **Requirement 1 (Suggestions):** Session-scoped randomness; injectable for tests.
4. **Requirement 6 (Search UI):** State management in BrowseSavedState.
5. **Requirement 5 (Back Control):** Extract common component, apply to all screens.
6. **Requirement 4 (Fullscreen/OSC):** Last; depends on Back control for fullscreen overlay.

---

## Code Review Points

- Verify `added_at` column exists in schema and is populated for all items.
- Verify `force-media-title` is the correct mpv property (not `osd-title-part-B`).
- Verify AWT fullscreen is NOT used; header visibility is controlled via Compose only.
- Verify suggestion randomness is injectable/deterministic in tests (never flaky).
- Verify search state (expanded/collapsed, query, filter) survives Browse→Detail→Browse.
- Verify fullscreen Back control placement and accessibility.
- Manual testing uses local fixture only; no real provider streams.
