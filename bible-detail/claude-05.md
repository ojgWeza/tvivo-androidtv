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

