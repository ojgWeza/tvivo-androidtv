# Tvivo WinUI 3 Playback Architecture — Handoff Report

**Purpose:** context for another AI/engineer picking up this work. Repo: `D:\HCode\Tvivo`,
spike code at `windows-spike/`. Full narrative log: `.spike-decision-log.md` (repo root).

## What this project is

Tvivo is an IPTV/Xtream player. Existing shipped apps: Android, and a Kotlin/Compose Windows
desktop app (`desktop/`) that plays video via a hand-written libmpv/JNA binding
(`desktop/src/main/kotlin/com/dev/tvivo/desktop/MpvPlayer.kt`, `MpvLibrary.kt`) — this is a
**proven, working reference implementation** for mpv-based playback of IPTV streams, resume
tracking, fullscreen, and event handling.

`windows-spike/` is a from-scratch WinUI 3 + .NET 9/10 vertical-slice spike evaluating whether a
provider-neutral core with a WinUI 3 UI is a sound foundation for a Windows rewrite. Deployment
target is **unpackaged** (non-MSIX) Win32, i.e. `WindowsPackageType=None`, no package identity.

Playback is behind an `IPlaybackEngine` abstraction:
- `windows-spike/src/Tvivo.Core/Contracts.cs:89` — interface, exposes `StartAsync`/`StopAsync`.
- `windows-spike/src/Tvivo.Playback/WindowsPlaybackEngine.cs` — current impl, currently uses
  `MediaPlayerElement` (Windows Media Foundation).
- `windows-spike/src/Tvivo.Playback/Tvivo.Playback.csproj` — still references `LibVLCSharp` and
  `LibVLCSharp.WinUI` (both now considered dead ends, see below).

## Root cause found (closed, do not re-investigate)

**`LibVLCSharp.WinUI` is proven incompatible with unpackaged WinUI 3 on this machine/config.**
This was not a guess — it was proven via a full evidence chain:

1. Initial symptom: `MainWindow.InitializeComponent()` throws `XamlParseException 0x802B000A`
   intermittently (not every launch).
2. 20-run determinism test on one frozen `dotnet publish` output (byte-identical binaries,
   SHA256-verified across all runs) showed **genuine runtime nondeterminism**: ~18/20 runs
   exit with code `-1073741189` (`0xC000027B`, a native stowed/fast-fail exception), ~2/20 hang
   instead of crashing. All 124 loaded modules were identical between hang and crash runs
   (ASLR base addresses only differed) — ruled out a missing/extra DLL explanation.
3. Captured the real native stack via `cdb.exe`/WinDbgX attach-on-launch
   (`sxe -c2` second-chance exception handler, since first-chance `-c` doesn't fire the payload
   for a non-continuable stowed exception — this is the syntax gotcha, use `-c2`):
   ```
   KERNELBASE!RaiseFailFastException+0x188
   combase!RoFailFastWithErrorContextInternal2+0x520
   Microsoft_UI_Xaml!XamlCheckProcessRequirements+0xa2d
   Microsoft_UI_Xaml!DllMain+0xc4cf4
   ```
   Exact same offset as WER's independently-reported fault (`0x3A9DBD`), confirming this is the
   real fault site.
4. Researched `XamlCheckProcessRequirements`: **it's a red herring name.** A WindowsAppSDK
   maintainer confirmed (GitHub Discussion #2872) it's legacy dead code — it used to gate
   elevated-process support, became a no-op once elevation was supported, kept only for binary
   compatibility. It is NOT a package-identity/AppContainer/MSIX check.
5. The captured log right before the fail-fast shows a WinRT error:
   `Microsoft.Internal.WarpPal.dll ... 80004002 No such interface supported`. Combined with an
   earlier, separate throwaway-app finding (see `.spike-decision-log.md` lines ~61-75): a minimal
   code-only WinUI3 app with zero XAML files launches fine; the moment
   `Resources.MergedDictionaries.Add(new XamlControlsResources())` runs, it throws
   `COMException 0x80004005 — Cannot find a resource with the given key:
   AcrylicBackgroundFillColorDefaultBrush`. **Conclusion: the real root cause is WinUI 3's
   Fluent/XAML theme-resource initialization (`XamlControlsResources`) failing in this
   unpackaged environment; the fail-fast is a downstream symptom, not a separate bug.**
6. This exact pattern (unpackaged Win32 app + `XamlControlsResources` crash) is documented in
   Microsoft's own tracker: `microsoft-ui-xaml` issues **#7606** and **#9793**, both closed
   **"not planned."** No first-party fix exists.
7. **Four independent code-level workarounds were tried and all failed** — do not retry these:
   - Standard `XamlControlsResources` merge in `App.xaml`: 10/10 launches fail-fast.
   - Adding an explicit `AcrylicBackgroundFillColorDefaultBrush` override: still fails.
   - Deferring `XamlControlsResources` construction to `OnLaunched` instead of app init
     (a workaround referenced for a different, related issue #8151): still fails, same crash.
   - Removing `XamlControlsResources` entirely and hand-rolling local `Button`/`TextBox`/
     `ContentControl` control templates: avoids the *native* fail-fast, but `MainWindow.
     InitializeComponent()` now throws a *managed* `XamlParseException 0x802B000A` instead —
     the dependency on framework theme resources goes deeper than those three controls.
   - A prior, separate MSIX packaging experiment (see decision log) changed the failure mode
     (process stays alive, no crash) but produced no visible window and no diagnostic log at
     all — a different, still-unexplained dead end, not proof MSIX fixes the resource issue.

All experimental edits were reverted; only the pre-existing XAML diagnostics instrumentation
(`App.xaml.cs` — `IsXamlResourceReferenceTracingEnabled`, `XamlResourceReferenceFailed`/
`BindingFailed` handlers, an `XamlDiagnostics` build configuration with `FailFastOnErrors`) and a
useful cdb-syntax lesson remain. **Do not spend more time on this. It is closed.**

Artifacts from the investigation are under `windows-spike/step2-*` and
`windows-spike/step3b-artifacts/` (native dumps, module lists, cdb transcripts, WER event
captures, 20-run and 10-run CSV result tables).

## Decision made: replace the playback backend entirely

Since the crash is structurally tied to instantiating a XAML `UserControl` that depends on
`XamlControlsResources` (which is what `LibVLCSharp.WinUI` is), the fix is architectural, not a
patch: **use a playback backend that hosts video through a native Win32 child HWND instead of a
XAML control.** A raw HWND never touches the broken resource-resolution path.

### Options evaluated (full matrix with citations in prior conversation / can be regenerated)

| Option | Verdict |
|---|---|
| **libmpv via direct P/Invoke + native HWND embed (`wid`)** | **Recommended.** Real, documented mpv feature (`mpv-examples` libmpv README). Reuses the desktop app's proven mpv architecture almost directly. Excellent IPTV/codec/HLS/MPEG-TS coverage, HW accel, subtitle/audio track switching, fullscreen — all already demonstrated on desktop. **Only unverified part: WinUI 3 has no WPF-style `HwndHost` — child-HWND-in-Composition-tree behavior (z-order, resize, overlays) is not yet proven for this specific repo and must be spiked before full implementation.** |
| libmpv render API → SwapChainPanel/DXGI | Phase-2 fallback if native HWND embedding fails. Higher control over compositing but libmpv's typical render path is OpenGL vs. WinUI's DirectX-oriented composition — nontrivial bridging (see `Richasy/mpv-winui` on GitHub as a real prior-art reference). Lower priority than mpv+HWND. |
| Direct LibVLC P/Invoke (`libvlc_media_player_set_hwnd`), skip `LibVLCSharp.WinUI` entirely | Technically viable fallback, same native-HWND-in-WinUI3 unverified risk as mpv, but we have zero institutional VLC knowledge on this platform vs. full mpv knowledge from desktop. Not preferred — don't split effort across two native media stacks. |
| Flyleaf (FFmpeg + Direct3D, github.com/SuRGeoNix/Flyleaf) | Real project, real feature set (HW accel, HLS, subtitles). **Be skeptical of its WinUI3 support claim** — Flyleaf's best-proven surfaces are WPF/Avalonia; WinUI3 support is likely newer/thinner and unverified for unpackaged deployment specifically. Hold as a documented fallback only if HWND embedding is unworkable. |
| Windows Media Foundation / `MediaPlayerElement` (current impl) | Lowest deployment/maintenance cost, most naturally unpackaged-compatible, but Media Foundation's codec/container coverage is narrower than VLC/mpv/FFmpeg — weak fit for general Xtream/IPTV where providers use inconsistent codecs/containers. Keep only as a compatibility fallback for known-good streams, not the primary backend. |
| Custom FFmpeg + DirectX renderer (build our own) | Maximum control, maximum cost — demuxing, clocking, buffering, sync, device-loss handling all become Tvivo-owned. Not justified while mature players exist. |
| Separate native player window (not embedded) | Most isolated/reliable native boundary, but destroys UX (fullscreen transitions, monitor changes, window-ownership races become cross-window problems). Diagnostic/emergency fallback only. |

### Final recommendation (SUPERSEDED — see "2026-09-18 update" below)

~~Adopt libmpv via direct P/Invoke (not LibVLCSharp/LibVLCSharp.WinUI), hosted through a native
Win32 child HWND embedded in the WinUI 3 window, behind the existing `IPlaybackEngine` contract.~~

This was the conclusion of the *first* research pass. A later first-principles re-open (below)
revised this: native-HWND embedding avoids XAML entirely, but that framing under-weighted
architectures where XAML overlays, fullscreen, DPI, and hardware-accelerated video are first-class
*native* behavior of the hosting control, rather than something bridged after the fact. libmpv
native-HWND is retained as the proven fallback, not the primary plan — see below.

## 2026-09-18 update — first-principles re-open and current status

**Current lead candidate: FFmpegInteropX + native `MediaPlayerElement`**, not libmpv-HWND. Full
detail and evidence chain in `.spike-decision-log.md` (repo root) — this file only summarizes.

Key finding that drove the change: `windows-spike/src/Tvivo.Playback/WindowsPlaybackEngine.cs`
already instantiates `MediaPlayerElement` — a built-in WinUI3 framework control, not a
third-party XAML resource dictionary like `LibVLCSharp.WinUI` — in this exact unpackaged project,
and it has never hit the `XamlControlsResources` crash class. That's direct, first-party evidence
that the crash class is specific to third-party controls pulling in broken Fluent theme
resources, not to WinUI media rendering in general. This makes "keep the already-working
`MediaPlayerElement`, just widen its codec support via FFmpeg" the simplest architecture that
plausibly satisfies every requirement (XAML overlays, fullscreen, DPI, resize all stay ordinary,
already-proven WinUI behavior).

Ranking as of this update:
1. **FFmpegInteropX + `MediaPlayerElement`** — lead candidate, Gate 0 in progress (see below).
2. **libmpv via native child-HWND** — proven fallback; full reuse of
   `desktop/src/main/kotlin/com/dev/tvivo/desktop/MpvPlayer.kt`'s design; only unverified part is
   WinUI3 has no `HwndHost` equivalent, so child-HWND-in-Composition-tree overlay/z-order behavior
   is unproven here.
3. **Flyleaf** (`SuRGeoNix/Flyleaf`) — second fallback. Its `FlyleafHost` is a real WinUI
   `ContentControl` templated around a `SwapChainPanel`, genuinely XAML-native for
   overlay/resize/fullscreen — stronger than initially assessed. But its own docs label WinUI
   control support "Partially" (vs. mature WPF), and no unpackaged deployment evidence was found.
4. **`Richasy/mpv-winui`** (libmpv render-API/SwapChainPanel bridge) — downgraded out of
   contention. Real prior art for the OpenGL→DirectX bridging technique, but a thin project
   (6 commits, no issues, 12 stars), README never claims unpackaged support, and the same
   author's other WinUI app ships via MSIX (`Add-AppxPackage`), which is contrary evidence.

**Gate 0 status (go/no-go on FFmpegInteropX): BLOCKED on toolchain, not re-tested since.**
`FFmpegInteropX 2.1.0.81200` + `MediaPlayerElement` code is wired up in this branch, but the build
fails with `NETSDK1148` (a Windows SDK projection/version-alignment conflict between
`FFmpegInteropX.Desktop.Lib`'s `Microsoft.Windows.CsWinRT` dependency and the resolved SDK
projection — see `.spike-decision-log.md` for the full dependency chain). This is unrelated to
the closed `XamlControlsResources` crash — different component, different error class, confirmed
via a full read-only toolchain audit. **First thing to do when resuming: re-run the toolchain
audit post-Visual-Studio-update (dotnet SDK/runtime list, `vswhere` state, resolved
`Microsoft.Windows.CsWinRT`/`Microsoft.Windows.SDK.NET.Ref` versions) and retry the build** — the
VS update may resolve this on its own. If it still fails, fall back to Gate 0 on libmpv-HWND
instead of continuing to chase FFmpegInteropX toolchain alignment.

## 2026-09-18 update — the "unfixable at app-code level" claim below is PARTIALLY OVERTURNED

A same-day, outside-Tvivo re-investigation (`D:\Scratch\winui3-repro`, `D:\Scratch\winui3-libvlc-gate`,
full narrative in `.spike-decision-log.md`) found real, verified evidence that narrows this:

- The **self-contained** deployment case matches a real, documented, fixable Windows App SDK defect
  (`WindowsAppSDK#6720`: `dotnet publish` silently omits the app's own `.pri` file for unpackaged
  apps) — confirmed on disk (app `.pri` present in build output, absent from self-contained publish
  output) and matched to a moderator-confirmed community fix (`EnableMsixTooling=true`). **Not yet
  tested against our repro** — treat as a real, promising lead, not a confirmed fix.
- **Framework-dependent** deployment of plain `XamlControlsResources`/Acrylic usage is **clean, not
  crashing** (24/24 runs) — the original "any use of `XamlControlsResources` crashes" framing was
  too broad; the self-contained/framework-dependent distinction matters and wasn't isolated before.
- **However**, `LibVLCSharp.WinUI` specifically, under framework-dependent, still failed 10/10 in a
  fresh isolated Gate — and a same-session attempt to get a WinDbg native stack for it was blocked
  by newly-discovered environment nondeterminism (the LibVLC-free control app also started crashing
  after previously running clean). **This is unresolved, not disproven and not confirmed** — see
  `.spike-decision-log.md`'s 2026-09-18 section for the full evidence chain and the pending
  post-reboot baseline check.

**Do not treat `LibVLCSharp.WinUI` as cleared for use, and do not treat this as re-closed/dead
either.** The next owner should pick up at the post-reboot golden-vs-baseline check described in
the decision log before drawing further conclusions.

## Do NOT do these things (already closed / ruled out)

- Do not assume MSIX packaging is a shortcut fix — it changes the failure mode but was not
  proven to solve it (no window, no log, in the one experiment run).
- Do not build/test/deploy without presenting the change set first (standing project rule,
  see memory: "Ask before build or deploy").
- No JVM (Gradle/Kotlin daemons) running while any Windows-native build/emulator work happens on
  this machine — 16GB RAM, they don't coexist (memory: "No JVM while emulator runs" — analogous
  resource-contention caution applies to any heavy concurrent native build+debug session here).

## Suggested immediate next task for whoever picks this up

1. Re-run the read-only toolchain audit (dotnet SDK/runtime list, `vswhere` completeness state,
   physical Windows SDK folders, resolved `Microsoft.Windows.CsWinRT`/`Microsoft.Windows.SDK.NET.Ref`
   versions in `windows-spike/src/Tvivo.Playback/obj/project.assets.json`) now that the Visual
   Studio update on this machine has finished, to get a real baseline (the previous audit was run
   mid-update and is explicitly marked unstable).
2. Retry the Gate 0 build (`windows-spike/src/Tvivo.App/Tvivo.App.csproj`, unpackaged x64|Debug,
   `WindowsPackageType=None`) with the already-wired FFmpegInteropX + `MediaPlayerElement` code.
   If it now builds, launch and press Play on the already-configured test stream
   (`https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`) and report only: does first frame render,
   any crash/XAML-resource-class error. That's the entire Gate 0 criterion — do not test
   tracks/subtitles/reconnect/resize/fullscreen/DPI yet, that's Gate 1.
3. If Gate 0 still fails on the same `NETSDK1148` toolchain issue, stop chasing FFmpegInteropX
   version/SDK combinations and pivot Gate 0 to libmpv native-HWND embedding instead (see ranking
   above) — build a throwaway plain child HWND in the WinUI3 window first, confirm it resizes,
   z-orders correctly under overlays, and survives fullscreen/minimize/multi-monitor changes,
   before wiring in real mpv via `wid`.
