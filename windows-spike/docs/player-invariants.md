# Player invariants

Starting any new playback clears the previous picture immediately; a failure never re-shows the old picture; reconnecting the same media may keep its frame.

An expired sleep-timer stop may hide the playback surface and stop UI updates only while its playback generation still owns the session. A cooldown or retry completion may clear the progress bar only while it owns that bar.

The black curtain shows static, non-interactive popcorn loading art until the current generation reaches its first frame; it is hidden for compact player areas under 300 px and on final failure.

The selected video host must be visible and loaded at a non-zero size under the opaque curtain before an engine opens; collapsing it breaks LibVLC swap-chain initialization. Each attempt stops both engines, exposes the selected host beneath the curtain, forces layout, then waits up to 3 seconds for `Visible`, `IsLoaded`, and positive actual width/height. This host wait has its own budget and is not an engine/network timeout. Failure logs `playback.host-not-ready` with generation, host visibility/load/size, player page visibility and curtain state, then passes `HostNotReady` through the same retry decision.

Cinema and Compact share one cursor-hide controller and one popup owner. Compact may hide the pointer only when its controls fade after idle during active playback, with no pointer over the chrome/resize edges, gesture, flyout, keyboard interaction, or inactive window. Leaving Compact restores the pointer and tooltips and destroys the popup before another presentation can own it.

In both mini players, X is "Stop playback" and cancels the sleep timer with reason `mini-close`; the in-window mini stays on its current catalog page. Expand is "Return to player", and Esc in the in-window mini also returns to the full player.

New playback clears/stops both engines before exposing the host, and the curtain stays up until that generation's first frame. `player.reset` is logged at the point the old surface is cleared; `player.host-ready` is a separate readiness diagnostic. After stopping both engines, `playback.handoff-stopped` records stop duration and the selected host/page state before visibility is changed.

The configured **Playback attempts** value (default 5) is the per-engine attempt budget, including the first open. Each engine gets a fresh budget. An engine open has a 10-second per-attempt cancellation; that timeout is classified inside the attempt loop and goes through the retry policy. Timeout and network failures can use the full configured budget without a cumulative elapsed cap. Retries wait 0.5, 1, 2, then 3 seconds. Not Found uses the same attempt count and backoff, with an 8-second retry-decision cap and a 10-second hard stop. HTTP 403 is never retried. Once the selected engine exhausts its budget, supported transient failures may fall back once to the other engine with a fresh budget; Not Found does not currently trigger engine fallback. Pressing Retry starts a fresh budget and logs `player.reset reason=Retry`.

## Regression checklist

- Movie → pause → failing Live item → another movie: no old frame at any time.
- Check both LibVLC `VideoView` and Native `MediaPlayerElement` surfaces, including retries and final failure.
