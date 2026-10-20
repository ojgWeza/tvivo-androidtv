# Static audit round 2

Scope: the new Spotlight, cooldown, pointer, diagnostics, refresh, close, and session ownership paths. These are source findings; live behavior remains unverified. Priorities reflect impact if triggered.

## Findings

| ID | Priority | Evidence | Scenario and minimal fix |
| --- | --- | --- | --- |
| SA2-01 | P1 | `src/Tvivo.Core/DiagnosticRedactor.cs:10-24` | The export copies arbitrary ASCII values from fields such as `event`, `op`, `stage`, and `trace`. A value shaped like a diagnostic token can contain a username, title, or hostname and enter the support zip. Validate each field against its actual grammar and reject ambiguous values; test encoded, Unicode, userinfo, IPv6, query, and long-line attacks. |
| SA2-02 | P1 | `src/Tvivo.Infrastructure/XtreamInfrastructure.cs:19-20,93-94,218,354-361,456` | Concurrent authentication and EPG refresh/read mutate ordinary dictionaries. A parallel refresh can throw or lose a map update. Use concurrent collections and atomic map updates. |
| SA2-03 | P1 | `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs:2997-3017` | Metadata lookup checks only card reference after `await`; a refresh may reuse the same card object for changed content, so an old result can overwrite its new summary. Capture account, item, and card content revision; cancel on rerender and check before apply. |
| SA2-04 | P1 | `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs:2985-3004` | A card already expanded by mouse hover can activate on the first touch tap, contrary to the two-step touch rule. Track a touch-armed index separately from the open index and require a prior tap on that index. |
| SA2-05 | P2 | `src/Tvivo.Core/SpotlightPolicy.cs:128-147` | Specific-folder preparation re-enumerates the candidate source for every selected folder. Many folders and a large candidate set can stall the worker and allocate many temporary lists. Group eligible candidates in one pass, keeping the per-folder and total limits. |
| SA2-06 | P2 | `src/Tvivo.Core/RecentlyLeftCooldownPolicy.cs:37,56` | The history dictionary retains every opened item for the lifetime of the window. Repeated browsing can grow it indefinitely. Expire entries outside the 70-second window and cap retained identities. |
| SA2-07 | P2 | `src/Tvivo.Core/DiagnosticLogRotation.cs:5-10` | An individual write larger than the configured limit creates an oversized active log, and rotation does not repair already oversized backups. Reject or truncate oversized writes at the caller and bound backup files during rotation. |
| SA2-08 | P2 | `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs:3040-3064` | Spotlight dwell and metadata cancellation sources are canceled but not disposed on all paths. Dispose each in its async owner's `finally`, after callbacks finish. |
| SA2-09 | P2 | `src/Tvivo.App/Pages/CatalogLandingPage.xaml:484-489,524` | New strip position/hint and shelf count text are 12-14 DIP, and the empty-state Settings button has no 44-DIP touch minimum. Raise body/interactive labels to 16 DIP, set the button minimum, and verify layout live. |
| SA2-10 | P1 | `src/Tvivo.Infrastructure/CatalogRefreshTasks.cs:13-33` | A second refresh caller joins an in-flight task even after its owner cancels. Switching pages during automatic refresh can therefore make a fresh manual refresh inherit cancellation. Treat a task with a cancelled owner token as inactive and start a fresh operation; retain reference-safe cleanup. |
| SA2-11 | P2 | `src/Tvivo.App/Pages/CatalogLandingPage.xaml.cs:3020` | Spotlight metadata lookup swallows every unexpected exception, hiding repeat failures during hover. Log only the exception type and a fixed event label, without message or item data. |

## Reviewed paths

- The close path uses one total teardown budget and detaches playback, timer, window, and EPG handlers before engine disposal. The backstop can still terminate an incomplete native disposal; only a live close run can establish its timing and process behavior.
- Cooldown and retry progress have generation ownership checks; stale completions do not unconditionally stop a newer bar. Pointer popup ownership is cleared on disposal, and the delayed probe has a generation check.
- Spotlight snapshot revision checks reject settings saved during a snapshot build. The metadata continuation is a separate content revision risk above.
- Support exports use three named entries and a final compressed size check. The remaining privacy risk is the redactor's accepted field values.
