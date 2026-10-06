# Tvivo MSI

The WiX v5 project produces an unsigned x64 per-user MSI. It installs the complete
self-contained unpackaged publish directory at `%LOCALAPPDATA%\Programs\Tvivo`,
adds a Start Menu shortcut, and registers Tvivo in Apps & Features. The app's
user data remains at `%LOCALAPPDATA%\Tvivo`, outside the install folder. Releases use
manual MSI major upgrades; there is no in-app updater. The stable UpgradeCode is
`DE41D078-AB21-4CD3-8F5A-85D5C14B5992`.

The WiX `Files` harvest is retained as requested for the fixed per-user package.
WiX documents that per-user `Files` harvests fail ICE38; this build suppresses
ICE03 (its generated PE language metadata is not used for patching), ICE38 (file
key paths under the user's profile), and ICE64 (harvested nested directories).
ICE91 is suppressed because the package is fixed to per-user scope and is never
offered as a per-machine install.
These suppressions keep the explicit per-user/no-elevation decision, but do not
replace clean-machine install/uninstall validation.

The app/MSI version is owned by `../Version.props`. `build-msi.ps1` checks that
the app and installer resolve the same version and verifies the published app
metadata before building. NuGet restore uses a scratch config containing only
nuget.org. The script compares the published file manifest with the file table
harvested into the MSI, including relative paths.

`TVIVO_DATA_ROOT` is an optional diagnostic/test override for the shared Tvivo
data root. In this slice, diagnostics, credentials, catalog, and EPG paths use
the helper. The remaining direct lookups in `Tvivo.App/MainWindow.xaml.cs` and
`Tvivo.App/Pages/CatalogLandingPage.xaml.cs` still use the default LocalAppData
root; therefore the override is not yet safe for isolated app launches until
those call sites are migrated.

The installer checks Windows build 22621 or later and the x64 Visual C++ 2015–2022
runtime. Installing that shared runtime can require administrator access, so the
per-user MSI reports the Microsoft download link and stops without elevation when
the prerequisite is absent. It does not attempt to install the redistributable.

Uninstall always preserves `%LOCALAPPDATA%\Tvivo`. The optional data-delete
checkbox is deferred: MSI's standard removal transaction cannot safely promise
that arbitrary user files deleted by a custom action will be restored if removal
rolls back. A future custom uninstall front end can offer cleanup after the MSI
transaction commits. `TVIVO_DELETE_DATA` is reserved and defaults to `0`; it has
no cleanup action in this skeleton. Major upgrades and rollback therefore never
delete app data.

Use `build-msi.ps1 -ScratchRoot D:\Scratch\msi-slice3` to publish and build. The
script does not install the MSI.

## Windows 10 edition (separate package)

`build-msi.ps1 -Win10 -Version 1.0.1 -ScratchRoot D:\Scratch\msi-win10` builds a second,
side-by-side MSI from the same source: `Tvivo-<version>-win10-x64.msi`. It differs from the
Windows 11 package only by identity and OS floor:

| | Windows 11 (default) | Windows 10 (`-Win10`) |
|---|---|---|
| Product name | Tvivo | Tvivo (Windows 10) |
| UpgradeCode | `DE41D078-AB21-4CD3-8F5A-85D5C14B5992` | `7B3C5E0A-4D62-4F1B-9A87-2C6E1D90F4A3` |
| Install folder | `%LOCALAPPDATA%\Programs\Tvivo` | `%LOCALAPPDATA%\Programs\Tvivo Win10` |
| Minimum Windows build | 22621 | 19041 (Windows 10 2004) |
| App `TargetPlatformMinVersion` | 10.0.22621.0 | 10.0.19041.0 |

Because the UpgradeCode differs, neither package upgrades or removes the other, so both
can be installed together. Both share the user data root `%LOCALAPPDATA%\Tvivo` (catalog,
credentials), so running both against one account shares that state.
`-Version` overrides `../Version.props` for that build only; the Windows 11 package keeps
the `Version.props` value. The edition is selected by the `TvivoWin10` MSBuild property,
which `build-msi.ps1` passes to every restore/publish/build step. The Windows 10 package
is built on Windows 11 and is not yet verified on a Windows 10 machine: the source has no
known Windows 11-only API use, but a clean Windows 10 install/launch/uninstall check is
still required.
