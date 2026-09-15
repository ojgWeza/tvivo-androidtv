# Tvivo Product, UX, and Architecture Review

**Review date:** 2026-09-11  
**Evidence reviewed:** `PRODUCT.md`, `CLAUDE.md`, `TODOS.md`, `docs/architecture.md`, `docs/ui-scope.md`, and `docs/decisions.md`.  
**Confidence boundary:** This is a document review, not a new emulator or physical-TV QA pass. “Built” and “verified” below mean the project tracker records them that way; they are not claims from a fresh build run.

## Current-state snapshot

- Phases 0–4 are built; Phase 5 hardening is in progress.
- The tracker records 149 passing unit tests. No build or test was rerun for this review.
- Movie playback has been exercised. Live and episode playback remain shipped-but-never-executed paths because automated tests must not consume the development account's single connection.
- Q-4, Q-5, Q-14, U-14, QA-3 through QA-7, N-1 through N-7, and T-T2 remain materially open. Q-21 is fixed in code but still needs emulator verification; QA-8 is reported but not reproduced.
- The Room database is version 5 on real migrations. Favourites and resume positions are user data and must never be exposed to destructive migration fallback.

## Executive assessment

Tvivo has crossed the difficult boundary between a demo and a credible Android TV product foundation. The core loop is unusually well thought through for a POC: authenticate safely, browse a large and imperfect catalog from the couch, open a content page before committing a scarce stream, and retain the user’s personal state locally. The data architecture is particularly strong. Per-account Room data, per-category freshness, generation-safe full sync, and a real non-destructive database migration are the kinds of decisions that prevent a TV app from becoming unreliable after the first week of real use.

The product is **not yet ready to be called household-ready**, chiefly because two risks sit on the final mile:

1. The actual playback paths with the greatest user value—Live TV and episode playback—have not been executed under the intended physical-TV conditions.
2. Several remote-first usability and resilience states are either still open or only documented: offline-with-cache, failed/partial sync, exhausted connections, long-list behavior, and a reproducible phone launch crash with no trace.

The correct next move is not broad feature expansion. It is to make the existing experience trustworthy: prove playback on hardware, close failure-state gaps, and protect every important D-pad route with instrumentation and device QA. Once that is done, global search and episode-aware continue watching are the two features most likely to change day-to-day usefulness.

## What is already strong

### 1. The product has a clear user and operating model

The project correctly designs for a household member holding a remote, not the developer who knows the panel. This leads to good product choices:

- D-pad focus is treated as the input cursor, rather than as an implementation detail.
- Text entry is recognized as expensive, so login preserves input and the server is a single flexible field.
- Sign-out is treated as genuinely destructive because the encrypted credential material cannot be restored after app data is cleared.
- The UI remains provider-agnostic and avoids committing panel identity or credentials.

That is a strong foundation. It avoids the most common IPTV-client failure: an interface that is acceptable only to the person who configured it.

### 2. Data integrity and catalog architecture are excellent

The architecture is much more mature than a typical POC:

- Catalog, favourites, and resume positions are account-scoped.
- Content types have separate tables, avoiding cross-type ID collisions.
- Freshness is per category, preventing one refresh from silently making unrelated categories look current.
- Full-catalog sync uses generations and protects against zero-row panel responses deleting a usable cache.
- Virtual rails—Recently Added, Continue Watching, and Favourites—are views over local data rather than special network states.
- Room is at version 5 with real migrations, preserving the only user-owned data rather than using destructive fallback.

This is product work, not merely engineering hygiene. A media app earns trust when yesterday’s favourites, partially watched film, and browsable catalog are still there after a network failure, update, or account transition.

### 3. The content model respects the realities of the source

The implementation handles several panel behaviors that would otherwise leak visibly into the UI: noisy quality tokens in titles, mixed Arabic/Latin strings, unreliable episode duration fields, empty descriptions in list responses, missing direct playback URLs, and ratings that arrive progressively. The use of `name_display` and `name_normalized` is especially valuable: it cleanly separates what users see from what the database searches and sorts.

The move from immediate activation-to-play to a pre-run page was also correct. It makes a stream-opening action intentional, gives the user context, supplies a favourite action, and reduces accidental use of an account with limited concurrent streams.

### 4. The product has already improved beyond bare functionality

The design pass is materially more than polish. The theme roles, ten-foot type scale, rail label wrapping, Home action row, protected sign-out, subscriptions screen, and card treatments directly improve usability. The tracker records the design work D-1 through D-17 as built and emulator-verified, alongside `RefreshWorker`, Diagnostics, RTL support, and grid loading states. Q-21 is the explicit exception around later follow-up work: its fix exists in code but still needs an emulator screenshot with a long category title and the item filter open.

### 5. Testing discipline is pointed at the real risks

The project acknowledges that unit tests alone did not catch focus traps, layout defects, or remote navigation failures. There are 149 recorded passing unit tests, and the completed DAO/sync tests cover important data-loss and full-catalog guards. The remaining plan correctly prioritizes instrumented focus tests and physical-TV validation rather than pretending repository tests prove TV usability.

## Honest gaps and risks

### 1. Playback is the product’s primary promise, and it is not fully proven

Movie playback has been exercised, but Live TV and episode playback remain shipped-but-never-executed paths. Seek behavior, the player’s exit affordance, and audio-language reporting are also not fully verified because testing them opens a stream.

This is the highest release risk. A household does not experience Tvivo as a catalog application; it experiences it as “does the thing I selected play reliably, and can I get back?” Until the physical-TV session validates those paths, the product should be described as a strong POC, not a finished TV client.

**Recommendation:** schedule a focused physical-TV acceptance session before new scope. Test movie, Live `.ts`, and episode playback; remote Back; seek; resume; concurrent-connection refusal; audio metadata; key-repeat; and return-to-browse focus. Capture a concise pass/fail matrix and retain redacted diagnostic logs for failures.

### 2. Failure states are partially designed but not yet a complete contract

The intended state model is excellent: cached catalog remains browsable offline, partial sync is distinct from empty, errors carry appropriate actions, and successful refresh visibly confirms completion. However, the TODO tracker still identifies the following missing or incomplete work:

- cached catalog with no network;
- visible partial-sync state and retry;
- user-facing behavior when the panel rejects another stream because connections are exhausted;
- first-frame, buffering, and playback-failure validation;
- an unhandled-exception hook that writes synchronously to on-device Diagnostics.

The danger is not only ugly error copy. Without these contracts, ordinary household conditions can look like data loss: a category appears empty, a refresh appears broken, or a stream fails with an opaque player error.

**Recommendation:** treat N-1 through N-4 as one reliability milestone, not miscellaneous polish. Define and implement the user-visible state table end-to-end, then test it with network loss and deliberately partial sync responses.

### 3. A reproducible phone crash is untriaged

The sideloaded APK reportedly crashes on every phone launch. The manifest is intentionally TV-only, so phone support may not be a product objective; nevertheless, a repeated post-install crash is a quality and distribution concern if users can install the APK on unsupported devices.

The project is right not to guess at a cause. It needs a stack trace first. The proposed synchronous uncaught-exception logging is the right small investment because it will improve supportability on all devices, including a physical TV without attached ADB.

**Recommendation:** implement N-1 before attempting a fix. Then make a product decision: either support phones to a basic graceful degree, or clearly declare the APK Android-TV-only and prevent/install-guide against unsupported launch paths where possible. Do not leave a silent crash as the implicit behavior.

### 4. Focus reliability remains a systemic risk

Many of the historical defects were focus or layout failures that passed compilation and unit tests. Compose TV focus is fragile enough here that a specification alone is not protection. The tracker’s T-T2—instrumented D-pad traversal tests—is therefore not “test debt”; it is a release-quality feature.

The required cases should cover at least:

- initial focus on every entry screen;
- rail-to-grid entry and back;
- return from pre-run pages to the exact prior card;
- season-rail-to-episode-list traversal;
- text field entry/exit without focus traps or IME overlap;
- confirmation dialogs defaulting to the safe choice;
- focus retention through loading, empty, and failed-refresh states.

### 5. The browse experience still has visible QA defects

The remaining QA findings are not all equal:

- **High practical impact:** the focused grid card can be clipped at the bottom edge (QA-4). This damages focus confidence and can be worse on overscanning TVs.
- **Medium impact:** Arabic description paragraphs use the wrong paragraph direction (QA-3). This is prominent in content where Arabic is common and reduces perceived quality.
- **Lower but meaningful polish:** missing Live artwork has no designed fallback (QA-5); title overlays need stronger contrast and better word breaks (QA-6); rail last-active state is too subtle (QA-7).

**Recommendation:** fix QA-4 and QA-3 before cosmetic expansion. They affect basic comprehension and remote orientation. Then provide a deterministic live-card fallback consistent with the already-specified missing-poster treatment.

### 6. Long-list navigation has no proven strategy

The catalog is large enough that long-list behavior is a product capability. Holding Down through thousands of rows is not usable; `ALL` and jump affordances are intentionally deferred. This is reasonable for the POC, but it becomes a ceiling as soon as users browse beyond a few categories.

The current category filter and scoped item search reduce the problem, but not for someone who does not know the category. The project should not ship an `ALL` experience until it has a hardware-tested fast-scroll, page-jump, or index strategy.

## Important missing features, ordered by value

This ordering assumes the goal remains a dependable household Android TV client, not feature breadth for its own sake.

### Must have before calling the product household-ready

1. **Physical-TV playback acceptance and recovery**
   - Validate Live and episode paths, back/exit, seek, resume, audio metadata, and connection exhaustion.
   - Add user-facing connection-exhaustion copy that reports the observed condition without asserting a universal stream limit.

2. **Offline and partial-sync experience**
   - Keep cached rows visible when refresh fails.
   - Distinguish truly empty from not-yet/partially synced.
   - Provide an explicit retry and a small refresh-success confirmation.

3. **Crash observability**
   - Synchronously persist uncaught stack traces to the existing redacted Diagnostics log.
   - Make Diagnostics discoverable enough for the owner to retrieve a failure without ADB.

4. **Instrumented D-pad focus suite**
   - Protect the routes that previously regressed despite green unit tests.
   - Run it on the emulator whenever navigation UI changes.

5. **Resume durability verification**
   - Verify a resume position survives process death, not only a clean player exit.
   - This is a trust feature because Continue Watching depends on it.

### Highest-value next features after reliability

1. **Global search across Movies, Live, and Series (N-7)**

   This is the most valuable discovery feature once the catalog is fully indexed. A household user often knows a title, channel, or show but not the provider’s arbitrary category structure. It should reuse the current `BrowseItem` grid, return locally cached results, and clearly group or label result type. It must respect the TV IME ceiling and should never claim complete results before all relevant catalogs are indexed.

   There is a prior decision favouring scoped search. That decision was sensible when the user chose a content type first. Revisit it based on the observed catalog scale: 48k movies, 6k channels, and 13k series is now large enough that a clearly labelled global search improves retrieval more than it harms conceptual simplicity. A Home action is the natural entry point, provided results visibly identify type.

2. **Episode-aware Continue Watching and next episode (N-6)**

   This changes series from a catalog into a viewing habit. Mid-episode resume should reopen the exact episode; near-completion should offer or advance to the next episode. The rail should display the show identity, season/episode context, and artwork in a way that remains comprehensible without overloading the card. Do this only after resume writes survive process death.

3. **Multiple saved accounts (T-A1), when the household actually needs it**

   The data layer is already account-scoped, which makes this unusually low-risk when a second panel/account is real. It is not worth building speculatively, but it is a natural quality-of-life feature for a household with separate subscriptions or a changing provider.

4. **Newest-first sorting for category grids (T-D3)**

   The existing Recently Added rail is useful but bounded and catalog-wide. A category-level newest-first option provides a practical answer to “what changed in this genre?” without rebuilding taxonomy. Keep alphabetical as default; make the scope and persistence rule explicit.

### Useful, but defer until evidence supports them

- **Fast scroll / jump-to-letter and `ALL`:** important only when usage shows filters/search are insufficient. Do not add `ALL` as a huge grid without a navigation solution.
- **Idle dim/screensaver behavior:** relevant for OLED safety but lower priority than playback reliability. Prefer platform-aligned behavior and avoid accidentally interfering with normal TV screensaver settings.
- **Complete design-system documentation:** a motion spec, real icon set, and wordmark improve coherence. They should follow—not precede—the reliability milestone unless assets are needed to resolve an active usability defect.
- **Phone support:** decide explicitly rather than letting accidental sideloading define the experience.

## Features that should remain out of scope for now

The existing exclusions are disciplined and should stand:

- EPG/program guide and timeshift: materially different data and interaction models.
- Cross-platform/webOS abstraction: would weaken the Android TV focus work before the POC is proven.
- Voice search: high integration cost for uncertain benefit.
- Provider-specific shortcuts, assumptions, branding, or hardcoded panel behavior.
- Automated playback tests: the account’s connection allowance makes them unsafe.

Avoid adding social, recommendation, profiles, downloads, trailers, or a custom content taxonomy without evidence from household use. Each would create more surface area than the current product needs.

## Recommended delivery plan

### Milestone A — Make the current promise reliable

1. Implement crash logging (N-1) and capture the phone crash trace.
2. Specify and implement connection-exhaustion, offline-cache, and partial-sync states (N-2 through N-4).
3. Add T-T2 focus instrumentation for the critical navigation graph.
4. Fix QA-4 and QA-3; then address QA-5 through QA-7.
5. Conduct one deliberate physical-TV acceptance session, including playback and resume-process-death verification.

**Exit criterion:** a household member can sign in, browse cache during a refresh or network outage, play every content type on hardware, recover from failure, and return to the prior card using only a remote.

### Milestone B — Improve discovery and continuity

1. Implement global search only after full indexing, with results grouped/labeled by type.
2. Implement episode-aware Continue Watching and next-episode behavior.
3. Decide newest-first sorting and, if needed, saved multi-account switching.

**Exit criterion:** a user can find a known title without knowing its category and can resume a series naturally after leaving the app.

### Milestone C — Refine the product identity and longer-term navigation

1. Finish iconography, wordmark, splash assets, and a restrained motion/reduced-motion specification.
2. Observe actual use before deciding on `ALL`, key-repeat acceleration, and jump navigation.
3. Add idle protection only if the deployed device’s display behavior warrants it.

## Product metrics and research to add later

There is currently no analytics or user research, so no claim about real household behavior should be made. If privacy and scope permit, the owner can use lightweight, local-only diagnostics rather than third-party tracking:

- time from Home to first successful playback;
- playback-start failure category counts (redacted, no URLs or credentials);
- frequency of refresh failures, partial sync, and cache-only sessions;
- most-used discovery route: category filter, scoped search, global search if added;
- focus-route failures captured during QA, not as user telemetry.

For qualitative validation, watch one non-technical household member perform five tasks: sign in after an intentional bad password, find a known film, play a channel, resume a partially watched item, and recover from a disconnected network. Note pauses, wrong turns, and requests for help. Do not coach during the task.

## Documentation and planning hygiene

The documentation is thoughtful but has a few status conflicts that should be reconciled before the next planning session:

- `TODOS.md`’s top summary says Q-8 remains open, while the design table says D-6 is built and closes Q-8.
- Some sections retain historical wording that the design pass is “decided and unbuilt,” followed immediately by a newer status stating D-1 through D-17 are built and verified.
- The architecture/product text contains both earlier and later interaction decisions around activation/play; the newer pre-run-page decision should be treated as authoritative everywhere.
- The design-pass status says all work is verified, while Q-21 explicitly remains unverified and the later QA sweep leaves QA-3 through QA-7 open. The summary should distinguish the completed design scope from unresolved follow-up defects.

This is not a product defect, but an out-of-date status document causes expensive rework. Update historical headings or label them explicitly as superseded, retain the useful rationale, and maintain one short “current verified state” section at the top of `TODOS.md`.

## Final verdict

Tvivo is a high-quality, carefully engineered Android TV POC with an unusually solid cache and data-integrity core. It has already solved several problems that commonly undermine IPTV clients: panel inconsistency, large catalogs, unsafe data migrations, mixed-direction titles, destructive credential loss, and remote focus restoration.

Its next success will come from restraint. Do not chase a larger feature list before proving the playback and failure paths on the device people will actually use. Once that reliability bar is met, global discovery and episode continuity are the clearest investments in everyday value. The foundation is strong enough to support them; the immediate job is to earn user trust in the loop that already exists.
