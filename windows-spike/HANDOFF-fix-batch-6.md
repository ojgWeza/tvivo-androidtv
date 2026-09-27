# Fix batch 6 handoff

Only the playback handoff, description flyout, and requested handoff note were edited for this batch. No build, test, app launch, install, process control, or deployment was performed.

## VERDICT

The P0 regression has a confirmed source-level cause and a targeted fix. `PlaybackHandoff.RunAsync` uses `ConfigureAwait(false)` and waits 350 ms, so its stop/start delegates resume away from the UI thread. The stop delegate then called `PlayerPage.UpdateLayout()`, and `PlaybackService` also discarded the caller context before it entered either engine. That path can fail with `CO_E_NOTINITIALIZED` before an engine gets as far as opening a movie, live stream, or episode. Both handoff delegates now dispatch to the window's `DispatcherQueue`; `PlaybackService` and engine awaits retain that UI context. LibVLC `MediaPlayer.Play()` now runs on that dispatcher too.

The artwork source path contains no source-level rendering blocker. Decode assigns the `BitmapImage` to the visible `Image`; its `ImageOpened` handler sets opacity to 1 and collapses the placeholder. A decoded log line alone does not prove this handler ran. Existing `Artwork rendered` diagnostics identify items for which it did. No artwork code change was justified from source inspection alone.

The About flyout now has an explicit 640×520 content viewport, a wider presenter limit, and vertical scrolling for long descriptions.

## STRONGEST EVIDENCE

- The live symptom is consistent with the source path: the 350 ms delay is in `PlaybackHandoff`, which resumes through `ConfigureAwait(false)`. The subsequent stop callback awaits tasks, yields, and touches `PlayerPage.UpdateLayout()` before start. `PlaybackService` also used `ConfigureAwait(false)` around its gate and engine calls.
- `CO_E_NOTINITIALIZED` can arise from that off-dispatcher WinUI call. The exception is caught around the full handoff and reported as `Playback start failed`, so an exception from `UpdateLayout` can be reported as a failed playback attempt before an engine starts.
- `OnUiAsync` now queues both the stop/layout callback and the start callback on `WindowRoot.DispatcherQueue`. The service keeps that context across its gate and calls. The Windows playback engine uses context-preserving awaits, and LibVLC's `Play()` call is no longer moved to `Task.Run`.
- For artwork, `StartArtwork` checks that its load is still current before assigning `image.Source = bitmap`. `ArtworkImage_Opened` verifies the same bitmap is still assigned, reveals the image, collapses `ArtworkFallback`/`SpotlightArtworkFallback`, and logs `Artwork rendered`. The 10-second expiry removes only the load bookkeeping; it does not clear the source.

## Root causes and changes

1. **Playback apartment regression — confirmed in source (high confidence).** The close-delay continuation escaped the UI context. The handoff callback then called a WinUI layout API off-thread, and the playback service could enter either engine off-thread as well. Both callbacks now go through the window dispatcher. The service preserves its caller context, so Native MediaPlayer/FFmpeg startup and LibVLC setup/`Play()` execute from the UI apartment. Existing LibVLC event monitoring remains asynchronous. No headless test can prove WinUI apartment affinity without a dispatcher; no test was added. The code comment and this note record the intended affinity.
2. **Artwork display — no source-level defect found (high confidence in source path; live paint unverified).** The image is layered above the placeholder. On the matching `ImageOpened` event it becomes opaque and the placeholder is collapsed. Stale loads are rejected before assignment and virtualization unload clears the old source. A successful decode log without a matching `Artwork rendered` line is not enough to establish that the visible control opened the image. Missing or invalid absolute provider URLs correctly retain the placeholder. No artwork code was changed.
3. **About flyout clipping — source-level layout cause addressed (medium confidence; live layout unverified).** The prior content had only maximum dimensions and relied on the default FlyoutPresenter sizing, allowing an undersized presenter/content measure for long plot or cast text. The presenter now allows 680 px width (up to 760 px), the content viewport is explicitly 640×520 (bounded to the available maximum), and horizontal scrolling is disabled while vertical scrolling remains available.

## Files changed

- `src/Tvivo.Core/PlaybackService.cs` — preserve dispatcher context through playback engine calls.
- `src/Tvivo.App/MainWindow.xaml.cs` — marshal delayed handoff stop/start callbacks through `DispatcherQueue`.
- `src/Tvivo.Playback/WindowsPlaybackEngine.cs` — invoke `MediaPlayer.Play()` on the dispatcher context.
- `src/Tvivo.App/MainWindow.xaml` — set flyout presenter and scroll viewport dimensions.
- `HANDOFF-fix-batch-6.md` — this report.

## EXACT BOUNDARY

Batch 6 made only the changes listed above. No build, test, app launch, install, process control, provider request, or deployment was performed. Existing unrelated/uncommitted work from earlier batches remains in the working tree and was not attributed to this batch. No test was added because asserting the WinUI dispatcher apartment requires a UI dispatcher unavailable to headless tests.

## RULED OUT

- The 350 ms delay is not itself an engine-construction delay on this path; it is the handoff wait after which its continuation loses UI affinity.
- The playback attempt can fail before an engine's `StartAsync` runs: `PlayerPage.UpdateLayout()` is called in the stop callback, before the start delegate.
- The artwork placeholder is not left above an opened image by XAML child order: the image is above the fallback, and the open handler explicitly collapses the fallback.
- Artwork expiry does not reset `Image.Source`; it removes the active-load entry only. Data-context changes and unloads deliberately restart or clear recycled images.
- Items without an absolute HTTP/HTTPS artwork URL are intentionally left on the placeholder.

## UNCERTAINTY

- The source path strongly matches the reported HRESULT and timing, but no new live trace or stack trace was collected. Confirm that playback now starts for a Movie, Live item, and Episode on both playback engines.
- `ImageOpened` and its `Artwork rendered` log establish that the image was opened and made visible by the handler; only live inspection can confirm the final pixels are unobscured in the actual window. For each decoded item, compare its decode and rendered log entries.
- XAML sizing behavior depends on WinUI's live flyout placement and available window bounds. Long plot/cast content still needs visual verification with vertical scrolling.
- No build or tests were run, so C# and XAML compilation are unverified.

## LIVE-VERIFICATION LIST

- Try a Movie, Live stream, and Episode in both Native and LibVLC modes. Confirm no `0x8001010E`, that each selected item reaches its expected startup result, and rapid consecutive starts still respect stop/close serialization.
- Check launch logs for a start/result pair for each case. If any fails, capture its HRESULT and engine startup log to locate the first failing call.
- For an item with a valid absolute artwork URL, match `Artwork downloaded and decoded` with `Artwork rendered`, then inspect the visible card/spotlight. Check a virtualized card after it unloads and is reused. Confirm invalid/missing URLs remain on the placeholder.
- Open a title with long plot and cast text. Confirm the flyout is wide, text wraps, all content is reachable by vertical scrolling, and the flyout stays inside the window.

## NEXT GATE

Owner performs one live verification session covering both engines and all three stream kinds, a decoded artwork item, and long About content. This batch did not launch or test the app.
