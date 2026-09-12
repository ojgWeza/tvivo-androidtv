## T-D3 — Newest-first sort toggle for category grids

**What:** a second sort option, `added` descending, alongside the alphabetical
default, reachable per category.

**Why:** alphabetical fixes scanability but loses "what's new in this category".
Unlike `ALL`, a single category is small enough that either ordering is usable.

**Context:** `RECENTLY ADDED` already exists as a separate bounded virtual rail
(30 items, catalog-wide). This is per-category, inside a grid.

**Unblocked now** — Phase 2 shipped the grid and the long-press context menu to
hang a sort control off. Still needs a decision on whether the choice persists
per category, globally, or for the session only.

---

# Part 5 — Environment notes (not app defects)

Recorded because each cost real time to diagnose and will recur.

- **Emulator media volume defaults to 3/15.** Playback is inaudible and looks
  like an app bug. Confirmed 2026-09-07: the stream was AAC, decoded cleanly by
  `c2.android.aac.decoder`, no decoder errors — the volume was simply low.
  Raise with 14× `adb shell input keyevent 24`.
- **`hw.keyboard=yes` breaks Esc-as-Back.** The host keyboard becomes a real HID
  device, so Esc arrives as `KEYCODE_ESCAPE` (111) instead of `KEYCODE_BACK` (4)
  and the app ignores it. Fix is environmental: Extended Controls → Settings →
  General → "Send keyboard shortcuts to" = Emulator controls. **Do not add a
  `KEYCODE_ESCAPE` handler to app code** — no real TV remote sends that key.
- **Physical-keyboard typing needs `forwardShortcutsToDevice`, and it is stored
  outside the repo and outside the AVD.** It lives in the Windows registry at
  `HKCU\Software\Android Open Source Project\Emulator\set`, value
  `forwardShortcutsToDevice` (REG_SZ `true`/`false`) — the backing store for
  Extended Controls → Settings → General → "Send keyboard shortcuts to". It is
  absent by default, so a fresh emulator profile silently loses it and the host
  keyboard stops typing into the guest. Restore with:
  `Set-ItemProperty -Path 'HKCU:\Software\Android Open Source Project\Emulator\set' -Name forwardShortcutsToDevice -Value true -Type String`
  Takes effect only on emulator restart.
  **This directly conflicts with the Esc-as-Back note above** — `true` sends keys
  to the device (host keyboard types, Esc arrives as `KEYCODE_ESCAPE` and Back
  breaks); `false` keeps emulator shortcuts (Esc is Back, host keyboard does not
  type). Pick per task; they cannot both be satisfied.
- **Prefer pasting over typing for credentials.** `clipboardSharing` is already
  `true` in that same registry key, and `adb shell input text '<value>'` works
  regardless of either setting. Neither needs the host keyboard.
- **`-gpu swiftshader_indirect` is required.** The host GPU path paints a black
  window while the guest renders correctly.
- **`-memory 1536`, and stop the Gradle daemons first.** 16 GB total does not fit
  the emulator plus the Gradle (1536m) and Kotlin (768m) daemons. The emulator
  gets OOM-killed mid-session otherwise. App launch went 34s → 1.4s after this.
  `tools/emulator.sh` does all of the above.
- **`adb shell` corrupts binary on Windows** (newline translation). Use
  `adb exec-out` to pull databases or screenshots.
- **Android TV AVDs have no touchscreen** (`hw.screen=no-touch`). Mouse clicks
  do nothing by design; `input tap` never works. Drive with `keyevent` or arrow
  keys.

---

# Considered and not taken

Recorded so they are not re-proposed as new ideas:

- **Idle dim / screensaver.** A static rail on an OLED panel risks burn-in after
  ~5 min idle. Small and self-contained; judged not worth tracking yet.
- **Manual refresh control placement.** Now built into the browse header.
- **Voice search.** Most Android TV remotes have a microphone, and search is the
  only place outside login that asks for typing. Declined as a whole integration
  for one field; the rail's category filter already keeps most navigation
  typing-free.
- **`KEYCODE_ESCAPE` handling in the player.** See Part 5 — an emulator config
  artefact, not a product requirement.
