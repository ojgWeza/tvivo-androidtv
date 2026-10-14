# Player invariants

Starting any new playback clears the previous picture immediately; a failure never re-shows the old picture; reconnecting the same media may keep its frame.

The black curtain shows static, non-interactive popcorn loading art until the current generation reaches its first frame; it is hidden for compact player areas under 300 px and on final failure.

## Regression checklist

- Movie → pause → failing Live item → another movie: no old frame at any time.
- Check both LibVLC `VideoView` and Native `MediaPlayerElement` surfaces, including retries and final failure.
