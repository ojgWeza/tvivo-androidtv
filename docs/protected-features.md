# Protected features (Android TV)

Agreed with the owner 2026-10-31, after the build following `c58cb01` (`keep/android-good-c58cb01`) left TV folders, categories and search unreachable. Reference build: `tvivo-android-finalize-parity-c58cb01.apk`.

## Rules

1. **A protected feature must keep working in every build.** Adding features never excuses breaking one.
2. **Automatic tests block the build.** Each entry below needs a TV test (JVM remote-navigation tests plus a short emulator run). If any fails, no APK is released to the owner.
3. **Conflicts go to the owner.** If a new feature would break a protected one, stop and show the owner the trade-off. Never ship it quietly.
4. **Only the owner or an Opus review may add, change or retire entries.** Opus retires only clearly obsolete entries, and the owner is told afterwards. Other sessions propose and never apply.
5. **The list grows.** The owner adds points as they come up.

## Protected areas

Each entry's exact behaviour and its test are to be written against the `c58cb01` behaviour. Until a test exists, mark it `test: pending` and treat it as a gap.

| # | Area | What must hold on TV | Test |
|---|------|----------------------|------|
| P1 | Folders and categories | Landing folders and the category rail are visible; every category is D-pad reachable and opens its grid. | pending: partial Compose coverage; see below |
| P2 | Search | The EN/AR TV keyboard is D-pad operable, results receive focus, and OK opens a result. | pending: keyboard component coverage only; see below |
| P3 | Playback and player | D-pad/OK transport supports play/pause, seek, resume, track selection, and live channel switching. | pending: overlay focus coverage only; see below |
| P4 | Continue, favourites, My Tvivo | Continue, favourites, and For You shelves are visible when populated, reachable, openable, and reflect playback/favourite updates. | pending: policy coverage only; see below |
| P5 | See all / full folder lists | Every shelf with a full list has a "See all" target reachable by remote (Up from a card, or Down to it), OK opens the full list, Back returns to the same shelf. Applies to For You, Movies, Series, Live landing shelves and search groups. | pending: ShelfRow header focus exists in code, no test; owner reports it unreachable on the 21 Oct build (1f83a1f) |
| P6 | Arabic search input | The TV search offers an Arabic keyboard that works with the remote and does not depend on the TV having an Arabic system keyboard; Arabic text finds Arabic titles (diacritics and alef forms normalised). | pending: in-app EN/AR keyboard exists at HEAD, not in 1f83a1f (system IME); no end-to-end test |

## P1 — Folders and categories

**Pass criteria (c58cb01 behavior):** On the TV landing screen, the content folders/categories are visible without opening a hidden menu. From the initial category-rail focus, one `Right` reaches the first grid card; one `Left` returns focus to the selected category row. `Up` from the first category row reaches the category filter, and `Down` returns into the category list. Every category row, including rows beyond the initially visible viewport, can be reached by repeated `Up`/`Down` (the rail scrolls the focused row into view); one `OK` on a category selects it and shows that category's items. The category name remains readable (wraps rather than truncating), and a focused item is visible. The landing virtual folders (including All, Recently Added, Continue Watching and Favourites when available) each open a content grid by `OK`.

**Proposed JVM test:** Extend `BrowseDpadComposeTest` with a several-viewport category fixture. Assert initial focus, the one-press rail↔grid transitions, filter reachability, scroll/focus of the last category, and OK selection updating the visible grid. Add a landing-harness Compose test for each virtual folder's open action and visible destination. Existing coverage: `BrowseDpadComposeTest.rightEntersGridAndLeftReturnsToSelectedRailRow`, `upFromFirstRailRowReachesFilter`, and the unattached-grid guard; `CategoryPickerModelTest`, `CategoryPickerStateTest`, `CatalogLazyKeysTest`, and `BrowseSnapshotTest` cover model/state/key/render details, not full remote-driven landing-folder selection. Missing: end-to-end rail scrolling + category activation; virtual-folder D-pad open; category label wrap/visible assertion; actual category list/grid integration. The fixture and keyboard-mode setup in `TvJvmComposeTestSupport` can support this without a provider or stream.

**Emulator smoke (`tvivo_lite_tv`):** Starting at the landing screen, verify folders and categories are on screen; D-pad to the last category and confirm it scrolls into view, press `OK`, and confirm its grid/title. Press `Left` and confirm focus returns to that category. Open Continue Watching/Favourites if populated and confirm each destination renders.

**HEAD hint:** `c58cb01`'s `CategoryRail.kt` is a rail/list implementation. HEAD changes `BrowseScreen.kt`, `CategoryRail.kt`, and adds picker/navigation machinery (`CategoryPickerOverlay`, `TvRemoteNavigation`), while changing the landing/grid composition substantially. These are the areas to compare if reachability regresses; this note does not claim a specific breaking commit.

## P2 — Search

**Pass criteria (c58cb01 behavior):** Search is reachable from the TV shell by D-pad and `OK`. On entry, one key on the TV keyboard owns focus (the software IME does not obscure the TV controls). Every EN and AR character/action key is reachable using directional presses, visibly focused, and activated by `OK`; switching language changes the displayed alphabet without losing the query. `Backspace`, `Clear`, and `Done` work. From the last keyboard row, one `Down` reaches a result when results exist; results remain visible/focusable, directional movement reaches another result, and one `OK` opens the focused result. Empty results leave the keyboard usable and show an empty state. The same criteria apply when layout direction is RTL.

**Proposed JVM test:** Add a `SearchScreen` Compose harness with fixture results and shell callback. Drive the keyboard in EN and AR with key events, assert query text and language switch, then assert `Down` from the bottom row focuses a result and `OK` invokes the open callback. Include empty-results and RTL cases. Existing coverage: `TvSearchKeyboardComposeTest.dpadMovesAcrossKeyboardAndDoneMovesFocusToResults` checks a component key move and Done focus transfer; its four EN/AR snapshots only check rendering. `TvSearchKeyboardTest` and `SearchGridLayoutTest` cover keyboard navigation math/layout. Missing: full `SearchScreen` integration, EN/AR character entry via D-pad, actual result focus/OK open, empty-state interaction, and RTL D-pad behavior.

**Emulator smoke (`tvivo_lite_tv`):** From the landing screen open Search with the remote; enter a short English query, move Down from the keyboard to a result and open it. Repeat with Arabic after switching language. Verify focus remains visible and Back returns from the opened item to search.

**HEAD hint:** `c58cb01` has no equivalent dedicated `TvSearchKeyboard.kt` in the inspected app path; HEAD adds that TV keyboard and substantially rewrites `SearchScreen.kt` and `AppShell.kt`. Test the integration boundary (shell → keyboard → result → open/return), not only the new keyboard's isolated geometry.

## P3 — Playback and player

**Pass criteria (c58cb01 behavior):** A focused playable item starts with `OK`. In the player, `OK` toggles play/pause and the focused transport buttons show visible focus. For seekable VOD, `Left`/`Right` seeks backward/forward by the displayed increment (10 seconds per press); the position/time display updates and remains within the item bounds. Reopening a partially watched item offers Resume and Start Over; `OK` on Resume starts at the stored position, and `OK` on Start Over starts at zero. Audio and subtitle menus are reachable by D-pad; `OK` selects a track and the selected label/state updates (subtitle Off is selectable). For live playback, channel up/down controls switch to the adjacent channel and `OK` on a channel starts it. Back exits player and returns to the previously focused item. Tests must use fake callbacks/state and must never open a provider stream.

**Proposed JVM test:** Expand `PlayerOverlayComposeTest` with key-driven assertions for play/pause, seek callbacks/position, audio/subtitle menu open/select/Off, and live channel controls; add a browse/detail Compose route test for `OK` start and return focus. Add a ResumePrompt Compose test asserting Resume passes the saved position and Start Over passes zero. Existing coverage: `PlayerOverlayComposeTest.hiddenOverlayOwnsFocusAndVisibleOverlayFocusesPlaybackControl`, `visibleBackButtonInvokesBackCallback`, and `playerSeekEpisodeAndStartOverButtonsRemainAvailable` check focus/back/existence; `SleepTimerUiPolicyTest`, `PipPolicyTest`, and `PipPlaybackActionTest` cover separate policies. Missing: functional D-pad transport callbacks and position changes, resume/start-over prompt activation, audio/subtitle selection, live channel switching/OK-to-play, and end-to-end return focus. Keep all test fixtures offline and do not execute playback.

**Emulator smoke (`tvivo_lite_tv`):** With an owner-approved local fixture source only, play one VOD item, pause/resume, seek both directions, inspect audio/subtitle choices, exit and reopen to check Resume/Start Over; switch between two fixture live channels and verify `OK` starts the focused channel. Do not use the real provider account for automated tests. This is a proposed manual smoke step, not run in this documentation task.

**HEAD hint:** `c58cb01` playback service/command changes are visible in `git show c58cb01:...`; HEAD changes `PlayerActivity.kt` and `PlayerOverlay.kt` extensively and adds PiP/sleep-timer policies. Preserve the baseline transport and track-selection paths while reviewing the newer controls.

## P4 — Continue, favourites, My Tvivo

**Pass criteria (c58cb01 behavior):** On My Tvivo, each non-empty Continue Watching, Favourites, and For You shelf is rendered as a visible titled row with at least one visible card. From the initial TV focus, repeated `Down` reaches each non-empty shelf row and `Right`/`Left` moves among visible cards; focus must scroll the selected card into view. `OK` opens the selected item. A partially watched VOD card resumes from its saved position; changing favourite state through the item's context action updates the Favourites shelf on return/refresh, and completed/cleared resume state removes the item from Continue Watching. Empty shelves are omitted (or show the specified empty state) without trapping focus. Returning from item detail/player restores focus to the same card when it remains present.

**Proposed JVM test:** Add a Home Compose test harness backed by deterministic fake shelves/playback state. Assert populated shelf visibility and labels, D-pad navigation across shelf rows/cards, `OK` open callback, favourite toggle causing membership update, resume progress/action, empty-shelf behavior, and focus restoration after return. Existing coverage: `MyTvivoHomeTest` checks pure first-paint/merge/progress/shelf policies; `HomeRouteTest` checks home tile route selection; `ShelfPageNavigationTest` covers shelf-page navigation policy. These do not compose `HomeScreen` or drive D-pad. Missing: HomeScreen shelf visibility/focus, `OK` activation, favourite and Continue membership updates, resume callback/position, and return-focus restoration.

**Emulator smoke (`tvivo_lite_tv`):** With seeded fixture playback/favourite data, navigate from the first shelf to Continue Watching and Favourites using only D-pad; open a card, return, and confirm the same card is focused. Toggle a fixture item's favourite state and confirm it appears/disappears from Favourites; resume a partially watched item and confirm playback starts at the saved position.

**HEAD hint:** `c58cb01`'s `HomeScreen.kt` is the baseline source. HEAD modifies it substantially to assemble the shelf landing and focus restoration; `HomeRepository`/`PlaybackStateRepository` data feeds are part of the visible behavior. Existing policy tests establish some shelf rules, but there is no app-level HomeScreen D-pad Compose test in the inspected test tree.

## P5 and P6 (added 2026-10-31 at the owner's request)

- **P5 See all:** pass criteria as in the table. Needs a Compose test on ShelfRow with a fake shelf: Up from the first card focuses the header, OK invokes the see-all callback, Back restores card focus. Emulator smoke: open See all on a Movies and a Series shelf with the remote.
- **P6 Arabic input:** pass criteria as in the table. Needs a test that switches the TvSearchKeyboard to Arabic, types a word with the remote and runs the search over Arabic fixture titles; the mock provider (tools/mock-provider) needs a few Arabic-titled items.

## Open items

- Implement the missing JVM TV navigation/integration tests listed under P1–P4; until then, keep each entry marked `pending` and block release on the gap.
- Find the commit that broke P1 and P2 (bisect pending a Loopa slot).
