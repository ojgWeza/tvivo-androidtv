# Fix batch 8 handoff

Code and handoff edits only. No build, test, app launch, install, playback, or live verification was performed, as requested.

## VERDICT

The 775 successful decodes and zero `Artwork rendered` events exposed a real visibility gap. The template `Image` had `ImageOpened="ArtworkImage_Opened"` in the live XAML template, and the decode path assigned `load.Bitmap` to that same `Image.Source`; the event was not missing from the control markup. However, every image started at `Opacity=0`, and the only success path that changed opacity and collapsed the placeholder was the `ImageOpened` handler. Thus the observed zero callbacks left successfully decoded images permanently hidden.

There was a second correctness gap at assignment: code checked whether the per-element load was still in `_artworkLoads`, but did not also verify that the image was loaded, attached to a `XamlRoot`, and still bound to the same card and URL. The completion path now checks those conditions before assigning. A stale decoded bitmap is cached for reuse but is not assigned to a recycled control. A valid assignment immediately shows the already-decoded bitmap and hides its placeholder; `ImageOpened` remains a separate render confirmation.

The decoded cache's 48-entry plateau was its configured capacity, not evidence by itself of a broken URL key. The lookup used exact absolute URL plus decode dimensions and returned before the HTTP request on a hit. Its FIFO eviction could nevertheless discard frequently revisited images. The cache now uses 48-entry LRU eviction and emits explicit cache-hit/cache-miss diagnostics. A 48-entry cap can still cause a miss after more than 48 distinct images have displaced an item.

The category-switch edge case was `PrepareMode` clearing the visible sources whenever `ContentState` was temporarily collapsed, even when `_renderedSnapshot` still existed. Mode transition detection also required the content to be visible, skipping the fade in that state. Both conditions now follow the retained snapshot instead, so category changes keep the current source attached and use the existing fade-out/swap/fade-in sequence.

## STRONGEST EVIDENCE

- `CatalogLandingPage.xaml` wires `Loaded`, `Unloaded`, `DataContextChanged`, `ImageOpened`, and `ImageFailed` on each card template `Image`. `StartArtwork` assigns `image.Source` on that template image. The spotlight image also wires `ImageOpened` directly in XAML.
- Before this batch, `ArtworkImage_Opened` required the matching `_artworkLoads` entry and bitmap identity, then set `Opacity=1` and collapsed the fallback. No equivalent visibility change ran after `SetSourceAsync` and `image.Source = bitmap`.
- The request path already canceled loads on unload and rebind. Its final guards checked cancellation and dictionary identity, but did not check `Image.IsLoaded`, `Image.XamlRoot`, the current card ID, or the current card URL.
- The prior cache key is `$"{uri.AbsoluteUri}|{decodeWidth}x{decodeHeight}"`; `TryGetValue` is before request queueing and the hit branch returns. The hard limit is `ArtworkBitmapCacheCapacity = 48`. Cache hits now promote entries to the front of a linked-list LRU; least-recently-used entries are evicted.
- The two category entry paths are the main-window `ShowCatalogAsync` → `PrepareMode`/`LoadAsync` path and the in-page `ModeButton_Click` → `SetModeAsync`/`ShowCachedPageAsync` path. Both reach `ApplySnapshotAsync`; the retained-snapshot checks now apply even if the content host is momentarily collapsed.
- Description availability is computed from plot/cast fields. The about icon is visible only for Movies/Series with at least one nonblank field. The flyout is 360×300, capped at 380×340, with its vertical `ScrollViewer` retained.
- Cinema cursor idle handling is limited to the player page while cinema mode is active and the engine is playing or buffering. Pointer movement shows it and resets a three-second timer; timer expiry hides it. Leaving cinema, pausing/stopping playback, and window close restore it.
- Minimize, maximize/restore, and close now use `AppCaptionButtonStyle`: transparent background, no border, compact centered glyph, and a low-opacity orange hover fill consistent with the category treatment. The global rule is recorded in `DESIGN-RULES.md`.

## EXACT BOUNDARY

- `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` — validate the current bound card and live tree before source assignment; reveal the decoded bitmap at validated assignment; retain `ImageOpened` as confirmation; log discarded stale loads and cache hit/miss; change the 48-entry cache to LRU; retain/fade category content for collapsed-host transitions.
- `src/Tvivo.App/MainWindow.xaml` and `src/Tvivo.App/MainWindow.xaml.cs` — shrink the about flyout, hide its button when plot/cast is absent, add three-second cinema cursor idle handling, and apply the caption style to the window buttons.
- `src/Tvivo.App/App.xaml` — add the shared minimal caption-button style.
- `DESIGN-RULES.md` — record the artwork assignment/cache, category transition, cursor, and caption rules.
- `HANDOFF-fix-batch-8.md` — this report.

No provider, account, stream URL, persistence, or playback-selection behavior was changed. No automated test or build was run.

## RULED OUT

- The `ImageOpened` statement was not dead/unwired XAML: the card and spotlight `Image` elements each subscribe directly to `ArtworkImage_Opened`; the assignment is made to the same control instance passed into `StartArtwork`.
- A missing cache lookup or incorrect key was not found in the source: URL plus decode size was used consistently, and a cache hit bypassed download/decode. The exact 48 plateau matches the configured bounded capacity.
- The count of 775 decodes alone does not prove that all requests missed or that the same URLs were repeatedly evicted. Prior diagnostics did not report cache misses, distinct cache keys, or hit rate. FIFO eviction was a concrete inefficiency and is replaced with LRU; live cache effectiveness still needs measurement.
- The stale-result path did cancel/drop a load when unload/rebind removed or replaced its dictionary entry. What it lacked was a final current-item and attached-tree check at assignment; the batch adds those checks and logs discard reasons.

## UNCERTAINTY

- Source inspection cannot identify why the live WinUI runtime raised zero `ImageOpened` events for those predecoded `BitmapImage` assignments. The missing event itself is not explained by absent XAML wiring. The new visible-at-assignment path no longer depends on that event, and the live check must confirm actual pixels.
- The report proves the old code lacked the current-card/tree guard, but cannot prove that any of the 775 assignments actually landed on detached or rebound images. New `Artwork assignment deferred` and `Artwork result discarded` logs make those cases observable.
- The 48-entry LRU and URL-size key have not been exercised against the live catalog; revisits outside the retained 48-entry working set will still decode again.
- The Windows `ShowCursor` P/Invoke path, XAML resource/template, pointer idle behavior, visual transitions, flyout bounds, and image pixels are unverified because no build or app launch was permitted.

## NEXT GATE

On the next authorized live verification, scroll until several cards display artwork and confirm pixels are visible (not placeholders), then scroll away and back and compare `Artwork cache hit` against `Artwork cache miss` and `Artwork downloaded and decoded`. Confirm successful `Artwork applied` lines report `loaded=true` and `attached=true`; `Artwork rendered` may confirm the separate WinUI render event. Exercise rapid scrolling/recycling and verify stale IDs log discard/defer without painting the wrong card.

Also switch Movies → Series → Live TV through both the top navigation and in-page mode buttons; confirm the old catalog remains attached during loading and each replacement fades smoothly. Check long and empty-description movies/series: the bounded flyout scrolls, and the info button is absent with no plot/cast. During fullscreen playback, leave the pointer idle for over three seconds, move it to restore it, then wait for it to hide again; confirm windowed player and catalog menus keep the cursor visible. Check minimize/maximize/close hover for the subtle orange treatment and lack of grey blocks.
