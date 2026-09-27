# Fix batch 7 handoff

Only the catalog artwork loader, About flyout dimensions, and this handoff were changed for batch 7. No build, test, app launch, install, process control, or deployment was performed.

## VERDICT

The source confirms a silent cancellation mechanism that can explain why artwork requests start without a matching completion/failure line: `ArtworkImage_Unloaded` calls `StopArtwork`, which cancels the per-image token, while `StartArtwork` swallowed every `OperationCanceledException`. The source also confirms no permit leak in its normal cancellation/error paths: cancellation had a conditional release in `finally`, and other exceptions released in `catch`. However, a stalled request could occupy one of six permits until `HttpClient`'s implicit timeout, and permit release on successful decode depended on `ImageOpened` or the later ten-second bookkeeping expiry.

Batch 7 makes those outcomes observable, releases permits from `finally` after each request/decode attempt, applies a 30-second linked cancellation timeout across response headers and body download (with `HttpClient.Timeout` also set to 30 seconds), and caches up to 48 decoded bitmaps by URL and decode size so recycled cards can reuse completed artwork. `Artwork rendered` already existed and is emitted by `ImageOpened` after the source identity check, opacity change, and fallback collapse. The About flyout is now a 460×390 content viewport in a presenter capped at 480×420, with the existing internal vertical scrolling.

The supplied live counts do not establish which requests were canceled versus still waiting, timing out, or failing before instrumentation. The explicit per-request ID diagnostics in this batch should distinguish those cases on the next run.

## STRONGEST EVIDENCE

- `ArtworkImage_Unloaded` calls `StopArtwork`; `StopArtwork` removes the load and cancels its token. Data-context changes also replace a load through `StartArtwork` → `StopArtwork`.
- `StartArtwork` previously had `catch (OperationCanceledException) { }`, so container recycling cancellation had no terminal log. `Artwork source` was deduplicated by item and URL, so its count was not a reliable count of actual request attempts.
- The previous loader used a six-slot `SemaphoreSlim`. Its cancellation `finally` released an acquired slot, and its generic exception handler explicitly released. No missing release was found in those paths. Successful requests could retain a slot while awaiting `ImageOpened`; a request stuck before decode had no explicit timeout configured in `ArtworkClient`.
- `ArtworkImage_Opened` already emitted `Artwork rendered` after confirming that the opened bitmap was still the source, making it the appropriate observable UI event. The reported zero count is not evidence that the log statement was absent from source.
- The new log records request IDs, item IDs, stage, cancellation/timeout outcome, slot acquire/release, and available permit count. Decoded-cache hits are also logged.

## EXACT BOUNDARY

- `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs` — add per-load diagnostics, guarantee permit release from `finally`, enforce the 30-second timeout across header and body reads, and add a 48-entry decoded bitmap cache keyed by URL and decode dimensions. Preserve the existing `ImageOpened` rendered diagnostic and retire per-element load bookkeeping after it fires.
- `src/Tvivo.App/MainWindow.xaml` — reduce the About flyout to 460×390 with a 480×420 presenter cap and retain vertical internal scrolling.
- `HANDOFF-fix-batch-7.md` — this report.

No provider endpoint, credentials, account data, or other product behavior was changed. No build or tests were run, as requested. Compilation and live behavior remain unverified.

## RULED OUT

- The source did not lack an `Artwork rendered` log line; it was already attached to the `ImageOpened` event.
- No unconditional/missing semaphore release was found in the pre-batch cancellation and ordinary exception paths. The new `finally` now releases an acquired permit after every request/decode attempt, including success.
- The live counts alone cannot prove a semaphore deadlock or permit leak. They also cannot distinguish canceled loads from requests still pending or other unlogged outcomes because earlier cancellation was silent and the source log was deduplicated.
- The prior flyout's explicit content size was 640×520, with presenter limits of 760×620; the requested smaller bounded viewport is now explicit and remains scrollable.

## UNCERTAINTY

- Without a new live trace, the exact share of the roughly 290 unmatched requests caused by unload/rebind cancellation versus slow/hung transport is unknown. The new request-ID lifecycle events will identify queueing, acquisition, cancellation stage, timeout, failure, decode, and permit return.
- `ImageOpened` records that WinUI opened the matching bitmap and the handler made it visible; it cannot establish final unobscured pixels without live visual inspection.
- The 30-second request cancellation and 48-image cache behavior have not been exercised. Flyout placement and scrolling have not been visually checked at runtime.
- No build/test was run, so C# and XAML compilation are unverified.

## NEXT GATE

On the next authorized live run, correlate each `Artwork request queued` ID with its permit-acquire/release and terminal outcome. Check that unload/rebind cancellations are logged with the expected stage, timeouts occur by the configured bound, permit availability returns to six, cache hits appear after scrolling away and back, and valid loads produce `Artwork rendered`. Also open long About text and confirm the flyout stays within the intended bounds while all text remains reachable by vertical scrolling.
