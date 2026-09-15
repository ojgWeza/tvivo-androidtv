## U-13 — the Continue watching condition was hiding a data-loss bug

`PlaybackStateRepository.savePosition` collapsed two unrelated cases into one `remove`:

```kotlin
if (positionMs < MIN_TRACKED_MS || finished) { remove(...) }
```

The 60 s floor is right — it stops accidental opens filling the folder. But crossing it
downward says only that *this visit* was short; it says nothing about the forty minutes
already watched. So opening a part-watched film and backing out within a minute **deleted
the resume point**, which is the most common way to touch something you are part-way
through. Too-short now declines to *write*; only `finished` removes.

**Not fully verified**: proving it needs an open stream. The three resume rows on the device
(3–6 % watched) are exactly the rows the old code would have destroyed on the next short
visit, and they survived the v5 migration intact.

