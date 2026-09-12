## Suggested order for the next session

1. **T-T2 (instrumented focus tests).** Now the highest-value gap. Five of the
   defects found on this project were focus/layout, all behind green builds, and
   Phase 4 added two more of the same class (initial focus on the detail screen,
   and the season rail's RIGHT target) that only a screenshot caught. Q-9 is the
   sharpest case: the first fix compiled, read correctly, and did nothing.
2. **Get the physical TV in early, ahead of Phase 5.** Live `.ts` playback, the
   `/series/` episode path, `max_connections` behaviour and remote key-repeat
   (T-D2b) are all unverifiable on the emulator, and both live and episode
   playback are shipped-but-never-executed code. This retires more risk than
   further emulator work.
3. **Phase 5 — hardening.** RTL verification, empty/loading/error states,
   `RefreshWorker`, on-device diagnostic log. **Take N-3 and N-4 inside this**,
   not after it — they are error states, and building the state table twice is
   the same mistake D-4/D-5 avoided.
4. **N-1 first among the new items (Part 2c).** It is small, and Q-14 cannot
   move at all until a stack trace exists on a device with no adb attached.
   **N-2 before the physical-TV session**, since that session is the only place
   an exhausted `max_connections` can be provoked on purpose.

**T-T1 is done.** `CatalogDaoTest` (16 tests) covers `replaceCategory`, the
generation flip, account scoping and the per-type table separation;
`CatalogSyncerTest` (11) drives the full-catalog tier over MockWebServer against
in-memory Room, including the zero-row guard for all three content types.

`DESIGN.md` (T-D1) can wait — the shared `tvFocusFrame` and `BrowseItem` /
`CardShape` now enforce most of what it would have said. Multi-account (T-A1) is
speculative until a second panel actually exists.

---

# Part 1 — Open defects

Ten defects have been found on this project. **Every one of them passed a green
build and a green unit suite**, and every one was found by driving the emulator
over `adb` and looking at a screenshot. Budget for that on every UI change.

Seven are fixed and verified (Q-1, Q-2, Q-3, Q-6, Q-7, Q-9, and the two Home
nits found while verifying them). Phase 4 found three more the same way and all
three are fixed: the detail screen opened with focus on nothing, the picker
showed "1 min" for 40-minute episodes because this panel's `duration_secs` is
wrong, and episode titles carried the `HD` token the grid strips. What is left
is below. The durable lessons
from the fixed ones live in `docs/ui-scope.md` and `docs/decisions.md`, not
here — this file is for what is still open.

