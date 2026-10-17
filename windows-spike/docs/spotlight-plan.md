# Spotlight catalogue strip - plan (Sol, 2026-10-16)

Status: awaiting owner approval. Nothing built.
## Spotlight plan — 10-line summary

1. Replace the pager with one horizontal catalogue strip of up to **12 artworks**.
2. The leftmost card opens on first entry; the others remain visible as narrow artwork slivers.
3. Pointer hover opens a card after **350 ms**; keyboard focus opens it immediately.
4. Left/Right moves through the strip, Enter activates the open card, and Tab leaves the strip.
5. Movies draws only movies; Series draws only series; For You alternates movies and series. Live TV has no Spotlight.
6. Selection stays stable for each account, page, source setting, and UTC day. Remove the eight-second timer.
7. Default source is **Recently added**; users can instead select multiple folders separately for Movies and Series.
8. Fetch summaries only when a card is open and visible, then retain them in the existing metadata cache.
9. Animate only the two cards changing width; switch immediately when Windows animations are disabled.
10. Implement data policy, settings, strip, and accessibility in ordered Luna slices, then verify performance and input on a running app.

### Data facts and the summary count

The current Spotlight uses one `CatalogCard`, previous/next controls, six warmed artworks, and an eight-second timer in [CatalogLandingPage.xaml.cs](D:/HCode/Tvivo/windows-spike/src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs:2668). Its discovery query excludes visited, favourite, and resumed items and can include Live TV; that is a separate source policy from the new default in [SqliteCatalogRepository.cs](D:/HCode/Tvivo/windows-spike/src/Tvivo.Infrastructure/SqliteCatalogRepository.cs:594).

**Why the cached-summary count was low, in two lines:** Catalog indexing creates titles and artwork without fetching every title’s detail record. Summaries arrive through the hover/focus metadata lookup and are cached only for titles people have encountered; the owner’s experience of seeing hundreds is consistent with that behavior. The existing lookup and SQLite metadata fields support this in [HoverMetadataLookup.cs](D:/HCode/Tvivo/windows-spike/src/Tvivo.Infrastructure/HoverMetadataLookup.cs:1) and [SqliteCatalogRepository.cs](D:/HCode/Tvivo/windows-spike/src/Tvivo.Infrastructure/SqliteCatalogRepository.cs:140).

Reuse that lookup and cache. An open, visible card first displays cached text, if present; otherwise it requests metadata after a brief settled-focus interval. Cancel or ignore a superseded request when the user moves away. Do not fetch summaries for the eleven folded cards, prefetch a whole category, or treat an absent summary as evidence that the catalog has none. If a provider returns no plot, show “Summary unavailable” without invented copy.

### Strip layout and input

Use a **320-DIP-high** strip. Give the open card a responsive width of `clamp(480 DIP, 42% of strip viewport, 672 DIP)`. Its poster is **164 × 246 DIP** at the minimum width and grows to at most **190 × 285 DIP**; place text beside it with at least 20 DIP separation and 16 DIP outer padding. Every folded card is **64 DIP wide**, with an **8-DIP gap**. Crop its artwork consistently and keep the entire 64 × 320 DIP surface clickable. Title is at least **22 DIP**, summary **18 DIP**, and year/genre **17 DIP**. Limit the on-card summary to four lines with a visible “More details” route; expose its full text in details and to assistive technology.

Keep all 12 cards in **one logical row**. When they exceed the viewport, use horizontal scrolling with a visible scrollbar and an “item 1 of 12” position label. Do not reduce the daily selection count to fit the window. Opening or focusing an offscreen card scrolls it fully into view; preserve a neighboring sliver where space permits. A pointer can drag/scroll the row, and touch can swipe it. The strip is hidden in a full category grid or search result, as Spotlight is today.

| Effective strip width | Open width | Folded cards fully visible beside it | Total width for 12 | Policy |
| --- | ---: | ---: | ---: | --- |
| 776 DIP minimum | 480 DIP | 4 | 1,272 DIP | Scroll horizontally |
| 1,200 DIP | 504 DIP | 9 | 1,296 DIP | Scroll the last portion |
| 1,600 DIP | 672 DIP | All 11 | 1,464 DIP | All fit |

Sizes are in WinUI DIPs. At the **776-DIP effective minimum**, 100%, 150%, and 200% scaling require approximately **776, 1,164, and 1,552 physical pixels** respectively; the 480-DIP open card becomes 480, 720, and 960 physical pixels, and the 64-DIP sliver becomes 64, 96, and 128. Recalculate using actual viewport DIPs after resize or monitor-DPI changes. If the OS permits a window narrower than 776 DIP, keep a 420-DIP open-card floor and scroll the row; never shrink the summary type or sliver target.

- First entry opens the **leftmost** item. Returning from details restores the focused item when possible; a fresh page/day starts at the left.
- Hover starts a **350 ms** dwell timer. Leaving before it expires does nothing; crossing the strip while scrolling therefore does not unfold successive cards. Focus, touch, and click open immediately.
- Left/Right changes focus and opens the adjacent item, with no wrap at either end. Enter/Space activates the open card: movie follows the existing movie activation path; series follows the existing series details path. Clicking a folded card opens it; clicking an open card activates it. Tab and Shift+Tab leave the strip in normal page order. Escape does not trap focus.
- Display a clear “Select to open; select again to play/view” hint on touch-capable layouts, because folded artworks alone do not explain the two-click rule.

**Motion:** Use a **220 ms ease-out** width transition on the old and new open cards only, plus a **120 ms** opacity/clipped reveal for text after enough width is available. Keep artwork anchored and avoid a flash from clearing and reloading the image source. Do not add a page flip in the first implementation: perspective rotation adds clipping, text readability, and focus-ring risks for little navigational value. A later clipped slide can be tried only if it clears the performance and Narrator checks. WinUI can storyboard `Width`, but Microsoft identifies it as a layout-dependent animation that uses UI-thread work; restrict it to these two elements and measure it. Composition opacity/translation is appropriate for the small reveal. There is no looping motion or timer. Read `UISettings.AnimationsEnabled` and react to changes; when false, set final widths and opacity immediately. [Microsoft’s animation performance guidance](https://learn.microsoft.com/en-us/windows/apps/develop/performance/optimize-animations-and-media) and [UISettings documentation](https://learn.microsoft.com/en-gb/uwp/api/windows.ui.viewmanagement.uisettings.animationsenabled) support those choices.

**Accessibility:** Each card needs an automation name containing its title, type, position, and **“expanded” or “collapsed”** state; update that state when it changes. Use a single tab stop for the strip with arrow-key movement among cards, a **3-DIP high-contrast focus ring** on the focused artwork, and a visible focus treatment distinct from hover. Raise one polite announcement such as “Title, expanded, 4 of 12” when keyboard focus opens a card; avoid repeating it when focus itself already gives an equivalent announcement. Prefer a custom automation peer exposing ExpandCollapse and Invoke if the stock button peer cannot represent the two actions accurately; verify the actual Narrator output rather than assuming attached names suffice. WinUI documents [ExpandCollapse](https://learn.microsoft.com/en-us/windows/apps/design/accessibility/control-patterns-and-interfaces) and [custom automation peers](https://learn.microsoft.com/th-th/windows/apps/design/accessibility/custom-automation-peers). Test High Contrast and 200% text/scaling with long titles and missing artwork.

### Selection and sources

Recommend **12** because it exposes meaningful variety while keeping the strip to twelve image elements, a small focus set, and at most eleven slivers. Selecting 20 would make reaching the end tedious at 776 DIP; six would offer little benefit over the old carousel. Render fewer when fewer eligible titles exist.

- **Movies:** 12 movie candidates only. **Series:** 12 series candidates only. **For You:** six of each, interleaved Movie, Series, Movie, Series. If one type has fewer than six, borrow its unused slots from the other; never insert Live TV. No ratio control initially.
- **Recently added, default:** use the existing bounded, deduplicated recently-added retrieval as the candidate pool. Rank within a recent pool with a seeded, recency-weighted selection so the strip changes by day without scanning or shuffling the full catalog. An item’s displayed position stays fixed all day.
- **Specific folders:** store selected **category IDs per content type**, not labels. Take eligible titles round-robin across ticked folders so one large folder cannot dominate; deduplicate by normalized title plus year where known, then fill to 12. For You applies the movie folder choice to movie slots and the series folder choice to series slots. An empty ticked folder contributes no cards. Keep a deleted folder’s saved ID and show it as “Folder no longer available” in Settings, unchecked from the effective selection but available to remove; do not silently replace its choice with another folder. If all selected folders yield nothing, show a clear empty Spotlight with a Settings action rather than unrelated content.
- **Folders you haven’t explored:** keep the existing discovery rule as a **third, opt-in source**. It already has repository support and a distinct understandable purpose; remove its Live TV path for Spotlight. Its eligibility exclusions remain specific to this mode.
- **Similar to what I watch (by folder):** **defer as a fourth source**. The proposed folder affinity can be built from visit counts later, but calling it “similar” before proving useful recommendations would overpromise. Add it only with a transparent “Based on folders you visit” label, a bounded query, and a cold-start fallback to Recently added.

Persist an ordered daily list of **type + item IDs**, keyed by an opaque account key, page, UTC date, source mode, and selected-folder fingerprint. The first list item is the default open card. A catalog refresh preserves still-present items in place, drops deleted ones, and fills gaps deterministically; a settings change makes a new selection. Persist only bounded recent days and never store provider connection details or artwork addresses in this file. Remove the old timer, indicator pager, automatic index advance, and six-image rotation assumptions.

### Settings and storage

Add a **“Spotlight” section under Settings → For You**, alongside the existing shelf controls in [AccountPage.xaml](D:/HCode/Tvivo/windows-spike/src/Tvivo.App/Pages/AccountPage.xaml:62). Show separate **Movies** and **Series** source selectors with plain labels: “Recently added”, “Specific folders”, and “Folders I haven’t explored”. For You states that it mixes the two settings evenly. Selecting “Specific folders” reveals a searchable, scrollable list of that type’s categories, each as a large checkbox row with full two-line label and item count; provide “Select all”, “Clear”, selected count, and an inline empty/deleted-folder message. Apply changes when the user presses **Save**; Cancel restores the prior selection. This avoids accidental churn while ticking several folders. Show a short preview of the effective selection without loading summaries.

Use an account-scoped, versioned JSON preference store in Infrastructure, following the atomic temporary-file-and-replace pattern of [MyTvivoLayoutStore.cs](D:/HCode/Tvivo/windows-spike/src/Tvivo.Infrastructure/MyTvivoLayoutStore.cs:1). Keep source mode and category IDs for each type; validate unknown enum values, duplicate IDs, and malformed files back to the safe Recently added default. Store daily selected IDs separately from user preferences. Use `TvivoDataPaths`, not packaged-only `LocalSettings`.

### Architecture, verification, and Luna slices

| Slice, order and effort | Ownership | Deliverable and meaningful unit tests |
| --- | --- | --- |
| **L1 — selection policy, 1–1.5 days** | Core policy types; Infrastructure repository and `SpotlightSelection` | Bounded pools, 12-item daily order, movie/series isolation, six/six borrowing, deduplication, sparse/empty catalogs, deleted IDs, UTC rollover, refresh stability. |
| **L2 — settings, 1–1.5 days** | Infrastructure JSON store; App `AccountPage.xaml(.cs)` | Versioned account-scoped preferences, atomic write/read, malformed-file fallback, per-type folders, Save/Cancel and empty/deleted folder states. |
| **L3 — strip data and artwork, 1.5–2 days** | App `CatalogLandingPage.xaml(.cs)`; existing artwork pipeline | Replace hero/pager/timer with 12-card row; preserve cached images through fold/unfold; lazy visible/open summary lookup. Test request cancellation and stale-result rejection with existing lookup seams. |
| **L4 — interaction and accessibility, 1.5–2 days** | App strip control/peer and page wiring | Keyboard boundary and Tab behavior, pointer dwell cancellation, click semantics, state names/notifications, reduced-motion switching. Keep pure input/state policy tests separate from visual checks. |
| **L5 — polish and gate, 1–1.5 days** | App sizing/motion; focused docs and existing perf harness | Verify 776/1,200/1,600 DIPs at 100/150/200%, touch, Narrator, High Contrast, missing art/plot, and 12-card memory/frame behavior. Run existing deep-scroll scenario and compare with baseline. |

That is roughly **6.5–8.5 Luna working days**, subject to the UI Automation peer and DPI checks. Core owns deterministic policy and input-state rules; Infrastructure owns bounded SQLite reads and JSON; App owns XAML, image lifetime, focus, announcements, and page navigation. Keep the existing provider metadata contract and artwork disk cache.

For performance, decode each Spotlight poster once at no more than the **190 × 285 DIP display size times rasterization scale**: at 200% this is at most **380 × 570 pixels**, about **0.83 MiB per 32-bit decoded image**, or about **10 MiB for twelve** before framework/cache overhead. Reuse the same decoded image when a sliver unfolds. Load only visible cards plus one neighbor, cap new concurrent loads at two, and cancel them when the strip leaves view. Keep the existing disk-cache budget. After the initial render, idle Spotlight should issue **zero** image or metadata requests and **zero** animation ticks. Acceptance targets: no sustained idle CPU increase; fold/unfold frame p95 under **33 ms** with no visible flash; no material regression in the existing 20-cycle deep-scroll UI-ping, working-set, or image-decode measurements. These are **targets, not measured results**. The existing [performance report](D:/HCode/Tvivo/windows-spike/perf/REPORT.md:1) records the old timer as an idle cost and provides a comparison point; its older numbers should not be treated as a current baseline without rerunning the harness.

**Honest view:** The accordion is a strong fit for browsing artwork while giving one title a genuinely readable description, and eliminating automatic rotation gives users control. Its risks are real: slivers conceal titles, hover can unfold cards while a mouse merely passes over them, touch requires a discoverable first tap, and only one summary is visible at once. The dwell delay, scrollbar/position cue, large sliver targets, keyboard names, and stable open card address those risks; a running-app test with a senior user should decide whether 64-DIP slivers communicate enough before polishing a page-flip effect.

**Remaining owner questions:** None block implementation. Recommended defaults are **12 items, equal For You mix, four summary lines, Recently added per type, and “Similar” deferred**.

This was a read-only plan. I did not edit, build, run tests, or inspect a live window; motion smoothness, Narrator speech, DPI behavior, and performance remain to be verified in implementation.

Codex session ID: 01a14231-8da6-7e42-be3a-34b02e3b0c32
Resume in Codex: codex resume 01a14231-8da6-7e42-be3a-34b02e3b0c32

### L1 status — implemented (2026-10-16)

Core now provides `SpotlightSelector` and its policy/settings/candidate types. Infrastructure adds bounded movie/series candidate queries, account-scoped versioned settings, and a bounded daily ID-list store. The legacy App Spotlight implementation and repository method remain in place for L3 replacement. Core tests pass (378); Infrastructure tests pass except five existing DPAPI tests blocked by the runner's user-profile protection context. Full solution build/test limitations and exact environment errors are recorded in the implementation handoff; App/UI behavior remains for L3-L5.

