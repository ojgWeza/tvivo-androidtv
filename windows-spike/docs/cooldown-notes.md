# Recently left stream cooldown

Movie and episode sessions remember their last open using a monotonic clock. If the same item is opened again within 70 seconds after another item was opened, a first HTTP 403 enters a separate quiet wait. Range GET probes run near 3, 10, 25, 45, 58, and 65 seconds, with at most 10% jitter and a 65-second total bound. A 200 or 206 allows the normal playback engine to open the stream again. Other 403 responses remain final.

The cooldown is cancelled with its playback generation, on navigation, window close, or when the sleep timer ends. It does not add 403 to the ordinary retry policy.
