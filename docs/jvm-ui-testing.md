# JVM Compose UI testing

Compose UI tests run with Robolectric from `:app:testDebugUnitTest`; they do not
install the APK or start an emulator. The app uses AGP 8.5.2, Kotlin 2.0.20,
Compose BOM 2024.06.00, and Robolectric 4.13. Paparazzi 1.3.5's published
baseline lists AGP 8.4.2 and Compose 1.7.5, which does not match this project's
AGP/Compose combination. Roborazzi is used for screenshots because it captures
Compose semantics nodes through Robolectric and has explicit record and verify
tasks. Recheck the compatibility notes before changing any of those versions.

## Focus tests

Put JVM Compose tests under `app/src/test/java`. Use `createComposeRule()` with
`RobolectricTestRunner`, provide `LocalIsTelevision` as `true` for remote
surfaces, and interact with tags through `performKeyInput { pressKey(...) }`.
Use fake model values and callbacks; tests must not open a media stream.

Run the unit suite:

```bash
./gradlew :app:testDebugUnitTest --console=plain
```

## Screenshot tests

Roborazzi's recorded references and rendered screenshots live under
`app/build/outputs/roborazzi/`; reports live under `app/build/reports/roborazzi/`.
The references are local build artifacts and are ignored by Git. Record again
after cleaning the build directory. Keep captures cropped to the component or
the smallest screen that demonstrates the behavior. Commit a golden only when
it is tiny and useful as a stable review artifact; never add large resolution
matrices to source.

Record/update references, then verify them:

```bash
./gradlew :app:recordRoborazziDebug --console=plain
./gradlew :app:verifyRoborazziDebug --console=plain
```

To review a difference without updating the reference, run
`./gradlew :app:compareRoborazziDebug --console=plain`. Keep generated screenshots
under `app/build/`; do not copy them into docs or source folders. Roborazzi
verification requires a recorded reference, so run record before verify when
introducing a new screenshot test.

The initial sample set covers TV browse at 1280×720 and 1920×1080, phone
category picker, English and Arabic/RTL TV search keyboard, and the player
overlay. Each view is rendered at font scale 1.0 and 1.5. Fixture titles and
counts are synthetic.

## Debug Google Drive source

The Drive source is enabled in debug builds and disabled in release builds. Its
runtime values default to empty. Put local values in the ignored root
`secrets.properties` or pass them as Gradle properties:

```properties
driveMockBaseUrl=http://10.0.2.2:8090
driveTvClientId=local-mock-client
```

Start `node tools/mock-drive/server.mjs` on the host before using the debug
source. Android emulators reach the host through `10.0.2.2`. For a connected
device, use `adb reverse tcp:8090 tcp:8090` and set
`driveMockBaseUrl=http://127.0.0.1:8090`. Keep these values in local untracked
configuration; `BuildConfig` defaults to an empty URL and client ID. The debug
phone consent adapter approves a synthetic mock device code and requests a
mock bearer. TV displays a device code and waits for approval through
`POST /__ctl/approve` as described in `tools/mock-drive/README.md`.

The phone adapter is mock-only until a Google Identity consent client is
provided. A build without mock configuration reports that Drive is unavailable
instead of trying the Xtream credential path. No automated UI test opens a
stream.
