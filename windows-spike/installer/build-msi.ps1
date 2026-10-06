[CmdletBinding()]
param(
    [string]$ScratchRoot = 'D:\Scratch\msi-slice3',
    # Separate side-by-side Windows 10 edition (own UpgradeCode, install folder, file name).
    [switch]$Win10,
    # Overrides Version.props for this build only (e.g. 1.0.1).
    [string]$Version
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$appProject = Join-Path $repoRoot 'windows-spike\src\Tvivo.App\Tvivo.App.csproj'
$wixProject = Join-Path $PSScriptRoot 'Tvivo.Installer.wixproj'
$nugetConfig = Join-Path $ScratchRoot 'NuGet.Config'
$artifactsPath = Join-Path $ScratchRoot 'artifacts'
$publishPath = Join-Path $ScratchRoot 'publish'
$installerOutput = Join-Path $ScratchRoot 'installer'
$feed = 'https://api.nuget.org/v3/index.json'
$editionProps = @()
if ($Win10) { $editionProps += '-p:TvivoWin10=true' }
if ($Version) { $editionProps += "-p:TvivoVersion=$Version" }

New-Item -ItemType Directory -Force -Path $ScratchRoot, $publishPath, $installerOutput | Out-Null
@'
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <clear />
    <add key="nuget.org" value="https://api.nuget.org/v3/index.json" />
  </packageSources>
</configuration>
'@ | Set-Content -LiteralPath $nugetConfig -Encoding UTF8

function Invoke-DotNet {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $logPath = Join-Path $ScratchRoot 'build-msi-last.log'
    & dotnet @Arguments @editionProps *> $logPath
    if ($LASTEXITCODE -ne 0) {
        Get-Content -LiteralPath $logPath | Select-String -Pattern 'error [A-Z]+\d+:|Build FAILED|failed with exit code' | Select-Object -First 8 | ForEach-Object { Write-Error $_.Line -ErrorAction Continue }
        throw "dotnet $($Arguments -join ' ') failed with exit code $LASTEXITCODE; log: $logPath"
    }
}

$appVersionOutput = @(& dotnet msbuild $appProject -getProperty:Version -p:Platform=x64 @editionProps -nologo)
if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the app version.' }
$appVersion = ([string]$appVersionOutput[-1]).Trim()
$wixVersionOutput = @(& dotnet msbuild $wixProject -getProperty:TvivoVersion @editionProps -nologo)
if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the MSI version.' }
$wixVersion = ([string]$wixVersionOutput[-1]).Trim()
if ($appVersion -ne $wixVersion) { throw "App version '$appVersion' does not match MSI version '$wixVersion'." }

Invoke-DotNet @('restore', $appProject, '--configfile', $nugetConfig, '--artifacts-path', $artifactsPath, '-p:Platform=x64', '-p:RuntimeIdentifier=win-x64', '-p:DisableImplicitLibraryPacksFolder=true')
Invoke-DotNet @('restore', $wixProject, '--configfile', $nugetConfig, ('-p:BaseIntermediateOutputPath=' + (Join-Path $ScratchRoot 'wix-obj\')))

$assetsFiles = @(
    (Get-ChildItem -LiteralPath $artifactsPath -Filter project.assets.json -File -Recurse)
    (Get-ChildItem -LiteralPath (Join-Path $ScratchRoot 'wix-obj') -Filter project.assets.json -File -Recurse)
)
if ($assetsFiles.Count -eq 0) { throw 'Restore produced no project.assets.json files.' }
foreach ($assetsFile in $assetsFiles) {
    $assets = Get-Content -LiteralPath $assetsFile.FullName -Raw | ConvertFrom-Json
    $sources = @($assets.project.restore.sources.PSObject.Properties.Name)
    if ($sources.Count -ne 1 -or $sources[0] -ne $feed) {
        throw "Unexpected NuGet sources in $($assetsFile.FullName): $($sources -join ', ')"
    }
}

Invoke-DotNet @('publish', $appProject, '-c', 'Release', '-p:Platform=x64', '--runtime', 'win-x64', '--self-contained', 'true', '--artifacts-path', $artifactsPath, '-o', $publishPath, '--no-restore', '-p:PublishReadyToRun=false', '-p:PublishTrimmed=false', '-p:PublishSingleFile=false', '-p:DisableImplicitLibraryPacksFolder=true')

$depsPath = Join-Path $publishPath 'Tvivo.App.deps.json'
if (-not (Test-Path -LiteralPath $depsPath)) { throw 'Published Tvivo.App.deps.json is missing.' }
$deps = Get-Content -LiteralPath $depsPath -Raw | ConvertFrom-Json
$appLibrary = @($deps.libraries.PSObject.Properties.Name | Where-Object { $_ -like "Tvivo.App/$appVersion*" })
if ($appLibrary.Count -ne 1) { throw "Published app metadata does not agree with version $appVersion." }
if (-not (Test-Path (Join-Path $publishPath 'Tvivo.App.exe'))) { throw 'Published Tvivo.App.exe is missing.' }

$publishManifest = @(Get-ChildItem -LiteralPath $publishPath -File -Recurse | ForEach-Object {
    [IO.Path]::GetRelativePath($publishPath, $_.FullName).Replace('/', '\')
} | Sort-Object)
if ($publishManifest.Count -eq 0) { throw 'Publish directory contains no files.' }

Invoke-DotNet @('build', $wixProject, '-c', 'Release', '--no-restore', "-p:PayloadDir=$publishPath", "-p:OutputPath=$installerOutput\", "-p:BaseIntermediateOutputPath=$ScratchRoot\wix-obj\", "-p:BaseOutputPath=$ScratchRoot\wix-bin\")
$msiPath = Join-Path $installerOutput "Tvivo-$wixVersion-x64.msi"
if (-not (Test-Path -LiteralPath $msiPath)) {
    $msiPath = Get-ChildItem -LiteralPath $installerOutput -Filter '*.msi' -File | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $msiPath) { throw 'WiX build produced no MSI.' }

# Read the MSI File/Component/Directory tables through Windows Installer automation
# and reconstruct paths below INSTALLFOLDER for an exact harvest comparison.
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase($msiPath, 0)
function Read-MsiTable {
    param([string]$Sql, [int]$Columns)
    $view = $database.OpenView($Sql)
    $null = $view.Execute()
    $rows = [Collections.Generic.List[object]]::new()
    while ($record = $view.Fetch()) {
        $row = [object[]]::new($Columns)
        for ($i = 1; $i -le $Columns; $i++) {
            try { $row[$i - 1] = $record.StringData($i) }
            catch [System.Runtime.InteropServices.COMException] { $row[$i - 1] = $null }
        }
        $null = $rows.Add([pscustomobject]@{ Values = $row })
    }
    $null = $view.Close()
    return ,$rows.ToArray()
}

$directoryRows = Read-MsiTable 'SELECT `Directory`, `Directory_Parent`, `DefaultDir` FROM `Directory`' 3
$componentRows = Read-MsiTable 'SELECT `Component`, `Directory_` FROM `Component`' 2
$fileRows = Read-MsiTable 'SELECT `File`, `Component_`, `FileName` FROM `File`' 3
$directories = @{}
foreach ($row in $directoryRows) {
    $defaultDirectory = [string]$row.Values[2]
    $separator = $defaultDirectory.LastIndexOf('|')
    $directoryName = if ($separator -ge 0) { $defaultDirectory.Substring($separator + 1) } else { $defaultDirectory }
    $directories[$row.Values[0]] = @{ Parent = $row.Values[1]; Name = $directoryName }
}
$components = @{}
foreach ($row in $componentRows) { $components[$row.Values[0]] = $row.Values[1] }
function Get-InstallRelativeDirectory([string]$directoryId) {
    $parts = [Collections.Generic.List[string]]::new()
    $current = $directoryId
    while ($current -and $current -ne 'INSTALLFOLDER') {
        if (-not $directories.ContainsKey($current)) { throw "MSI directory '$current' is not in the directory table." }
        $entry = $directories[$current]
        $parts.Insert(0, $entry.Name)
        $current = $entry.Parent
    }
    if ($current -ne 'INSTALLFOLDER') { throw "MSI file directory '$directoryId' is outside INSTALLFOLDER." }
    return ($parts -join '\')
}

$harvestedManifest = foreach ($row in $fileRows) {
    $componentId = $row.Values[1]
    if (-not $components.ContainsKey($componentId)) { throw "MSI file references missing component '$componentId'." }
    $directory = Get-InstallRelativeDirectory $components[$componentId]
    $fileName = [string]$row.Values[2]
    $separator = $fileName.LastIndexOf('|')
    $filename = if ($separator -ge 0) { $fileName.Substring($separator + 1) } else { $fileName }
    if ($directory) { "$directory\$filename" } else { $filename }
}
$harvestedManifest = @($harvestedManifest | Sort-Object)
$missing = @($publishManifest | Where-Object { $_ -notin $harvestedManifest })
$extra = @($harvestedManifest | Where-Object { $_ -notin $publishManifest })
if ($missing.Count -or $extra.Count -or $publishManifest.Count -ne $harvestedManifest.Count) {
    throw "MSI harvest mismatch. Publish files=$($publishManifest.Count), MSI files=$($harvestedManifest.Count), missing=$($missing.Count), extra=$($extra.Count)."
}

$publishManifest | Set-Content -LiteralPath (Join-Path $ScratchRoot 'publish-manifest.txt') -Encoding UTF8
$harvestedManifest | Set-Content -LiteralPath (Join-Path $ScratchRoot 'msi-harvest-manifest.txt') -Encoding UTF8
Write-Output "MSI: $msiPath"
Write-Output "Version: $wixVersion"
Write-Output "NuGet assets verified: $($assetsFiles.Count) files, nuget.org only"
Write-Output "Publish/MSI manifest match: $($publishManifest.Count) files"
