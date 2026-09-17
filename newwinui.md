Fix the WinUI 3 unpackaged app error where:

`AcrylicBackgroundFillColorDefaultBrush`

cannot be resolved.

Requirements:

* Keep the app **unpackaged**. Do not convert to MSIX.
* First inspect `App.xaml`, resource dictionaries, Windows App SDK initialization, `XamlControlsResources`, and all references to the missing brush.
* Determine whether the real cause is Windows App SDK/bootstrap initialization, resource dictionary ordering, `XamlControlsResources`, or an unsafe dependency on a WinUI framework resource.
* Do not blindly add `XamlControlsResources` if its initialization is itself causing the exception.
* Fix the root cause with the smallest architectural change.
* Replace direct app dependencies on `AcrylicBackgroundFillColorDefaultBrush` with app-owned semantic resources such as `AppPanelSurfaceBrush` where appropriate.
* Keep whole-window backdrop (`SystemBackdrop`/Mica/Acrylic) separate from UI surface brushes.
* Do not modify playback architecture or add unnecessary libraries.
* Build and **run the actual unpackaged executable** after the fix. Compilation alone is not sufficient.
* Search afterward for remaining references to `AcrylicBackgroundFillColorDefaultBrush`.

Use **Codex as the primary coding/review agent**. Claude should orchestrate: inspect → diagnose → plan → delegate implementation to Codex → run/test → have Codex review the final diff.

At the end, briefly report:

1. root cause,
2. files changed,
3. fix applied,
4. build/runtime result.
