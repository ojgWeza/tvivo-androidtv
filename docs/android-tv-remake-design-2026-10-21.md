# Android TV navigation and player remake — design — 2026-10-21

**Decision:** Give TV its own browse composition: a persistent category rail beside the current grid, a TV-owned search keyboard, truthful catalog states, and an immersive player with a small set of predictable remote commands. Keep the existing repositories, Room paging, Media3 service, and phone/tablet layouts. This is a design and implementation plan, not a claim of device validation.

## Evidence and boundaries

The [D-pad walkthrough](tv-dpad-walkthrough-2026-10-21.md) observed 19 keys to a 678-title movie category, 16 keys to another category and Back, and Back returning to landing shelves. The reported 45,000-row indexing/empty combination was **not reproduced** on that cached emulator; the code permits the confusing combination because full-type sync progress and category Paging load state are independent. The emulator showed a system Gboard keyboard, while the owner's TV displayed a phone-keyboard prompt: the app must work under either system IME. The picker screenshot showed underlying content through its scrim; it did not prove the top bar painted over the panel. One walkthrough journey accidentally opened a stream, so its Favourites path supplies no successful key count.

The [screen audit](android-screen-needs-audit-2026-10-19.md) predates some current player wiring: `PlayerActivity` now has live channel up/down and last-channel callbacks. Preserve and verify these rather than claiming they are wholly missing. The [parity check](cross-platform-parity-check-2026-10-21.md) and [parity ledger](../vault/parity/ledger.json) distinguish source presence from device acceptance. WinUI's `CatalogLandingPage` keeps group selection beside paged results and cached content usable during refresh; `MainWindow` has a cinema layout, title/about control, episode adjacency and sleep-timer UI. These are behavior references, not a request to copy desktop chrome.

Product constraints: native Kotlin/Compose for TV, Media3, account-scoped Room data, direct media URLs, no provider identifiers in public assets, no automated stream playback, and no destructive migration. Keep category names on two lines without truncating distinguishing suffixes. The [product bible](../PRODUCT-BIBLE.md) defers voice search and an EPG guide; neither is a launch dependency. All playback, resume, favourites and basic tracks remain free. `LocalIsTelevision` gates the new layout and remote behavior; phone/tablet flows stay as they are unless a later slice explicitly names them.

## 1. TV information architecture

Keep a quiet top strip for **For You · Movies · Series · Live TV · Search · Settings**. Search and Settings have permanent labelled focus targets; Sort and Refresh live inside the relevant browse view, so the global strip does not change meaning as content changes. For You starts with **Continue watching** and **Favourites** as the first two focusable shelves, followed by recently added and other shelves. Those same two folders are pinned at the top of each content-type rail, with a clearly labelled **All** row and provider categories below. Do not create separate top-level tabs that crowd the strip.

Movies, Series and Live use one TV master/detail screen: a persistent left rail (about one quarter of the usable width) and a paged grid on the right. The selected row remains highlighted and its full name and cached count appear above the grid. The landing is **All**, not a misleading two-shelf page named Categories; optional discovery shelves can appear in For You. Selecting another rail row replaces the grid **in place** without navigating through a landing or resetting the rail scroll. Left from the grid enters the rail; Right from the rail enters the grid at the last focused item or the first available item. A category with no rows still presents a focusable recovery action.

**Back:** detail/player returns to the originating type, category, filter, scroll offset and card focus. Back from grid focus moves to the selected rail row; Back from rail focus returns to the previously selected top-level destination (or For You on cold entry), without clearing category selection. Back from a popup closes only that popup. Switching top-level types restores each type's last category and item. There is no intermediate category-list screen to revisit.

| Journey, from an already open type | Target remote cost |
| --- | --- |
| Grid → selected category row | 1 Left; 1 Right returns to grid |
| Landing focus → category rail | 0: the selected row owns initial focus |
| Select the highlighted category → grid | 1 OK, then 1 Right; selecting a different **visible adjacent** row is 1 arrow + OK + Right = 3 |
| Change to another type | 1 Up to top strip, then tab traversal + OK; selected type is remembered |
| For You → Continue watching or Favourites | Initial focus on Continue watching; 1 Down reaches Favourites, OK enters that shelf |
| Live hidden controls → next/previous channel | 1 Up/Down or CH+/CH−; last channel from an overlay action or remote shortcut after validation |

A literal **three-key guarantee for every provider category** is impossible with an unbounded category list and D-pad-only input. The guaranteed bound is **at most one key to category choice from the grid, and three keys for a visible adjacent category**. Far-off rows use a focusable category quick filter and page jumps; the device check records real counts for first, middle and last rows. This makes the target measurable without pretending a 100-row rail has three-key random access.

## 2. Focus and visual rules

- On entering a type, focus the selected category row after categories arrive; show a focusable **All** placeholder while they load. On returning from detail, the grid card restores by stable item ID and **requests focus** after paging attaches it. Re-entering from the rail uses `focusProperties { enter }`. Do not use `focusRestorer()` with the explicit focus group used here.
- Every visible action has an attached, reachable focus target and a visible focus frame. The first empty/error action is focused when no cards exist; **Refresh category** is a real button and remains available on TV. Keep Up/Down/Left/Right links conditional on nodes actually in composition. Preserve the current category and rail focus while refresh runs.
- Prefer the permanent rail over a TV picker. For any residual TV modal, draw an opaque full-screen surface or a full-window scrim **above the shell**, trap focus inside it, and hide/occlude the top strip until dismissal. No headings, cards or top-nav labels show through. Restoring focus after dismissal is mandatory.
- Menu key and long-OK on a card open the same context menu (details, favourite, watched state, relevant category); short OK opens detail. No navigation arrow activates a card. Back closes one layer. Never move focus to an off-screen Refresh label or an invisible top-bar control.
- Use ten-foot type and contrast, clear selected versus focused states, two-line category labels, stable card sizes, and RTL-aware focus order. A category change announces the selected name and count. Keep the focused item in view without jumping the whole rail.

## 3. Search that belongs to the TV

Make `SearchScreen` render a TV-only in-app letter-grid keyboard instead of relying on `BasicTextField` to summon the device IME. Initial focus is the query/keyboard area, with an obvious highlighted key. Include letters, digits, space, backspace, clear, language switch (English/Arabic), and **Done / Results**. Show the entered query above the grid; debounce saved-catalog search. Directional movement is deterministic, wraps only where labelled, and holds repeat safely. Back first closes a result popup, then returns from results to the keyboard, then leaves Search; Back does not silently erase a nonempty query. Clear is explicit.

Place recent searches (profile/account scoped, bounded and individually clearable) below the query, before results. Keep result filters All/Movies/Series/Live focusable. Pressing Done moves focus to the first result or to a focusable **No saved matches** state; Down from the keyboard can enter recent searches/results directly. OK on a result opens detail, never playback. Returning from detail restores the result by stable key, query, filter and scroll. Show that search covers the **saved catalog**; if the relevant full sync is incomplete, say results may be incomplete and offer a focusable Refresh library action.

An optional phone keyboard can be added through the existing pairing path later. It cannot be required for search. Voice search remains deferred by the product bible unless the owner changes that decision. The TV letter grid must remain usable with any installed system IME, including one that displays the owner's phone-keyboard prompt.

## 4. Catalog progress and empty-state truth

Treat **full Movies/Series/Live library sync** and **selected category fetch** as two named operations. `CatalogSyncer` currently emits `STATE_INDEXING` with `done = rows written` and `total = 0` until completion; `BrowseScreen` prints `Indexing N…` without the content type or denominator. Show, for example, **“Updating Movies library · 45,000 titles processed · total unknown”** only while it runs. Show `x / y` only when a genuine provider total or a reliable measured total exists. At completion show the committed count. Never use a category count as the full-library denominator or a speculative provider count as fact.

`BrowseViewModel` should expose a typed state per content type (idle, updating with processed/known total, complete, partial, failed) and a separate selected-category state (cached rows, first fetch pending, refresh pending, confirmed empty, failed). Gate **“No titles in this category”** on a successfully completed, category-scoped fetch for that selected category with zero rows and no filter. A Paging snapshot of zero while the full generation is pending is **not** proof of emptiness. With cached rows, show them immediately and update in the background; on failure keep them visible with a neutral **Showing saved titles · Retry** status. A filter miss says **No matches for this filter** and offers Clear filter.

`CatalogSyncer`: preserve the previous generation on failure/zero response; expose truthful per-type progress and generation/account identity so stale completion cannot replace a new account. `BrowseViewModel`: start/observe the category fetch independently of full sync, retain its last confirmed outcome, reject stale selection results, and provide Refresh category even while a different full-type update continues. `BrowseScreen`/TV landing: render these states separately and keep the category rail, cached grid and focusable Refresh usable. Existing Room paging remains scoped to the chosen category; do not wait for 45,000 rows before showing the first category page. Test a cold 700-row category, cached/offline return, partial full sync, zero-row provider response, fast category switching, filter miss and account switch. Do not label unknown provider totals as known.

## 5. TV player

**Recommendation:** Playback opens in immersive video-first mode. OK or Right reveals a compact overlay; a focusable **Cinema / Details** action switches between video-only and a side panel with title, plot, year, category, progress and playback information. The video remains visible while browsing the panel; no desktop window concepts appear on TV. The overlay has a small top row (Back, title, Favourite, Info), a transport row (previous, seek/play/next as applicable), and a bottom row (Episodes/Channels, Audio, Subtitles, Speed, Aspect, Sleep timer, Playback info). Never show inactive controls for a content type as dead focus stops.

| Capability relative to WinUI | TV decision |
| --- | --- |
| Cinema/immersive, title/about, favourite | Full-screen video by default; explicit side panel and Cinema toggle; title/about and favourite reachable in one overlay |
| Audio/subtitles, speed, aspect | Keep existing track pickers; add VOD-only 0.75–2× speed and Fit/Fill/Zoom with a visible current value; do not offer speed for Live |
| Resume, episode adjacency/list | Preserve resume/start-over and next prompt; add Previous and an episode list anchored to the current series/season, with watched/progress markers |
| Live channels | Preserve current CH+/CH− and hidden-overlay Up/Down; add number entry with a short confirmation window, current-category channel list, last-channel action and stable position labels |
| EPG/now-next | Show only real provider/cached guide data if present; otherwise omit the guide and avoid invented programme titles. Full guide remains deferred |
| Sleep timer and playback info | Keep existing timer semantics and warning; expose remaining time, media format, resolution, audio/subtitle selection, seekability and connection/error status without exposing credential-bearing URLs |

**Remote map:** With controls hidden, OK/Right reveals them; VOD Left/Right seeks ±10 seconds (hold accelerates) if seekable even when duration is unknown; Live Up/Down or CH+/CH− zaps one channel, Left opens Channels, and digits start a channel-number buffer. With controls visible, arrows move focus and OK activates. Back closes an open menu/dialog first; otherwise it exits playback on its first delivered press, whether the overlay is visible or hidden, as already decided. Dedicated media play/pause and channel keys work regardless of overlay visibility. Menu/long-OK opens a concise player actions panel, including last channel on Live; number entry and menus consume keys before transport does. Overlay hides after about five seconds of playing/idle, remains visible while focused in a menu, paused, retrying or showing an error, and restores focus to the invoking control when a menu closes. A seek/zap confirmation is brief and does not steal focus. Avoid simultaneous streams when changing channels; preserve session ownership and stop semantics.

Build the player in stages: first overlay focus/Back/visibility and a device-proven transport map; then title/info/favourite and Cinema/Details; then episode queue/list; then Live number/last-channel refinements; finally speed/aspect/playback info and optional EPG data. Playback service and UI changes must remain separated enough to verify one queue/session owner. No automated test opens a provider stream.

## 6. Implementation slices

Each row is one reviewable, roughly luna-medium-sized implementation job. **Sol** designs the state/interaction contract named below before a Luna implementation; it is not a request for one giant rewrite. Local tests are allowed only when the execution session authorizes them; deployment and emulator/device runs require the owner's explicit approval under `AGENTS.md`. The checks listed here are acceptance steps for that later approved run.

| Order / owner | Slice and acceptance | Device check after approval |
| --- | --- | --- |
| 1 · **Sol design, Luna build** | TV browse route becomes one persistent rail + grid. Category selection swaps grid in place; selected name/count and per-type restoration agree; phone/tablet routes remain unchanged. | From Movies/Series/Live landing, focus starts on selected rail row; adjacent category is selected and grid entered in ≤3 keys; detail → Back restores card; grid → rail is 1 Left. |
| 2 · **Luna** | TV empty/error/loading controls are attached and focusable; Refresh category bypasses TTL; filter miss is distinct. Replace any remaining TV picker with opaque/occluding surface. | Cold/empty/error and modal screenshots show a clear focused action; no top-nav/card bleed; every arrow and Back has a destination. |
| 3 · **Sol design, Luna build** | Separate full-type and selected-category state; typed progress, known-total rules, stale-result guard, cached rows and truthful empty copy. | Simulated 700-item category opens first page before full sync; 45,000 processed is labelled library-wide/unknown total; zero Paging rows during sync never says category empty; cache remains usable offline. |
| 4 · **Luna** | TV-owned English/Arabic letter grid and deterministic focus; no required system IME; Done/Back/result restore. | On the owner's TV, enter a short query entirely with D-pad, open result detail and return to same result; no phone prompt blocks entry. |
| 5 · **Luna** | Bounded recent searches, profile/account scoping, clear action, saved-catalog incompleteness message. | Search and clear a recent term; switch profiles/accounts without leaking terms; incomplete catalog offers reachable Refresh. |
| 6 · **Sol design, Luna build** | Player focus/back/auto-hide map and Cinema/Details side panel; keep Media3 service ownership. | TV remote: hidden/visible/menu/paused/retry states; first Back exits playback unless a menu/dialog is open; no lost focus or double stream. |
| 7 · **Luna** | Player info/favourite, tracks, sleep timer, speed and aspect menus have current values and type-specific availability. | Traverse every visible action by D-pad; check 4:3 Fit/Fill/Zoom, multi-track fixture and timer warning; no credential URL in info. |
| 8 · **Sol design, Luna build** | Episode previous/next/list and Live queue number entry, last-channel and fast zap; reuse existing channel navigation where sound. | Fixture queue and approved single-stream manual check: CH± is one key, number entry selects intended channel, last-channel returns, series list/Back restore focus. |

After each slice, record its Android/TV parity status and evidence in `vault/parity/ledger.json` using the parity tool, and run its `check` before handoff. This design document itself records no implementation status. Batch the safe local checks, then request one combined owner approval for install/emulator/physical-TV validation; do not deploy from this document task.

## 7. Risks and owner decisions

1. **Three-key wording:** approve the measurable bound above (one key to rail; three for visible adjacent rows). A strict three-key guarantee for an arbitrary off-screen category requires a different input method or a bounded category set.
2. **Live OK:** keep detail-before-play for now, or let short OK start Live directly with long-OK for details? The latter reduces the largest watching delay but changes the app's current activation contract.
3. **TV player presentation:** validate immersive-by-default with a side panel on the owner's real TV before polishing animations or adding controls.
4. **Provider totals/EPG:** some responses provide no reliable total or guide. Unknown stays unknown, and guide controls remain hidden without real data.
5. **Performance and focus:** a persistent rail beside a paged 45,000-row library must keep item-ID focus and cached first-page latency under measurement; never trade correct focus for an unmeasured render cache.
