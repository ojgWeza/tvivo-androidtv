# Tvivo TODO Archive

Closed Android production work moved from `TODO.md`. Closure notes explain the outcome; item text is preserved as it appeared in the open queue.

## Android production Phase 0

Closed 2026-10-17. The offline provider and local media generator now live in `tools/mock-provider/`; see its README for run and emulator setup instructions.

- [ ] Slice 0B: standalone mock-provider script for emulator and Play reviewer demo (short generated video, controllable `max_connections`, stalls, 429s, multiple tracks, configurable second-connection behaviour). Blocked on owner decisions: hosted vs offline demo source, CC BY Sintel vs CC0 media. Fetch the media against its recorded SHA-256 (`desktop-fixtures/` is gitignored).

## Android production Phase 3 Player

Closed 2026-10-17 as implementation work. Source and JVM coverage are present; device acceptance stays open in `TODO.md` under “Device-unverified / follow-ups.” The implementation plan is [`docs/android-player-slice-plan-2026-10-16.md`](docs/android-player-slice-plan-2026-10-16.md).

- [ ] Implement `docs/android-player-slice-plan-2026-10-16.md` in order: service-owned single ExoPlayer/MediaSession and foreground notification for screen-off/background audio; one-connection release-before-next; lock-screen/media controls, audio focus and becoming-noisy pause; phone/tablet PiP, with no TV PiP dependency.
- [ ] Finish player correctness: genuine-end/early-EOF Retry, one stall reconnect, saved Off/Next/Shuffle modes and timed prompt, preferred audio/subtitle tracks, and sleep timer presets/warning/extend/end-of-item across background playback.

## Android production Phase 5 Theme

Closed 2026-10-17. Persisted Dark/Light preference is implemented with Dark as the default; the owner decided both basic themes are free.

Persisted Dark/Light preference (Dark default)
