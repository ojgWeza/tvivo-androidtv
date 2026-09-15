# D-Desktop-16e Design Plan — Entry/Discovery + Idle Screen (Revised)

## Overview

16e implements a **two-state discovery layer** per the approved proposal (`desktop-home-proposal.md` lines 14-35):
1. **Entry/discovery state:** Shown after successful sign-in or normal app launch. Immersive, full-screen, interactive.
2. **Idle state:** After 5 minutes without input, the discovery layer dims to 40-50% opacity and rotates content slowly (8s per item) to avoid OLED burn-in.

Both states render from the same source (ten most recently added Movies+Series). The difference is opacity and whether rotation is active.

---

## Decision 1: Discovery Layer Content and Source

### Design Choice
- **Source:** Ten most recently added eligible items across Movies and Series combined (not __featured; that doesn't exist)
- **Ordering:** By `added` timestamp from local catalog (derived from provider timestamp), descending (newest first)
- **Timestamp reliability (per proposal line 34):**
  - If `added` timestamp has valid provider provenance and is parseable/non-placeholder: Label shelf/discovery as "Recently added"
  - If `added` timestamp is missing, malformed, or provider-marked as placeholder (per provider-specific convention): Label as "Recently indexed"
  - Trust is determined by provider source validity and parseability, NOT by item age or sync recency. Old content with a valid provider timestamp is trustworthy "Recently added"; new content with missing/placeholder timestamp is "Recently indexed"
  - Placeholder detection: Define per provider (e.g., if provider uses epoch/zero timestamp as placeholder, treat as missing; if provider uses a known placeholder string, detect it)
- **Filtering:** Eligible = playable items with complete metadata (no drafts, placeholders, or unplayable records)
- **Content per card:** Poster/backdrop image + title + content-type label ("Movie" or "Series")
- **Selection behavior:** Click on movie → pre-run/play route; click on series → episode picker
- **Fallback:** If fewer than 10 items or no eligible items cached, skip the discovery layer entirely and launch into the last selected tab (Movies by default) with actionable empty/loading state

### Observable Acceptance Criteria
1. Screenshot: Discovery layer shows 10 items, each with backdrop image, title, and "Movie" or "Series" label
2. Item count: Exactly 10 items (or fewer if catalog has fewer than 10 eligible items)
3. Ordering: Items are ordered newest to oldest by local `added` timestamp (derived from provider timestamp), verified against catalog values
4. Content type accuracy: Movies labeled "Movie", Series labeled "Series"
5. Tap behavior: Movie tap opens pre-run route (verified by screen transition); Series tap opens episode picker
6. Fallback: Catalog with <10 items or no eligible items skips discovery and enters Movies tab directly
7. Empty state: Displays "No recent content. Browse to find something to watch." or similar actionable message
8. Timestamp label accuracy: Verify "Recently added" vs. "Recently indexed" label matches timestamp provenance (recently added if valid provider timestamp; recently indexed if missing/placeholder)

---

## Decision 2: Entry/Discovery Screen Visual and Interaction

### Design Choice
- **Layout:** Full-screen immersive display (backdrop fills viewport)
- **Visual treatment:** Backdrop image covers full screen with semi-transparent gradient overlay (top: rgba(0,0,0,0.3), bottom: rgba(0,0,0,0.6)) for title/label legibility
- **Content overlay:** Title and content-type label positioned at bottom-left (24px margin from edges), white text, title in bold (DesktopTheme heading style), label in secondary text style
- **Entry animation:** Fade-in over 400ms (smooth discovery feeling)
- **Exit animation (to tab):** Fade-out over 200ms to reveal last selected tab
- **Input to exit:** ANY input (mouse movement, click, keyboard press, explicit browse action) immediately exits to last selected tab with saved scroll position and selection

### Observable Acceptance Criteria
1. Screenshot: Full-screen backdrop visible, title and type label at bottom-left in white text
2. Gradient overlay: Top is lighter, bottom is darker; title/label readable over all backdrops (test with light and dark images)
3. Entry animation: Fade-in takes ~400ms (verified with trace `discovery entered`)
4. Exit animation: Fade-out takes ~200ms (verified with trace `discovery exited`)
5. Input recovery: Tap anywhere → immediately exits to last selected tab
6. Scroll/selection intact: Last tab's scroll position and selected card are restored (verified by UI dump)
7. No blocking: Discovery exits on first input; no timer delay before responsiveness

---

## Decision 3: Idle State — Dimmed Rotation

### Design Choice
- **Trigger:** 5 minutes of no user input while discovery OR any content tab is visible and app is focused
- **Idle visual:** Same discovery layer but dimmed to 40% opacity (rgba(255,255,255,0.4) overlay on top, or reduce backdrop brightness to 40%)
- **Rotation:** Advance to next item every 8 seconds (same source, cycling through all 10 items)
- **Transition between items:** Cross-fade of backdrop + title/label over 400ms (smooth, not jarring)
- **OLED burn-in mitigation:** 
  - Content rotates (backdrop + title/label change every 8s) — prevents static image persistence
  - Text position alternates or shifts slightly (rotate between bottom-left and bottom-right every other card) to distribute pixel aging
  - Do not display static logo or text overlay
- **Loop behavior:** Infinite cycle; after 10th item, restart from 1st
- **Stop condition:** Any input immediately stops rotation and exits to last tab

### Observable Acceptance Criteria
1. Screenshot: Discovery layer visible at 40% opacity (content beneath dimmed but still readable)
2. Rotation interval: Card advances every ~8 seconds (verified with trace `idle rotation advance`, count 5+ transitions with timestamps)
3. Fade transition: Cross-fade between cards visible (not instant cut) over ~400ms
4. Item cycling: After 10 cards, rotation restarts from card 1 (verified with trace `idle rotation cycle restart`)
5. OLED mitigation motion: Text position shifts between cards (verify bottom-left vs. bottom-right alternation in screenshots)
6. Content variety: All 10 source items appear in sequence (no repeats until cycle restart)
7. Opacity correct: Text and images at 40% opacity (test readability without strain)
8. Background activity: No network requests during rotation (all artwork pre-fetched or loaded on discovery entry)
9. Input stops rotation immediately (verified with trace `idle rotation stopped(reason=<input_type>)`)

---

## Decision 4: Wake Semantics and Input Recovery

### Design Choice
- **Input classes that wake from idle:**
  - Mouse movement (any motion)
  - Mouse button click (any button)
  - Keyboard key press (any key except modifiers alone)
  - Explicit navigation action (rail click, search action, dialog open/close)
  - Player entry/exit
  - Window focus regain

- **Input classes that do NOT wake but still affect behavior:**
  - Modifier keys alone (Shift, Ctrl, Alt) do not wake but are tracked for modifiers on subsequent keys

- **Dialog and idle interaction (mutual exclusion):**
  - Idle cannot be entered while a dialog is visible; if idle timer expires while a dialog is open, defer idle entry until the dialog closes
  - If an app dialog opens (e.g., Account settings dialog, file dialog) while idle is active, the dialog wakes idle (idle exits, dialog is shown over the last tab)
  - System/external dialogs (OS file picker, print dialog, OS alerts) may appear over idle; treat as external and do not wake idle; rotation pauses while dialog is visible, resumes when dialog closes
  - No internal app dialog can open through an intercepting idle overlay without waking; the overlay must exit first

- **Wake behavior (consumed vs. replayed):**
  - Most input exits idle and is **consumed** (not passed to underlying screen): mouse movement, click on backdrop/title, any keyboard key
  - **Explicit navigation action** (e.g., Home rail click) exits idle and is **replayed as a semantic command** to the shell (navigates to the target tab/route AND saves that as the new restore target for future idle)
    - Navigation replay is NOT raw pointer pass-through; it is a semantic DesktopShell navigation command (e.g., `navigateTo(DesktopRoute.Home)`)
    - If clicking Browse rail during idle: discovery exits → shell receives nav-to-Browse → Browse opens at its saved state (filter/query/scroll/highlight from 16b)
    - If clicking Movies rail during idle while on Browse: shell receives nav-to-Movies → Movies tab opens → idle does not re-enter until timer restarts on Movies
  - Player entry exits idle immediately (player always covers idle; wake is implicit)

- **State restoration after wake:**
  - Return to the same tab (Home, Movies, Series, Live TV, Account) that was active before idle
  - Restore scroll position (use existing shell scroll state preservation)
  - Restore selected/focused card (if any)
  - Clear any transient UI state (dropdowns, search focus, etc.)

### Observable Acceptance Criteria
1. Mouse wake: Move mouse over idle screen → immediately exits and returns to last tab (no lag, verified with trace `idle woken(reason=mouse_movement)`)
2. Click wake: Click anywhere on backdrop/title → exits to last tab (click does not select card or trigger action)
3. Keyboard wake: Press any key → exits (no action, input is consumed)
4. Navigation wake and replay (content tab context): Click Browse rail during idle on Movies tab → idle exits → shell receives semantic nav-to-Browse → Browse opens with saved state (screen transition to Browse verified)
5. Navigation wake and replay (Browse context): Click Movies rail during idle while on Browse → idle exits → shell receives semantic nav-to-Movies → Movies tab opens (screen transition verified); navigate back to Browse → Browse state (filter/query/scroll) is still saved
6. Scroll restored: After wake, last tab's scroll position matches pre-idle state (verify with UI coordinate dump)
7. Selection restored: Focused card is same as before idle (verify with highlighted card ID in UI dump)
8. Browse idle: Enter idle from Browse → discovery layer dims and rotates; any wake while Browse is active → restores Browse route/filter/query/scroll/highlight (verify UI dump)
9. App dialog wake: Account/settings dialog opens during idle → idle wakes, dialog shown over last tab (not over idle)
10. System dialog over idle: System file picker during idle → idle does not wake; rotation pauses, resumes when dialog closes
11. Player entry: Player entry while idle → player covers idle immediately (no idle visible)
12. Focus loss during idle: App loses focus (minimize/switch window) → rotation stops, no background activity
13. Focus regain during idle: App regains focus while idle active → rotation resumes immediately, timer stays paused (verify trace shows no timer-restart event)

---

## Decision 5: Timer State Machine and Lifecycle

### Exact Rules (all contexts)

#### General timer behavior
- Start/reset on: First app launch, successful sign-in, any qualifying input, player exit
- Cancel/pause on: Window focus loss, app minimize, player entry, dialog open, sign-out, navigation to Account screen
- Resume on: Window focus regain (resumes paused state: idle rotation if already idle, countdown timer otherwise), dialog close (enters deferred idle if expiry occurred during dialog, resumes countdown otherwise), player exit, returning from Account to content tab

#### Screen-specific timer rules

**Sign-in flow:**
- Timer does not start until sign-in completes (dismiss sign-in screen)
- Show entry/discovery layer (full opacity) immediately after sign-in, not dimmed
- Restart timer for discovery layer as if normal launch
- Input during discovery does not reset timer; it exits to last saved tab and disables timer until first input on content tab

**Home/Movies/Series/Live TV tabs (content state):**
- Timer running while tab visible and app focused
- Timer restarts on: Input (any class above), navigation between tabs, search query change, category filter change, scroll completion
- Timer cancels on: Dialog open, player entry, window focus loss
- After 5 minutes of eligible inactivity, transition to idle state (dim discovery layer, start rotation)

**Browse/Search screen:**
- Timer running while visible and app focused
- Input (card selection, search refinement, category selection) restarts timer
- Player entry cancels timer
- After 5 minutes, transition to idle (dim discovery layer, rotate) — same as content tabs
- Any wake from idle while Browse is the active context: restore Browse route, filter, query, scroll position, and highlighted item (per 16b BrowseSavedState); do NOT exit Browse

**Detail/Episode picker screen:**
- Timer running while visible
- Any screen action (close detail, select episode) restarts timer
- Player entry cancels timer
- Idle does not show while detail is open (detail takes precedence)
- Idle is shown after returning to content tab if eligible inactivity has occurred

**Account screen:**
- Timer is cancelled while Account is visible
- Return to any content tab → restart timer fresh

**Player:**
- Timer is cancelled during playback (player overlay covers idle)
- Player exit → restart timer for the underlying tab (content or discovery layer)

**Window focus loss and regain:**
- Focus loss: Timer pauses (does not advance), rotation stops (if idle is active)
- Focus regain while idle is active: Rotation resumes immediately; timer remains paused (idle is already "triggered", do not restart)
- Focus regain while timer is counting: Timer resumes; rotation does not start (timer has not yet expired)
- Focus regain during fade-in/fade-out: Allow animation to complete, then apply above rules

#### Race conditions and prevention
- Do not start multiple timers or rotation coroutines (use actor/singleton for timer state)
- If idle timer expires while a dialog is open, defer idle entry until the dialog closes (do not show idle overlay yet)
- If input occurs within 100ms of idle transition, cancel idle entry and restart timer
- If player entry occurs during idle fade-in (< 400ms), cancel fade and show player immediately
- If window focus loss occurs during fade-out, stop animation and freeze at current opacity
- **Focus regain behavior (explicit):**
  - If focus regain occurs while idle is already active (rotation visible): immediately resume rotation from where it paused, do NOT restart the 5-minute timer (idle is already "triggered")
  - If focus regain occurs while timer is counting down (before idle): do NOT restart the timer; timer continues from where it was paused
  - If focus regain occurs while idle is fading in/out: allow fade to complete, then apply above rules

#### Trace events (all mandatory for verification)
- `idle timer start(reason=<sign_in|input|player_exit|return_from_account>)`
- `idle timer restart(reason=<input_type>)`
- `idle timer cancel(reason=<player_entry|window_focus_loss|dialog_open|account_screen>)`
- `idle timer pause(reason=dialog_open)`
- `idle timer resume(reason=dialog_close)`
- `idle entered(from_screen=<home|movies|series|livetv|browse>)`
- `idle exited(reason=<input_type>)`
- `idle rotation start`
- `idle rotation advance(item_index=N, timestamp=<ms>)`
- `idle rotation cycle restart`
- `idle rotation stopped(reason=<input|window_focus_loss|player_entry>)`
- `discovery entered(after_signin=<true|false>)`
- `discovery exited(to_screen=<home|movies|series|livetv>)`

---

## Decision 6: Artwork Loading and Caching

### Design Choice
- **Pre-fetch strategy:** Load artwork for all 10 discovery items when entering discovery layer (on launch/sign-in); do not defer to idle
- **Caching:** Use existing `ArtworkImage` composition caching; store scaled backdrops (16:9, ~1280x720 or native viewport) in memory cache
- **Fallback for missing artwork:**
  - If backdrop URL 404s or fails: Show solid color gradient (dark charcoal to black, 16:9 aspect)
  - If title or type label fails to load: Show placeholder text ("Unknown title", "Media")
  - If all 10 items lack artwork: Still show discovery layer (backdrops are dark gradients, not a blocker)
- **Network unavailable:** If catalog sync fails or network is unavailable at app launch, use cached artwork from last session; if no cached session exists, skip discovery layer
- **Loading states:**
  - Entry discovery: Show loading spinner at center while artwork loads; do not show discovery layer until all 10 backdrops are cached or timed out (5s timeout, then show discovery with cached/gradient fallback)
  - Idle rotation: No explicit loading state; rotate through cached artwork without waiting; if next item's artwork is not yet loaded, continue rotation and load in background

### Observable Acceptance Criteria
1. Artwork loaded: All 10 backdrops visible in discovery (screenshot shows no placeholder boxes)
2. Cache hit: Reopen app → discovery layer artwork loads instantly (no spinner) (verified with trace timestamps)
3. Missing artwork fallback: If URL fails, gradient background visible instead of error (screenshot)
4. Offline fallback: Close network, reopen app → discovery uses cached artwork or shows gradient (no crash, artwork UI readable)
5. Loading spinner: First-time discovery shows spinner for ~2s (or until artwork loads), then transitions to discovery
6. Idle rotation: Cards advance without loading delays; artwork is pre-cached before idle starts
7. Timestamp label: Shelf is labeled "Recently added" if `added` timestamp is trustworthy; "Recently indexed" if missing, malformed, or placeholder (screenshot verification)

---

## Decision 7: OLED Burn-in Mitigation (Detailed)

### Design Choice
- **Content rotation:** Cycle through all 10 items (avoids static image persistence)
- **Text position alternation:** 
  - Odd-numbered items (1, 3, 5, 7, 9): Title + label at bottom-left (24px margins)
  - Even-numbered items (2, 4, 6, 8, 10): Title + label at bottom-right (24px margins)
  - Prevents persistent pixel aging in one screen region
- **Brightness variation:** Dim all items to 40% opacity uniformly (no additional per-item brightness variation; simplifies rendering and maintains consistent readability)
- **No static UI:** Do not display any static logo, watermark, status bar, or background while idle is active (only rotating backdrop + alternating-position title/label)
- **Rotation speed:** 8s per item (slow enough to read, fast enough to move across screen regions frequently)

### Observable Acceptance Criteria
1. Position alternation: Screenshots of cards 1, 2, 3 show title at left, right, left respectively (verify pixel coordinates in UI dump)
2. No static elements: Idle screen shows only rotating backdrop + moving title/label; no logo, watermark, or status bar (screenshot verification)
3. Opacity consistent: All idle cards at 40% opacity (no flicker or brightness variation between items) (screenshot with gamma/brightness analysis if needed)
4. Readability: Text is readable over light and dark backdrops at 40% opacity (subjective; test with real data)

---

## Implementation Boundaries

### In Scope (16e)
1. Discovery entry layer: shown on launch/sign-in, with backdrop, title, label, and tap routing
2. Idle state: dimmed discovery with rotation, 8s per item, cross-fade transitions
3. Timer state machine: start/cancel/pause rules for all screen contexts
4. Wake semantics: input types and consumed/replayed behavior
5. OLED mitigation: content rotation + text position alternation
6. Artwork loading and caching: pre-fetch, fallback, network-unavailable handling
7. Trace events for all state transitions

### Out of Scope
- Favorite folders (separate D-Desktop-16x)
- Continue Watching query refinement (D-Desktop-16f)
- Account screen redesign (separate item)
- Player full-screen defaults (D-Desktop-15/17)
- Suggestions shelf (separate; 16 is entry/idle only)

### Dependencies
- 16b: BrowseSavedState for tab restoration
- Catalog schema: must provide a query for "ten most recently added Movies+Series" sorted by local `added` timestamp (derived from provider timestamp), descending; must support placeholder detection per provider
- Shell architecture: timer must integrate with DesktopShell without race conditions

---

## Test Plan (Codex to verify post-implementation)

### Acceptance evidence types
1. **Screenshots:** Entry layer backdrop, title, label; idle dimmed with text position alternation (3+ cards)
2. **Trace logs:** All events listed in Decision 5 (timer start/restart/cancel, idle enter/exit, rotation advance/cycle)
3. **Timestamps:** Exact ms between rotation advances (8s ± 200ms tolerance), fade-in/out timing (verify logs)
4. **UI dump:** Card scroll position, selected card ID before and after wake (verify restoration)
5. **Artwork inspection:** Confirm all 10 items loaded, no 404s, fallback shown for missing artwork
6. **Network state:** Test with network disabled/enabled; verify fallback behavior
7. **Input response:** Measure time from input to idle exit (should be <50ms; verify with trace)

### Edge cases to exercise (with evidence)
1. Sign-in path: Complete sign-in → discovery appears with correct content (screenshot)
2. Launch path: Open app → discovery appears with saved backdrop (screenshot)
3. Timestamp label: Catalog with trustworthy `added` timestamps shows "Recently added"; with missing, malformed, or provider-marked placeholder shows "Recently indexed" (screenshot)
4. Idle rotation: 5 minutes idle → rotation starts, advance every 8s, position alternates (traces + screenshots)
5. Mouse wake: Move mouse during idle → exit <50ms later (trace timestamp)
6. Navigation wake+replay: Click Browse rail during idle on Movies → exit AND navigate to Browse with saved state (screen transition screenshot + UI dump showing filter/query/scroll restored)
7. Browse idle and wake: Enter idle from Browse → rotation visible; click Movies rail → exit idle → Movies opens; return to Browse → filter/query/scroll are still saved (traces + UI dump)
8. App dialog wake: Open Account/settings dialog during idle → idle wakes, dialog is shown over last tab (not over idle overlay)
9. System dialog during idle: System file picker appears during idle → idle does not wake; rotation pauses, resumes when dialog closes (screenshots of both states)
10. Idle deferred by dialog: Open dialog before 5-minute timer expires → dialog visible; close dialog at 5-minute mark → idle enters (traces show idle deferred until dialog closes)
11. Player entry during idle: Start playback → player covers idle immediately (no idle visible)
12. Focus loss during idle: Minimize app → rotation stops, trace shows `idle rotation stopped(reason=window_focus_loss)`
13. Focus regain during idle: Minimize, then restore while idle active → rotation resumes immediately, timer stays paused (traces show rotation advances, no timer-restart event)
12. Offline catalog: Close network, reopen app → discovery shows gradient fallbacks (screenshot)
13. Artwork failure: Mock 404 on one URL → gradient shown for that card, rotation continues (screenshot)
14. Account screen: Navigate Account during idle timer → timer cancels; return to Movies → timer restarts (traces)
15. Tab restoration: Open Series tab, navigate Browse, idle, wake → return to Series tab at saved position (UI dump)
16. Detail/picker entry during idle: Open series detail while idle → detail covers idle (screenshot); close detail → idle resumes if still eligible
17. Player full-screen during idle: Player enters full-screen → idle covered (screenshot); exit full-screen → idle resumes if still eligible

### Context ceiling
Target Codex context under **25%** for this test pass.

---

## Files to create/modify

- **New:** `DiscoveryController.kt` — state machine for entry/idle (timer, wake, restoration)
- **New:** `DiscoveryScreen.kt` — Composable for backdrop + title/label, dimmed version
- **Modify:** `DesktopShell.kt` — integrate discovery controller, route discovery display
- **Modify:** `DesktopCatalogRepository.kt` — add "recently added Movies+Series" query if not present
- **Modify:** `DesktopTheme.kt` — confirm title/label text styles, colors, opacity handling
- **Trace:** Integrate trace events into controller (use existing shell trace system)

---

## Resolved questions
1. **Visual:** Backdrop-based discovery layer (immersive, not logo-focused) ✓
2. **Idle appearance:** Dimmed version of discovery (40% opacity, rotating) ✓
3. **Content:** Ten most recently added Movies+Series (not __featured) ✓
4. **Labels:** Title + content-type ("Movie" or "Series") ✓
5. **OLED:** Position alternation + content rotation ✓
6. **Wake:** Input-type-specific consumed/replayed behavior ✓
7. **Timer:** Full lifecycle rules for all screen contexts ✓
8. **Artwork:** Pre-fetch, caching, fallback strategy ✓
