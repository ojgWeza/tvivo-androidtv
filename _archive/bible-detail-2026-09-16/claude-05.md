## Testing

Emulator-first. Create the AVD at the **same API level as the physical TV**
(`adb shell getprop ro.build.version.sdk`).

- Local unit tests: JUnit + kotlinx-coroutines-test + Turbine (repositories),
  Room in-memory DB (DAO + transaction behaviour), MockWebServer (API contract
  and the season-keyed-object parsing), Robolectric where framework classes
  are unavoidable. Run with `./gradlew test`.
- Instrumented: Compose D-pad focus traversal on the Android TV emulator.
- **No automated test may open a stream** — `max_connections` is `1`.
- Physical-TV validation happens once, after Phase 5. Accepted risk; the live
  `.ts` path carries the most exposure under that choice.

**Unit tests are necessary and nowhere near sufficient here.** Every defect
found so far — the login focus trap that made the app unusable on a remote, the
password leaking into the IME suggestion strip, the crushed Home tile, the
missing back stack — passed a green build and 66 green unit tests. All of them
were found by driving the emulator over `adb` and looking at a screenshot.
Budget for that on every UI change, and treat "it compiles and launches" as
saying nothing about whether the screen is usable.

Full coverage map and edge cases:
`~/.gstack/projects/ojgWeza-tvivo-androidtv/Dell-main-eng-review-test-plan-*.md`

## Emulator environment notes

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

