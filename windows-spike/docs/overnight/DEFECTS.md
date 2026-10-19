# Overnight defects

| ID | Sev | Row | Evidence | Suspected cause | Status |
|---|---|---|---|---|---|
| DF-01 | P1 | LC-01/04/07/09, LC-08 blocker | After every cached start (5 of 5 sessions in run 02), every catalog refresh fails within 2-523 ms: `Catalog Live/Movie/Series refresh failed: InvalidOperationException`, `catalog.cache.refresh.complete outcome=failure`, retried at +20 s/+60 s and failing the same way. Every Spotlight/hover lookup fails: `hover.metadata failed type=Movie/Series reason=InvalidOperationException` (8 in session 4b368663). General internet reachable (TCP 443 test OK). | The provider throws "account has not been authenticated by this catalog provider" (`XtreamInfrastructure.cs` `_connections` lookup, lines ~161/173/262). Either (a) the cached-start authentication fails and the retry path never re-authenticates, or (b) it succeeds but `CatalogLandingPage` re-keys the account (`result.Account with { AccountId = cachedAccount.AccountId }`) to an ID the provider never stored (the provider's ID includes the server-reported port). There is no auth-outcome log line to tell which. Brief F1. | Open, brief F1 |
| DF-02 | P1 | LC-05 | Session 4b368663, 15:06:09-15:06:17: search opened by UIA Invoke (focus `TopSearchBox`), 2 letters typed, nothing else sent; focus moved to `WindowStateButton`, `spotlight.open index=3 via=hover` (pointer resting over the strip), then `CLICK-AWAY-TRACE content-selected kind=Movie` -> `player.reset NewItem` -> `playback.attempt` -> engine-open. No Enter, Space or click was sent. UI thread stalled 0.8-2.8 s around it. Not reproduced in 2 controlled repeats (focus stayed in the box). Unrequested, unmuted stream start. | Unknown. Candidates: queued input handled after a stall-time focus move (the letters typed while focus was moving onto an activatable element); Spotlight `TouchTapActivates` treating a synthesized pointer event as a tap on the already-open card; search re-render handing focus to a result item. Brief F2 (diagnose first). | Open, brief F2 |
| DF-03 | P2 | LC-05 (session 4b368663) | `perf.stall` 1808 ms (15:05:39) and 1138 ms (15:05:40) 4-5 s after `authDone`, ops = probe timer only (uninstrumented). | Possibly the harness's UIA root lookup (UIA calls run on the app UI thread), possibly post-auth shelf/apply work. Needs a re-measure with no UIA traffic in the window (D-i). | Needs confirmation |
| DF-04 | P2 | LC-09 (session ab38978f) | First Spotlight metadata lookup at 15:01:30.778 failed at 15:01:30.797, before `authDone` at 15:01:31.018 (same in c4794e69: lookup 14:56:18.525, `authDone` 14:56:18.528). The open card then shows no summary until the user re-hovers. | Spotlight auto-open of card 1 starts a provider lookup before the saved-account authentication has registered the connection. Brief F1 part 3. | Open, with F1 |

Watch items (not defects): LC-02 close after a LibVLC open took CT 2778 ms (dispose stage 1609 ms) against a 3 s
limit - re-check in PC-02/PC-03 after Sol's S1 close-budget slice lands.

## Run 03 status updates (368a25d, 2026-10-19 21:20-21:40)

| ID | New status | Evidence |
|---|---|---|
| DF-01 | **Fixed, verified live** (368a25d) | 10/10 starts `auth.saved outcome=success idMatch=True`; session fa80957c `catalog.cache.refresh.complete outcome=success durationMs=16312` after a cached start; 0 `not-authenticated` / `refresh failed` / `hover.metadata failed` in 12 sessions. |
| DF-02 | Open (F2 diagnostics in build, not exercised) | LC-05 BLOCKED (focus); 0 selections all run. |
| DF-03 | Needs confirmation (unchanged) | No UIA traffic this run; 0 stalls > 1000 ms after `authDone` in 10 starts - weak evidence for the harness-load theory (D-i). Confirm in a foreground run. |
| DF-04 | **Fixed, verified live** (368a25d) | First Spotlight lookup 34 ms after `authDone`, `hover.metadata fetched plot=present`. |

Run 03 watch items (not defects):
- W-02: SC_CLOSE at Available 1320 MB: CT 3310 ms while `window.close-complete totalMs=336` - the extra time is
  after teardown (process exit under paging). Re-check CT under normal memory in PC-02/PC-03.
- W-03: every start has one 1.0-1.8 s stall 0.1-0.3 s after `cachedRender`, before `authDone`
  (ShelfContainerLoaded / ContainerContentChanging / ArtworkDataContextChanged). Inside the startup window (D-a),
  but it is the first moment the catalog looks usable. Measure input latency there in a foreground run; if a key
  press in that window waits > 1 s, raise as P2 perf.
- W-04: LC-01 idle CPU 1.5-2.8 % (run 02: 0-1.2 %). Under the 3 % limit; re-check in SP-05/SK-01.
