# Current project state

Audit date: 2026-09-19. This document records the current on-disk source and the two requested historical records. Every statement below is labelled by evidence strength: OBSERVED means directly present in a file or directly recorded as measured; INFERRED means logically derived from observed source/evidence; HYPOTHESIZED means not verified at runtime.

## Correction: this session's build/run results are AHEAD of `.spike-decision-log.md`

The sections below were drafted by reading only the repo's own persisted records
(`.spike-decision-log.md`, `WINUI3-PLAYBACK-HANDOFF-REPORT.md`, `CONSTITUTION-DRAFT.md`), which do
**not yet reflect this session's actual completed work**. The decision log's last entry says the
compile-and-manual-run gate "requires its own explicit authorization... none has been run" — that
statement is now **stale**. This session actually ran it. Corrected facts, independently verified
twice each (once by Claude directly, once by Codex delegated and cross-checked) within this same
2026-09-19 session, not yet written back into `.spike-decision-log.md`:

- **OBSERVED — The solution now builds successfully.** `dotnet build Tvivo.sln -c Debug -p:Platform=x64`
  reports `Build succeeded, 0 Warning(s), 0 Error(s)` for all 8 projects, including `Tvivo.App` and
  `Tvivo.Playback`. This required two source-level fixes beyond the three-file LibVLC slice the
  decision log describes: (1) removing the dead `FFmpegInteropX`-based `WindowsPlaybackEngine` class
  and its `PackageReference` (its native `Microsoft.Windows.SDK.NET` reference, `10.0.26100.38`,
  conflicted with LibVLCSharp's `10.0.19041.38` — this was NETSDK1148's actual root cause, not a
  generic Windows-SDK-projection issue as line 9 below states from the stale decision-log reading);
  (2) removing the plain `LibVLCSharp` package reference per the already-known collision rule (this
  matches the settled invariant already correctly captured below, but the decision log's own
  `Tvivo.Playback.csproj` had regressed to referencing it again before this session's fix).
- **OBSERVED — The app was launched against the Gate 9 fixture** (`D:\Scratch\winui3-repro-rung5c\fixtures\thirtyfive-second-h264.mp4`, SHA-256 `0f34f35c...374f30fb`, unchanged). It reached
  `MainWindow constructor entered` → `MainWindow XAML initialized` → `MainWindow constructed;
  activating` → **`MainWindow activated`** per `tvivo-launch.log` — the furthest this app has ever
  gotten. This is further than anything recorded in `.spike-decision-log.md`.
- **NEW OPEN QUESTION, not in any repo record, genuinely new per this session's own decision-log-first
  check:** after activation, the process eventually surfaced a window titled **"VLC (Direct3D11
  output)"** — a known LibVLC-native default title, not a Tvivo-branded one (no `Title`/
  `AppWindow.Title` is set anywhere in `MainWindow.xaml`/`.xaml.cs`). Unresolved: whether this is the
  same HWND as the WinUI3 window (title merely overwritten, likely cosmetic) or a genuinely separate
  native output window (an actual `VideoView` embedding failure). `Play` was never clicked in this
  run — the gate stopped at this anomaly before testing playback, per this session's own instruction
  to stop and check documented records before treating anything as a new hypothesis; this check was
  performed and found no match, hence flagged here as genuinely new.
- **Recommendation, not yet done:** append this session's evidence (the FFmpegInteropX/SDK-generation
  root cause, the LibVLCSharp collision regression-and-refix, the successful build, and the
  `MainWindow activated` milestone) to `.spike-decision-log.md` itself in a future authorized step,
  so it becomes the persistent record future sessions read — this document alone is not a substitute
  for that, since other tooling/sessions may read the decision log directly rather than this new file.

### Correction to the correction: the VLC-window-title question is RETRACTED, not open

The "VLC (Direct3D11 output)" separate-window finding recorded immediately below (and reported as an
open question in an earlier version of this document) **could not be reproduced and is retracted.**
A dedicated read-only window-identity investigation (2026-09-19, same session) launched the app
without clicking Play and enumerated all top-level windows strictly filtered by
`GetWindowThreadProcessId` matching the freshly-launched PID, using raw Win32 P/Invoke
(`EnumWindows`/`GetWindowThreadProcessId`/`GetParent`/`GetWindow(GW_OWNER)`/`GetWindowLong`/
`GetWindowRect`). **Three independent fresh runs** (two by Claude directly, one by Codex re-verifying
its own prior claim after being challenged) **found only one top-level window: "WinUI Desktop"**
(`WinUIDesktopWin32WindowClass`), plus its IME/input-sink child windows — **no separate VLC window,
no "VLC video output" class, in any of the three runs.** Codex's original report of a separate
top-level `0x3808CA` "VLC (Direct3D11 output)" window was traced to a likely stale-PID or
foreground-window mix-up in that first (uncorroborated, single-attempt) run, and was explicitly
retracted by Codex on re-verification: `GetWindowThreadProcessId` on the freshly-launched PID in the
retry returned only the WinUI Desktop/IME windows, matching Claude's independent result exactly.
**Conclusion: there is currently no evidence of a `VideoView`-embedding failure.** The app launches
to a single WinUI3 top-level window with no observed separate native output surface — consistent
with, though not proof of, correct inline embedding. `Play` has still never been clicked in any run
this session, so whether video actually renders inline once playback starts remains untested (see
"Implemented-but-unverified behavior" below, which is accurate and unaffected by this retraction).

### Final correction: manual acceptance run CONFIRMS a real, Play-triggered embedding defect

The retraction immediately above was correct about the **pre-Play** state but incomplete: it never
tested what happens **after** `Play` is clicked, because `Play` had never been clicked in this
session until this gate. Two independent, fully-automated, self-scripted trials (2026-09-19, same
session, run directly, not delegated) resolved this definitively:

- **OBSERVED — Pre-Play (activation through ~5s):** exactly one top-level window exists,
  `WinUI Desktop` (`WinUIDesktopWin32WindowClass`), confirmed via raw Win32 `EnumWindows` strictly
  filtered by `GetWindowThreadProcessId` against each trial's own freshly-launched PID. Matches the
  retraction above — no window exists at this stage.
- **OBSERVED — Automated `Play` action:** `PathBox` set via UI Automation to
  `file:///D:/Scratch/winui3-repro-rung5c/fixtures/thirtyfive-second-h264.mp4` (Gate 9 fixture,
  SHA-256 `0f34f35c...374f30fb`, re-verified unchanged immediately before both trials), `Play` button
  invoked via UI Automation `InvokePattern`.
- **OBSERVED — Post-Play, reproduced identically in both trials:** a **separate top-level window**
  titled `"VLC (Direct3D11 output)"` (class `"VLC video main <hex>"`) appears **~1–2.6 seconds after
  Play**, with `Parent=0x0` and `Owner=0x0` — confirmed to have **no relation whatsoever** to the
  `WinUI Desktop` window, both times. Screenshots (`windows-spike/trial-1-screenshot.png`,
  `windows-spike/trial-2-screenshot.png`, preserved as evidence) show this window **actually
  rendering the real, moving SMPTE color-bar test-pattern video from the fixture.**
- **OBSERVED — `StatusText` could not be reliably read via UI Automation in either trial** (a tooling
  gap in the probing script, not a claim about the app itself — likely because
  `AutomationElement.FromHandle` needs the correct `WinUI Desktop` HWND specifically, and the probing
  script's HWND resolution had a bug). This is a genuine observability gap, not evidence against
  playback working.
- **Codex's own independent cross-check attempt failed on tooling bugs** (wrong log path assumed,
  PowerShell syntax errors) and **honestly reported it could not produce a valid reproduction**
  rather than asserting unearned agreement — correctly not counted as either confirming or refuting
  evidence.

**Verdicts:**
- **Engine Acceptance: PASS (strong indirect evidence).** Real, moving video content from the actual
  fixture is visible on screen — decoding and playback are genuinely working. Per `VlcPlaybackEngine`'s
  own code, visible frames require the native `Vout` event to have fired with `player.VoutCount > 0`,
  so this is strong (if indirect, since `StatusText`'s literal `"FirstFrame"` value was not captured)
  evidence that engine-level playback succeeded.
- **Visual Acceptance (inline embedding): FAIL.** Video renders in a separate, floating, unrelated
  native window — not inside `VideoHost` in the WinUI3 app window as the architecture requires. This
  is a real, reproducible `VideoView`/WinUI3 embedding defect that manifests only once playback
  starts (not at construction/activation time), which is exactly why every pre-Play-only check
  (including the retraction above) missed it.

**This supersedes the "no evidence of embedding failure" conclusion above — there IS evidence, and it
is strong. The retraction was correct only for the pre-Play window state; it did not and could not
address the post-Play state that this gate finally tested.**

## Settled invariants

- **OBSERVED — The plain `LibVLCSharp`/`LibVLCSharp.WinUI` assembly collision is a solved packaging rule:** the decision log records that both packages wrote `LibVLCSharp.dll` with different contents, and that the corrected package substitution kept only `LibVLCSharp.WinUI`; see `.spike-decision-log.md:279-307`. The current playback project has `LibVLCSharp.WinUI` but no plain `LibVLCSharp`; see `windows-spike/src/Tvivo.Playback/Tvivo.Playback.csproj:7-12`.
- **OBSERVED — The solution has explicit x64 build mappings for the Windows App and Playback projects:** `Debug|x64` and `Release|x64` have `Build.0` entries for both projects; see `windows-spike/Tvivo.sln:22-56`. Any future Windows build gate must use the solution’s x64 configuration rather than assuming `Any CPU` is equivalent.
- **OBSERVED — The historical `NETSDK1148` cause was a Windows SDK projection/version-alignment conflict, not a generic .NET SDK requirement:** the recorded chain is `FFmpegInteropX` → `FFmpegInteropX.Desktop.Lib` → `Microsoft.Windows.CsWinRT` → Windows SDK projection revision; see `.spike-decision-log.md:175-188`.
- **OBSERVED — Gate 9 measured LibVLC engine-level output on an isolated stage-4 topology:** 15/15 completed runs reached `Playing`, `VoutCount=1`, advancing `Position`, and one video track for an adequate-duration synthetic H.264 fixture; see `.spike-decision-log.md:468-490` and `CONSTITUTION-DRAFT.md:15-29`. This does not prove visible pixels or provider-stream compatibility.
- **OBSERVED — The Constitution is a planning document, not authorization:** it explicitly says it does not authorize implementation, package changes, deployment, or a claim that playback is visually proven; see `CONSTITUTION-DRAFT.md:1-11`.

## Current architecture

- **OBSERVED — The solution contains four source projects and four test projects:** `Tvivo.App`, `Tvivo.Core`, `Tvivo.Infrastructure`, `Tvivo.Playback`, plus `Tvivo.Core.Tests`, `Tvivo.Infrastructure.Tests`, `Tvivo.Playback.Tests`, and `Tvivo.App.Tests`; see `windows-spike/Tvivo.sln:5-19`.
- **OBSERVED — `Tvivo.Core` targets `net10.0` and `net9.0` with nullable and implicit usings enabled;** see `windows-spike/src/Tvivo.Core/Tvivo.Core.csproj:1-6`.
- **OBSERVED — Core contracts are provider-neutral records/interfaces:** provider endpoint/account/connection, authentication results, catalog records, `StreamSource`/`StreamKind`, `IPlaybackEngine`, playback results/session tokens, and credential storage are defined in `windows-spike/src/Tvivo.Core/Contracts.cs:3-100`.
- **OBSERVED — `PlaybackService` serializes play/stop operations, stops the prior session before starting a new generation, and clears a non-`FirstFrame` session;** see `windows-spike/src/Tvivo.Core/PlaybackService.cs:3-47`.
- **OBSERVED — `Tvivo.Infrastructure` targets `net10.0` and `net9.0`, references Core, and packages `Microsoft.Data.Sqlite` 10.0.12 and `System.Security.Cryptography.ProtectedData` 10.0.0;** see `windows-spike/src/Tvivo.Infrastructure/Tvivo.Infrastructure.csproj:1-11`.
- **OBSERVED — Infrastructure contains `XtreamCatalogProvider`, URL/account helpers, and an unimplemented `DpapiCredentialStore`;** see `windows-spike/src/Tvivo.Infrastructure/XtreamInfrastructure.cs:9-246`.
- **OBSERVED — The current playback implementation is a single `VlcPlaybackEngine` in the file still named `WindowsPlaybackEngine.cs`;** that file currently contains only `VlcPlaybackState`, `VlcPlaybackStateChangedEventArgs`, `VlcPlaybackEngine`, and its nested `EventHandlers`; see `windows-spike/src/Tvivo.Playback/WindowsPlaybackEngine.cs:1-278`.
- **OBSERVED — `VlcPlaybackEngine` owns `LibVLC`, `VideoView`, `Media`, and `MediaPlayer`; it exposes `View`, implements `IPlaybackEngine`, and maps Preparing/Playing/Failed/Completed/Stopping state;** see `windows-spike/src/Tvivo.Playback/WindowsPlaybackEngine.cs:7-43` and `:183-277`.
- **OBSERVED — `StartAsync` requires `StreamSource.DirectUri`, attaches the player to the view, subscribes events before `Play()`, waits up to ten seconds for completion, and returns a normalized result;** see `windows-spike/src/Tvivo.Playback/WindowsPlaybackEngine.cs:28-121`.
- **OBSERVED — `App.ConfigureServices` registers the catalog provider, credential store, concrete `VlcPlaybackEngine`, `IPlaybackEngine` resolving to that same singleton, and `PlaybackService`;** see `windows-spike/src/Tvivo.App/App.xaml.cs:73-82`.
- **OBSERVED — `MainWindow` resolves `PlaybackService` and `VlcPlaybackEngine` from `App.Services`, puts `_engine.View` into `VideoHost.Content`, and stops/disposes playback on window close;** see `windows-spike/src/Tvivo.App/MainWindow.xaml.cs:9-24` and `:46-76`.
- **OBSERVED — Play parses the textbox as an absolute URI and calls `PlaybackService.PlayAsync` with a movie `StreamSource` whose `DirectUri` is that URI; Stop calls `PlaybackService.StopAsync`;** see `windows-spike/src/Tvivo.App/MainWindow.xaml.cs:27-44`.
- **OBSERVED — `MainWindow.xaml` uses a `ContentControl` named `VideoHost` in the main row and a lower row containing `PathBox`, Play, Stop, and status controls;** see `windows-spike/src/Tvivo.App/MainWindow.xaml:5-25`.
- **OBSERVED — `App.xaml` defines only the local `AppPanelSurfaceBrush` resource used by `VideoHost`;** see `windows-spike/src/Tvivo.App/App.xaml:1-10`.

## Implemented-but-unverified behavior

- **OBSERVED — The LibVLC playback body, DI swap, host wiring, and corrected smoke test are present on disk;** see `windows-spike/src/Tvivo.Playback/WindowsPlaybackEngine.cs:28-277`, `windows-spike/src/Tvivo.App/App.xaml.cs:73-82`, `windows-spike/src/Tvivo.App/MainWindow.xaml.cs:17-23`, and `windows-spike/tests/Tvivo.Playback.Tests/PlaybackSmokeTests.cs:7-47`.
- **OBSERVED — The decision log records that this corrected three-file code scope was source-reviewed and accepted, but no build, test, run, or commit had occurred at that gate;** see `.spike-decision-log.md:542-562` and `:573-584`.
- **HYPOTHESIZED — Whether the current app renders video visibly inside the inline `VideoHost` is unverified:** the source assigns a `VideoView` to a WinUI `ContentControl`, but no current-repo runtime visual check is recorded; see `windows-spike/src/Tvivo.App/MainWindow.xaml.cs:17-23`, `windows-spike/src/Tvivo.App/MainWindow.xaml:11-13`, and `.spike-decision-log.md:573-579`.
- **HYPOTHESIZED — The callback-versus-teardown TOCTOU risk remains unresolved:** the decision log says a native callback may pass its invalidation check while disposal proceeds, and that no callback-drain synchronization exists; see `.spike-decision-log.md:563-572`.
- **HYPOTHESIZED — Real provider streams, non-synthetic containers/codecs, and visual pixel correctness remain unverified even though the isolated Gate 9 engine milestone passed;** see `.spike-decision-log.md:479-490` and `CONSTITUTION-DRAFT.md:22-29`.

## Removed/dead paths

- **OBSERVED — The former `MediaPlayerElement`/FFmpegInteropX implementation is no longer the current source:** the current `WindowsPlaybackEngine.cs` contains LibVLC types only, and `Tvivo.Playback.csproj` contains no FFmpegInteropX reference; see `windows-spike/src/Tvivo.Playback/WindowsPlaybackEngine.cs:1-278` and `windows-spike/src/Tvivo.Playback/Tvivo.Playback.csproj:7-12`.
- **OBSERVED — The old implementation’s removal was the result of the accepted three-file LibVLC slice, which replaced the previous body in place rather than creating a new engine file;** see `.spike-decision-log.md:518-533` and `:542-548`.
- **OBSERVED — Plain `LibVLCSharp` is absent from the current playback package graph;** only `LibVLCSharp.WinUI` 3.10.1 and `VideoLAN.LibVLC.Windows` 3.0.23.1 remain; see `windows-spike/src/Tvivo.Playback/Tvivo.Playback.csproj:7-12`.
- **INFERRED — Do not re-add the old `WindowsPlaybackEngine`/FFmpegInteropX path or plain `LibVLCSharp` package without a new authorized decision:** current source/package state has replaced those paths, and the collision rule requires the WinUI package alone; see the preceding source citations and `.spike-decision-log.md:279-307`.
- **OBSERVED — `LibVLCSharp.WinUI` itself is not removed from the current source:** it remains a live package reference and is used by `VlcPlaybackEngine`; see `windows-spike/src/Tvivo.Playback/Tvivo.Playback.csproj:9-11` and `windows-spike/src/Tvivo.Playback/WindowsPlaybackEngine.cs:1-3`.

## Package/toolchain constraints

- **OBSERVED — `Tvivo.App` is an unpackaged WinUI executable targeting `net9.0-windows10.0.19041.0`, with minimum platform 17763, `WindowsPackageType=None`, and explicit x86/x64/ARM64 RIDs;** see `windows-spike/src/Tvivo.App/Tvivo.App.csproj:1-25`.
- **OBSERVED — The App project pins `Microsoft.WindowsAppSDK` 2.5.1 and `Microsoft.Extensions.DependencyInjection` 10.0.0;** see `windows-spike/src/Tvivo.App/Tvivo.App.csproj:32-38`.
- **OBSERVED — The Playback project pins `Microsoft.WindowsAppSDK` 2.5.1, `LibVLCSharp.WinUI` 3.10.1, and `VideoLAN.LibVLC.Windows` 3.0.23.1;** see `windows-spike/src/Tvivo.Playback/Tvivo.Playback.csproj:7-12`.
- **OBSERVED — `NuGet.config` clears inherited package sources and permits only nuget.org;** see `windows-spike/NuGet.config:1-7`.
- **OBSERVED — The app has `GenerateLibraryLayout`, `ProjectPriFileName=resources.pri`, disabled Windows App SDK bootstrap initialization, `WindowsAppSDKSelfContained=true`, and `SelfContained=false`;** see `windows-spike/src/Tvivo.App/Tvivo.App.csproj:4-25`.
- **OBSERVED — The app manifest is the minimal unpackaged assembly manifest and no launch profile file exists in the scanned `windows-spike` project tree;** see `windows-spike/src/Tvivo.App/app.manifest:1-4` and the project-tree search performed for this audit.
- **OBSERVED — No repository `global.json` pin was recorded by the toolchain audit in the decision log;** see `.spike-decision-log.md:197-203`. The actual machine-installed SDK version was not re-queried in this read-only documentation task.
- **INFERRED — Windows build/test gates must use x64 because the solution maps the Windows projects’ build configurations to x64 and the app selects `win-x64` for `Platform=x64`;** see `windows-spike/Tvivo.sln:22-56` and `windows-spike/src/Tvivo.App/Tvivo.App.csproj:10-15`.

## Known prior traps that must not be rediscovered

- **OBSERVED — Unpackaged WinUI Fluent/theme-resource initialization produced the historical `0xC000027B`/`XamlParseException` investigation; four app-code workarounds failed and are closed;** see `.spike-decision-log.md:107-147` and `windows-spike/WINUI3-PLAYBACK-HANDOFF-REPORT.md:25-80`.
- **OBSERVED — The historical MSIX experiment changed the failure mode but produced no visible window or diagnostic log, so it is not proof of a fix;** see `.spike-decision-log.md:76-101` and `windows-spike/WINUI3-PLAYBACK-HANDOFF-REPORT.md:73-75`.
- **OBSERVED — Adding plain `LibVLCSharp` beside `LibVLCSharp.WinUI` silently deployed the wrong same-named DLL; use package substitution, not both packages;** see `.spike-decision-log.md:279-307`.
- **OBSERVED — A harness topology error previously ran `BareWindow` rather than `MainWindow`, so earlier claims that a `VideoView` had been instantiated in that ladder were corrected;** see `.spike-decision-log.md:370-401`.
- **OBSERVED — For LibVLC crash/debug gates, second-chance `cdb` handling and serialized process waiting were required; first-chance handling and non-blocking process invocation produced misleading evidence;** see `.spike-decision-log.md:410-433`.
- **OBSERVED — A one-second fixture plus ten-second observation window gave a false-looking no-`Vout` result; an adequate-duration fixture and longer observation resolved it, but only at engine level;** see `.spike-decision-log.md:450-490`.
- **OBSERVED — The corrected engine retains a parameterless constructor, and the playback smoke test checks interface assignability without opening a stream;** see `windows-spike/tests/Tvivo.Playback.Tests/PlaybackSmokeTests.cs:7-13` and `:31-46`.
- **OBSERVED — No test in the current playback smoke file plays media:** its second test uses an in-memory `RecordingEngine` to verify generation ordering and prior-session stop; see `windows-spike/tests/Tvivo.Playback.Tests/PlaybackSmokeTests.cs:15-29`.
- **OBSERVED — A single-attempt window-enumeration claim (a separate "VLC (Direct3D11 output)" top-level window) was RETRACTED, then RE-CONFIRMED with strong reproducible evidence, because the first three verification runs never clicked Play;** the window is real and appears ~1-3s AFTER Play, not before — always test both pre-Play and post-Play states before drawing a conclusion about UI/window behavior in this app, since construction-time and playback-time behavior differ. See "Final correction: manual acceptance run CONFIRMS..." above this section. This is now a CONFIRMED FACT, not an open question — do not re-investigate whether the window exists; the open question is only its root cause (see "Open questions" below).
- **OBSERVED — The repository’s Constitution, handoff, and session-handoff records are filename matches for the requested learned/POC/evidence search:** `CONSTITUTION-DRAFT.md`, `handoff.md`, `SESSION-HANDOFF-2026-09-16.md`, and `windows-spike/WINUI3-PLAYBACK-HANDOFF-REPORT.md`; no filename containing `learned`, `lesson`, or `POC` was found by the audit’s filename search.
- **OBSERVED — The root `CLAUDE.md` points to `PROJECT-BIBLE.md`, `PRODUCT-BIBLE.md`, and `TODO.md`, while the user-provided `todo-tree/00-overview.md` and `bible-detail/claude-07-agent-division.md` paths do not exist at the repo root;** see `CLAUDE.md` and the audit’s direct path checks.

## Open questions

- **OBSERVED — The final shipping backend remains undecided in the Constitution:** it explicitly keeps LibVLCSharp, FFmpegInteropX, native `MediaPlayerElement`, libmpv, and other backends open pending visual and real-media gates; see `CONSTITUTION-DRAFT.md:31-51`.
- **RESOLVED — root cause of the Play-triggered embedding failure found and independently confirmed
  (2026-09-19, same session), source-level, read-only.** `LibVLCSharp.WinUI` 3.10.1's own XML docs
  (`D:\devcache\nuget\libvlcsharp.winui\3.10.1\lib\net9.0-windows10.0.19041\LibVLCSharp.xml`,
  member `P:LibVLCSharp.Platforms.Windows.VideoViewBase.SwapChainOptions`) state, verbatim: *"Gets
  the swapchain parameters to pass to the `LibVLC` constructor. **If you don't pass them to the
  `LibVLC` constructor, the video won't be displayed in your application.** Calling this property
  will throw an `InvalidOperationException` if the `VideoView` is not yet fully Loaded."* There is
  also a documented `VideoView<T>.Initialized` event that fires "when the `VideoView` is fully
  loaded and the `SwapChainOptions` property is set" — the intended integration pattern is: wait for
  `Initialized`, read `SwapChainOptions`, construct `LibVLC` with them.
  **`windows-spike/src/Tvivo.Playback/WindowsPlaybackEngine.cs:33` constructs `LibVLC` eagerly, in a
  field initializer, with `Array.Empty<string>()` — no swapchain options, no wait for `VideoView`
  to load, no subscription to `Initialized` anywhere in the file (confirmed by grep: zero matches for
  `SwapChainOptions`/`Initialized` in the whole file).** This is an exact, documented match for the
  observed symptom and its timing: nothing breaks at construction/activation (no swapchain logic
  runs then), but once `Play()` actually starts rendering, LibVLC has no valid inline swapchain
  target and falls back to its own native Direct3D11 output window — precisely the
  `"VLC (Direct3D11 output)"` window confirmed in the manual acceptance gate above. Independently
  confirmed twice: Codex found and quoted the doc/IL evidence from the package XML; Claude
  independently re-read the exact same XML file directly and grepped the engine source to confirm
  the missing wiring — full agreement, verbatim match, no discrepancy.
  **This is a diagnosis only — no fix has been attempted or authorized.**
- **OBSERVED — Deployment-mode equivalence remains unproven:** the Constitution explicitly says the Windows `VideoView` result is not established across framework-dependent, self-contained, packaged, and unpackaged modes; see `CONSTITUTION-DRAFT.md:22-29`.
- **OBSERVED — The decision log contains conflicting historical scope statements that must not be collapsed:** its Gate 9 closure records engine-level LibVLC success on isolated synthetic media, while its later Section-15 acceptance still leaves visual acceptance, current-source compilation, runtime behavior, and teardown risk unresolved; see `.spike-decision-log.md:503-510` and `:563-584`.
- **HYPOTHESIZED — Visible inline rendering, real-stream behavior, and teardown safety are the material current runtime questions for this source state;** see `windows-spike/src/Tvivo.App/MainWindow.xaml.cs:22-23`, `windows-spike/src/Tvivo.Playback/WindowsPlaybackEngine.cs:81-115`, `.spike-decision-log.md:563-579`, and `CONSTITUTION-DRAFT.md:22-29`.

## Exact next authorized gate

**Superseded by this session's actual results — see the correction section at the top of this
document.** The decision-log-only reading below is retained for citation completeness but is stale;
do not treat it as the current handoff.

- **OBSERVED (stale) — At the time `.spike-decision-log.md` was last written, no build/run had
  occurred and the proposed compile-and-manual-run gate awaited authorization;** see
  `.spike-decision-log.md:573-579`.
- **OBSERVED (current, this session, FINAL) — The compile-and-manual-run gate is complete.** Compile
  succeeded (0 errors). The manual-run half reached `MainWindow activated`, `Play` was clicked
  (automated), and both acceptance verdicts are now determined: **Engine Acceptance: PASS**
  (real fixture video visibly decoding/rendering), **Visual Acceptance: FAIL** (renders in a
  separate, unrelated native window, not inline in `VideoHost`). See "Final correction: manual
  acceptance run CONFIRMS a real, Play-triggered embedding defect" above for full evidence.
- **INFERRED — The actual next authorized gate is a scoped, READ-ONLY source-level investigation**
  into why `LibVLCSharp.WinUI`'s `VideoView` fails to embed inline once `Play()` is called — inspect
  `VlcPlaybackEngine` construction, `VideoView` creation/configuration, `MainWindow`'s
  `VideoHost.Content = _engine.View` wiring, control lifecycle/template timing, and any relevant
  package/API assumptions, compared against known LibVLCSharp.WinUI integration patterns. Do NOT
  choose a replacement engine or propose an HWND-based architecture without further explicit
  authorization — this is diagnosis only. Not yet authorized as of this document's writing.

## 2026-09-19 correction — VideoView inline rendering VERIFIED; visual acceptance gap CLOSED

- **OBSERVED — The programmatically constructed `VideoView` defect is resolved.** `VideoView.Initialized`
  did not fire when `VlcPlaybackEngine` constructed the view and assigned it through
  `ContentControl.Content`; the internal template/swap-chain/initialized lifecycle was not entered.
  Option A now declares `VideoView` directly in `MainWindow.xaml` with `Initialized="VideoView_Initialized"`.
  The new code-behind handler calls `VlcPlaybackEngine.InitializeView(view, args)`. The engine no longer
  constructs the view; its `View` property throws until registration. `IPlaybackEngine`,
  `PlaybackService`, and DI wiring remain otherwise unchanged. LibVLC construction remains deferred
  until `Initialized`, preserving the bounded 10-second readiness timeout and diagnostic logging.
- **OBSERVED — Scope and runtime evidence:** the production change is exactly the three requested
  files; no packages, project files, tests, fixtures, or other files were touched by that implementation.
  The current worktree `git diff --numstat` reports XAML `6/4`, code-behind `7/1`, and playback engine
  `87/12` insertions/deletions. On the unchanged Gate 9 `thirtyfive-second-h264.mp4` fixture, startup
  logs show `Initialized`, two swap-chain options (`--winrt-d3dcontext` and `--winrt-swapchain`), then
  successful LibVLC construction before Play. After Play, status reached `FirstFrame`, gated by
  `VoutCount > 0`; PID-filtered, `IsWindowVisible`-filtered `EnumWindows` found only the main
  `WinUI Desktop` window before and after playback. Two screenshots three seconds apart had distinct
  SHA-256 hashes and visibly different decoder frame-number overlays, showing the SMPTE pattern
  rendering inside the app's `VideoView` with advancing playback.
- **Caveats —** advancing playback was established visually and by screenshot hashes, not by a direct
  `MediaPlayer.Position` sample; window enumeration excludes hidden/off-screen/zero-size windows; and
  this was one run, fixture, and session (no second Play, fresh-launch repeat, or alternate fixture).
- **Status — VERIFIED, uncommitted, ready for commit decision.** This closes the previously open
  Visual Acceptance gap and supersedes the stale next-gate text above; historical entries are retained.

## 2026-09-19 — Raw evidence from the earlier (separate, closed) engine-replacement investigation archived externally

- The `windows-spike/step2-publish-xaml-diagnostics/` and `windows-spike/step3b-artifacts/`
  directories hold raw evidence for the *earlier, already-closed* investigation that determined
  the original pre-LibVLC playback engine was unfixable (`WinUI3`'s `XamlControlsResources`
  initialization failure; see `.spike-decision-log.md` and `WINUI3-PLAYBACK-HANDOFF-REPORT.md`)
  — not the later inline-video-hosting defect resolved above. Neither directory was ever
  git-tracked, and neither is needed to reproduce or support the verified Option A result.
- Archived on 2026-09-19, verified byte-for-byte via SHA-256 before and after the move (nothing
  deleted): the three `.dmp` memory dumps from `step3b-artifacts/` (~1.38 GB) moved to
  `D:\Tvivo-archives\windows-spike-2026-09-19\step3b-artifacts\`, leaving the 20 narrative
  `.txt`/`.csv` transcripts (416,901 bytes) in place; the full 775-file
  `step2-publish-xaml-diagnostics/` tree (268,163,187 bytes) moved unchanged to
  `D:\Tvivo-archives\windows-spike-2026-09-19\step2-publish-xaml-diagnostics\`.
All existing conclusions and citations in this file and `.spike-decision-log.md` remain valid.

## 2026-09-19 — WinUI shell foundation

- **OBSERVED —** `Tvivo.App` now starts on a Home page hosted by a window-owned `ContentControl`;
  the existing Player surface remains in `MainWindow` so the XAML-declared `VideoView` and its
  `Initialized` handler remain the rendering endpoint. `HomePage` owns only its one-shot navigation
  intent; `MainWindow` owns the selected page and playback service. `ShellNavigationButton` is the
  shared, automation-labeled navigation control. App-level static resources define the palette and
  primary/navigation button styles.
- **OBSERVED — targeted WinUI evidence:** the app targets `net9.0-windows10.0.19041.0`, Windows
  App SDK 2.5.1, x64, self-contained unpackaged mode. The complete solution built with zero
  warnings/errors and all 18 existing tests passed. The app launched, UI Automation traversed Home
  and Player, and the hash-pinned Gate 9 fixture rendered in the inline `VideoView`; the status
  reached `FirstFrame`, whose existing engine gate requires `VoutCount > 0`. A screenshot of both
  Home and Player was visually inspected. This evidence applies only to this tested configuration.
- **INFERRED —** Keeping shell state in `MainWindow` while `HomePage` emits intent preserves the
  Constitution's boundary between UI controls and playback core without adding a routing package.
- **HYPOTHESIZED / unverified —** these controls/resources have not been checked in packaged mode,
  on another Windows App SDK/runtime revision, or at other display scales. The history of
  `XamlControlsResources` failures remains relevant; this shell uses the app's existing resource
  model and adds no `XamlControlsResources` merge.
