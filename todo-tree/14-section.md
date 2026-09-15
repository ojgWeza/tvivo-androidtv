## Q-22 — The diagnostic log has almost no call sites — **FIXED, verified 2026-09-10**
**Severity: Medium. Found 2026-09-08 on-emulator.** `diagnostics/DiagnosticLog.kt`

The Diagnostics screen renders correctly and, after a full session of cold start, catalog
browsing, category filtering and item filtering, reported **"Nothing recorded yet."** That
is honest — but it means the screen would be empty at exactly the moment someone opened it
to find out why something broke.

**Cause:** the log was wired only into `RefreshWorker` and `AccountViewModel` (manual
refresh, sign-out). None of those ran. The events that actually matter — app start, catalog
sync start/finish/zero-row guard, category refresh failure, auth failure, playback error —
are not instrumented.

**Fixed 2026-09-10.** Call sites added at every point named above plus app start:

- `MainActivity` — start marker with version and API level. Without it an empty log is
  ambiguous between "nothing went wrong" and "the process restarted under the problem".
- `CatalogSyncer` — start, TTL skip, complete-with-row-count, HTTP failure, empty body,
  and the `partial`/zero-row guard as a **warning**. That last one is the most valuable
  line on the screen: it is the exact state where `ALL`, `RECENTLY ADDED` and search are
  incomplete while every per-category listing looks perfectly healthy. Its own `Log.i`
  calls were replaced rather than duplicated. Cancellation is logged as `info`, not as a
  failure — leaving a browse screen cancels its sync, and an error per screen exit would
  bury the real ones.
- `BrowseViewModel` — the single `toAppError()` funnel every browse failure passes
  through, plus the missing-credentials path.
- `AuthRepository` — HTTP rejection, panel refusal (`auth: 0`, expired), and transport
  failure.
- `PlayerActivity.fail()` — the one exit both the error listener and the connection-limit
  path already go through.

**Nothing logged carries a URL, a credential or a title.** Exceptions are recorded by
type (`t.javaClass.simpleName`), never by message: a network exception's message echoes
the request URL, and that URL is the whole account.

**Verified on the emulator 2026-09-10**, and the verification is worth recording because it
was first read as a *failure*.

After a ~45 minute session across Movies, Live and Series the screen showed **four lines**:
app start, and `within TTL, keeping cached generation` for vod, live and series. That was
briefly written up as "Q-22 is not fixed". It is not — it is the correct and complete output.
Every one of the 20 call sites is app start, a sync lifecycle event, or a **failure**, and in
that session no sync ran (all three content types were inside their TTL) and nothing failed.
There was nothing else to record.

**The trap to avoid next time: a healthy session produces a nearly empty Diagnostics screen,
and an empty screen is not evidence of missing instrumentation.** Read the call-site list
before concluding otherwise. Whether routine non-failure events should also be recorded is a
separate design question — **T-D4**.

---

# Part 1c — 2026-09-10 QA sweep: fixed and verified

Fourteen items raised by the user plus two from the report-only sweep, all landed in one
pass and re-driven on the API 34 emulator. Kept as one block because they were one change
set; the reasoning for each lives in the code comment at the site.

| ID | Item | Fix | Verified |
|---|---|---|---|
| U-1 | Entering Series focused the header search and opened the IME | **Not reproduced** — see QA-8 | — |
| U-2 | Player has no fast-forward; the time-bar knob is not focusable | `SEEK_INCREMENT_MS` + `onKeyDown` LEFT/RIGHT in `PlayerActivity` | **No** — see U-14 |
| U-3 | No rating anywhere | `rating` parsed → DB v5 → grid badge + detail line | Yes, real values |
| U-4 | Series plot truncated with no way to read the rest | `ui/common/ScrollableText.kt` | Yes |
| U-5 | Rail → grid → rail → grid landed on the first visible item | `focusProperties { enter }` in `ContentGrid` | Yes |
| U-6 | `Refresh this category` — "this" is inferred; pill too big | Label → `Refresh`; `IconPill` 88 → 56 dp | Yes |
| U-7 | Search pill exaggerated; Clear shown with an empty box | Clear only when non-blank, flush; field 320 → 260 dp | Yes |
| U-8 | Count on the right panel duplicates the rail | `N of M` only while filtering | Yes |
| U-9 | Home icon circles far too big for their glyphs | Same `IconPill` change as U-6 | Yes |
| U-10 | Movies → Home → Movies showed a stale list | Same root as U-12 | Yes |
| U-11 | `Refresh everything` → `Refresh all` | Label | Yes |
| U-12 | Back from a channel/movie/show lost the left-panel focus | Rail no longer competes when `pendingFocusItemId` is set; restore now *requests focus*, not just scrolls | Yes |
| U-13 | Continue watching dropped a film played for 1 s | See below — this was **data loss** | Partly |
| QA-1 | Live pre-run page used the 2:3 poster frame for a 16:9 logo | `CHANNEL_WIDTH/HEIGHT` in `ItemDetailScreen` | Yes |
| QA-2 | Episode-picker `Refresh` was orange text (= the focus colour) | `IconPill` | Yes |

