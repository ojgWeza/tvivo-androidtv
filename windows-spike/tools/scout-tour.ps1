<#
Scout tour (Debug only, no streams): launches the Debug Tvivo.App with SCOUT=1 (or attaches to a running one with -ProcessId),
visits the main pages, saves a screenshot + audit report per page and prints a one-line summary each.
Usage:  pwsh tools\scout-tour.ps1 [-OutDir D:\Scratch\scout-tour] [-ProcessId 1234] [-Baseline D:\Scratch\scout-tour\baseline] [-KeepOpen]
Never run while the owner has Tvivo open; never opens a stream. Reports contain control names and visible text only.
#>
param(
    [string]$OutDir = "$env:TEMP\scout-tour",
    [int]$ProcessId = 0,
    [string]$Baseline = '',
    [string]$Scout = 'D:\HCode\Scout\src\Scout.Cli\bin\Release\net10.0\scout.exe',
    [string]$Exe = "$PSScriptRoot\..\src\Tvivo.App\bin\x64\Debug\net9.0-windows10.0.22621.0\win-x64\Tvivo.App.exe",
    [switch]$KeepOpen
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $OutDir | Out-Null
$started = $null
if ($ProcessId -eq 0) {
    if (Get-Process Tvivo.App -ErrorAction SilentlyContinue) { throw 'Tvivo is already running; close it or pass -ProcessId.' }
    $env:SCOUT = '1'
    $started = Start-Process (Resolve-Path $Exe) -PassThru
    $ProcessId = $started.Id
}
for ($i = 0; $i -lt 30 -and -not (Test-Path "$env:TEMP\scout\$ProcessId.json"); $i++) { Start-Sleep 2 }
Start-Sleep 8
function Step($name, $selector) {
    if ($selector) { & $Scout click $ProcessId $selector | Out-Null; Start-Sleep 6 }
    & $Scout screenshot $ProcessId "$OutDir\$name.png" | Out-Null
    & $Scout audit $ProcessId --out "$OutDir\$name.json" | Out-Null
    $r = Get-Content "$OutDir\$name.json" -Raw | ConvertFrom-Json
    $by = $r.issues | Group-Object code | ForEach-Object { "$($_.Name)=$($_.Count)" }
    "{0,-14} {1}" -f $name, ($(if ($by) { $by -join ' ' } else { 'clean' }))
    if ($Baseline -and (Test-Path "$Baseline\$name.json")) {
        $d = & $Scout audit $ProcessId --diff "$Baseline\$name.json" | ConvertFrom-Json
        "               diff: new=$(@($d.new).Count) fixed=$(@($d.fixed).Count)"
    }
}
Step 'foryou' $null
Step 'movies' '[name="Movies"]'
Step 'series' '[name="Series"]'
Step 'live' '[name="Live TV"]'
Step 'settings' '[name="Settings"]'
Step 'dark-settings' '[name="Switch to dark theme"]'
if ($started -and -not $KeepOpen) { Stop-Process -Id $started.Id -Force -ErrorAction SilentlyContinue }
