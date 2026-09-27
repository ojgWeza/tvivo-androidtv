param(
    [ValidateSet('baseline','after','scenarios','scroll','monkey','heavy')]
    [string]$Phase = 'baseline',
    [int]$DurationMinutes = 1,
    [switch]$NoLaunch
)

$ErrorActionPreference = 'Continue'
$PerfRoot = $PSScriptRoot
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$RunDir = Join-Path $PerfRoot "$Phase-$Stamp"
New-Item -ItemType Directory -Path $RunDir -Force | Out-Null
$SamplePath = Join-Path $RunDir 'samples.csv'
$ActionPath = Join-Path $RunDir 'actions.csv'
$ExceptionPath = Join-Path $RunDir 'exceptions.csv'
$CliLog = Join-Path $RunDir 'winapp-debug-output.log'
$CliError = Join-Path $RunDir 'winapp-debug-error.log'
$LaunchLog = Join-Path $env:LOCALAPPDATA 'Tvivo\tvivo-launch.log'
$Project = Join-Path $RepoRoot 'src\Tvivo.App\Tvivo.App.csproj'

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class TvivoNative {
 [DllImport("user32.dll", SetLastError=true)] public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint msg, UIntPtr wParam, IntPtr lParam, uint flags, uint timeout, out UIntPtr result);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
 [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
 [DllImport("user32.dll")] public static extern uint GetGuiResources(IntPtr hProcess, uint uiFlags);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extraInfo);
 [DllImport("user32.dll", SetLastError=true)] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
 [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
}
'@

function Write-Action([string]$Name,[string]$Status,[double]$Ms=0,[string]$Detail='') {
    [pscustomobject]@{Time=(Get-Date).ToString('o');Action=$Name;Status=$Status;LatencyMs=[math]::Round($Ms,2);Detail=$Detail} |
        Export-Csv -Path $ActionPath -Append -NoTypeInformation
}

function Get-TargetProcess {
    Get-Process -Name 'Tvivo.App' -ErrorAction SilentlyContinue |
        Sort-Object StartTime -Descending | Select-Object -First 1
}

function Get-TargetWindow([int]$TargetProcessId) {
    $p = Get-Process -Id $TargetProcessId -ErrorAction SilentlyContinue
    if ($null -ne $p -and $p.MainWindowHandle -ne [IntPtr]::Zero) { return $p.MainWindowHandle }
    return [IntPtr]::Zero
}

function Get-UiaRoot([int]$TargetProcessId) {
    $h = Get-TargetWindow $TargetProcessId
    if ($h -eq [IntPtr]::Zero) { return $null }
    return [System.Windows.Automation.AutomationElement]::FromHandle($h)
}

function Invoke-Uia([int]$TargetProcessId,[string]$Name,[string]$ControlType='Button') {
    $root = Get-UiaRoot $TargetProcessId
    if ($null -eq $root) { Write-Action "UIA:$Name" 'no-window'; return $false }
    $condition = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty,$Name)
    $element = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants,$condition)
    if ($null -eq $element) { Write-Action "UIA:$Name" 'not-found'; return $false }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $pattern=$null
        if ($element.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern,[ref]$pattern)) { $pattern.Invoke() }
        else { $element.SetFocus(); [System.Windows.Forms.SendKeys]::SendWait('{ENTER}') }
        $sw.Stop(); Write-Action "UIA:$Name" 'ok' $sw.Elapsed.TotalMilliseconds
        return $true
    } catch { $sw.Stop(); Write-Action "UIA:$Name" 'error' $sw.Elapsed.TotalMilliseconds $_.Exception.Message; return $false }
}

function Send-Key([string]$Key,[string]$Tag='key') {
    $sw=[Diagnostics.Stopwatch]::StartNew()
    [System.Windows.Forms.SendKeys]::SendWait($Key)
    $sw.Stop(); Write-Action "${Tag}:$Key" 'sent' $sw.Elapsed.TotalMilliseconds
}

function Sample-Process([System.Diagnostics.Process]$Proc,[double]$PreviousCpu,[datetime]$PreviousAt) {
    $Proc.Refresh()
    if ($Proc.HasExited) { return $null }
    $now=Get-Date; $cpu=$Proc.TotalProcessorTime.TotalSeconds
    $seconds=[math]::Max(.001,($now-$PreviousAt).TotalSeconds)
    $cpuPct=100*($cpu-$PreviousCpu)/$seconds/[Environment]::ProcessorCount
    $handle=$Proc.MainWindowHandle
    $pingMs=-1.0
    if ($handle -ne [IntPtr]::Zero) {
        $result=[UIntPtr]::Zero
        $pingSw=[Diagnostics.Stopwatch]::StartNew()
        $sent=[TvivoNative]::SendMessageTimeout($handle,0,[UIntPtr]::Zero,[IntPtr]::Zero,2,1800,[ref]$result)
        $pingSw.Stop(); if ($sent -ne [IntPtr]::Zero) { $pingMs=$pingSw.Elapsed.TotalMilliseconds } else { $pingMs=1800 }
    }
    $gdi=[TvivoNative]::GetGuiResources($Proc.Handle,0); $user=[TvivoNative]::GetGuiResources($Proc.Handle,1)
    $gpuUtil=0.0; $gpuDedicated=0.0; $gpuShared=0.0
    try {
        $engineRows=Get-CimInstance -Namespace root/cimv2 -ClassName Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -Filter "Name LIKE 'pid_$($Proc.Id)_%'"
        $gpuUtil=($engineRows | Measure-Object -Property UtilizationPercentage -Sum).Sum
        $memoryRows=Get-CimInstance -Namespace root/cimv2 -ClassName Win32_PerfFormattedData_GPUPerformanceCounters_GPUProcessMemory -Filter "Name LIKE 'pid_$($Proc.Id)_%'"
        if ($memoryRows) { $gpuDedicated=($memoryRows | Measure-Object -Property DedicatedUsage -Sum).Sum/1MB; $gpuShared=($memoryRows | Measure-Object -Property SharedUsage -Sum).Sum/1MB }
    } catch { }
    $dotnetHeap=-1; $gen0=-1; $gen1=-1; $gen2=-1
    try {
        $clr=Get-Counter -Counter '\.NET CLR Memory(Tvivo.App)\# Bytes in all Heaps','\.NET CLR Memory(Tvivo.App)\# Gen 0 Collections','\.NET CLR Memory(Tvivo.App)\# Gen 1 Collections','\.NET CLR Memory(Tvivo.App)\# Gen 2 Collections' -ErrorAction Stop
        foreach($c in $clr.CounterSamples) { switch -Regex ($c.Path) { 'Bytes in all Heaps$' {$dotnetHeap=$c.CookedValue/1MB}; 'Gen 0 Collections$' {$gen0=$c.CookedValue}; 'Gen 1 Collections$' {$gen1=$c.CookedValue}; 'Gen 2 Collections$' {$gen2=$c.CookedValue} } }
    } catch { }
    $responding=$false; try {$responding=$Proc.Responding} catch {}
    [pscustomobject]@{
        Time=$now.ToString('o');Scenario=$script:ScenarioName;Pid=$Proc.Id;CpuPctPerCore=[math]::Round($cpuPct,2);WorkingSetMB=[math]::Round($Proc.WorkingSet64/1MB,2);PrivateMB=[math]::Round($Proc.PrivateMemorySize64/1MB,2);
        ManagedHeapMB=$dotnetHeap;Gen0=$gen0;Gen1=$gen1;Gen2=$gen2;Threads=$Proc.Threads.Count;Handles=$Proc.HandleCount;GdiObjects=$gdi;UserObjects=$user;
        GpuUtilPct=[math]::Round($gpuUtil,2);GpuDedicatedMB=[math]::Round($gpuDedicated,2);GpuSharedMB=[math]::Round($gpuShared,2);
        UiPingMs=[math]::Round($pingMs,2);Responding=$responding;Minimized=([TvivoNative]::IsIconic($handle))
    }
}

function Start-Sampling([int]$TargetProcessId,[int]$Seconds) {
    $proc=Get-Process -Id $TargetProcessId -ErrorAction Stop; $proc.Refresh(); $prevCpu=$proc.TotalProcessorTime.TotalSeconds; $prevAt=Get-Date
    $end=(Get-Date).AddSeconds($Seconds)
    $nextSample=Get-Date
    while((Get-Date) -lt $end) {
        $nextSample=$nextSample.AddSeconds(1)
        $row=Sample-Process $proc $prevCpu $prevAt
        if ($null -eq $row) { break }
        $row | Export-Csv -Path $SamplePath -Append -NoTypeInformation
        $proc.Refresh(); $prevCpu=$proc.TotalProcessorTime.TotalSeconds; $prevAt=Get-Date
        $remaining=($nextSample-(Get-Date)).TotalMilliseconds
        if($remaining -gt 1){Start-Sleep -Milliseconds ([int]$remaining)} else {$nextSample=Get-Date}
    }
}

function Get-ExceptionSummary {
    if (!(Test-Path $CliLog)) { return }
    $matches = Select-String -Path $CliLog -Pattern '(?i)(first[- ]chance|exception|0x[0-9a-f]{8})' -AllMatches
    $groups=@{}
    foreach($match in $matches) {
        $line=$match.Line
        if ($line -match '(?<type>[A-Za-z_][A-Za-z0-9_.+`]*Exception)(?::|\s|$)') { $type=$Matches.type }
        elseif ($line -match '0x(?<hr>[0-9a-fA-F]{8})') { $type="HRESULT-$($Matches.hr)" }
        else { $type='Unclassified' }
        if (!$groups.ContainsKey($type)) { $groups[$type]=0 }; $groups[$type]++
    }
    foreach($type in $groups.Keys) { [pscustomobject]@{ExceptionType=$type;ObservedLogLines=$groups[$type];TopFrame='see winapp-debug-output.log'} | Export-Csv -Path $ExceptionPath -Append -NoTypeInformation }
}

function Set-Scenario([string]$Name) { $script:ScenarioName=$Name; Write-Action "scenario:$Name" 'begin' }

function Invoke-Sampled([string]$Name,[int]$Seconds) { Set-Scenario $Name; Start-Sampling $script:TargetPid $Seconds }

function Invoke-Mode([string]$Mode) {
    $watch=[Diagnostics.Stopwatch]::StartNew()
    $ok=Invoke-Uia $script:TargetPid $Mode
    $watch.Stop()
    if (!$ok) { Write-Action "mode:$Mode" 'failed' $watch.Elapsed.TotalMilliseconds }
    else { Write-Action "mode:$Mode" 'ok' $watch.Elapsed.TotalMilliseconds }
}

if (!$NoLaunch) {
    if (!(Get-Command winapp -ErrorAction SilentlyContinue)) { throw 'winapp is not available on PATH.' }
    $start=Get-Date
    $launcher=Start-Process -FilePath (Get-Command winapp).Source -ArgumentList @('run','./src/Tvivo.App/Tvivo.App.csproj','--arch','x64','--debug-output') -WorkingDirectory (Join-Path $RepoRoot 'windows-spike') -RedirectStandardOutput $CliLog -RedirectStandardError $CliError -PassThru
    $deadline=(Get-Date).AddMinutes(2); $target=$null
    while((Get-Date) -lt $deadline -and $null -eq $target) { Start-Sleep -Milliseconds 250; $target=Get-TargetProcess }
    if ($null -eq $target) { Write-Action 'startup' 'no-process' ((Get-Date)-$start).TotalMilliseconds; throw "Tvivo.App did not start. See $CliLog" }
    Write-Action 'startup-process' 'started' ((Get-Date)-$start).TotalMilliseconds "pid=$($target.Id)"
    $script:TargetPid=$target.Id
    $interactiveDeadline=(Get-Date).AddMinutes(2); $interactive=$false
    while((Get-Date) -lt $interactiveDeadline -and !$interactive) {
        $hwnd=Get-TargetWindow $script:TargetPid
        if ($hwnd -ne [IntPtr]::Zero) { $result=[UIntPtr]::Zero; $interactive=([TvivoNative]::SendMessageTimeout($hwnd,0,[UIntPtr]::Zero,[IntPtr]::Zero,2,1800,[ref]$result) -ne [IntPtr]::Zero) }
        if (!$interactive) { Start-Sleep -Milliseconds 100 }
    }
    Write-Action 'startup-interactive' $(if($interactive){'ok'}else{'timeout'}) ((Get-Date)-$start).TotalMilliseconds
} else {
    $target=Get-TargetProcess; if ($null -eq $target) { throw 'Tvivo.App is not running.' }; $script:TargetPid=$target.Id
}

$pidNow=$script:TargetPid
if ($Phase -in @('baseline','after')) {
    Set-Scenario 'idle-current-view'
    Start-Sampling $pidNow ([math]::Max(1,$DurationMinutes*60))
} elseif ($Phase -eq 'scenarios') {
    Set-Scenario 'S2-idle-My-Tvivo'; Start-Sampling $pidNow 300
    foreach($mode in @('Movies','Series','Live TV','My Tvivo')) { Invoke-Mode $mode; Start-Sleep -Seconds 2 }
    Set-Scenario 'S3-idle-Movies'; Invoke-Mode 'Movies'; Start-Sampling $pidNow 300
    Set-Scenario 'S4-mode-switch-x50'
    $durations=New-Object System.Collections.Generic.List[double]
    for($i=0;$i -lt 50;$i++) { $mode=@('My Tvivo','Movies','Series','Live TV')[$i%4]; $sw=[Diagnostics.Stopwatch]::StartNew(); Invoke-Uia $pidNow $mode | Out-Null; $sw.Stop(); $durations.Add($sw.Elapsed.TotalMilliseconds); Write-Action "S4:${i}:$mode" 'done' $sw.Elapsed.TotalMilliseconds; Start-Sampling $pidNow 1 }
    $sorted=@($durations | Sort-Object); Write-Action 'S4-summary' 'done' 0 "p50=$($sorted[[int][math]::Floor(($sorted.Count-1)*.50)]);p95=$($sorted[[int][math]::Floor(($sorted.Count-1)*.95)]);max=$(($durations|Measure-Object -Maximum).Maximum)"
    Set-Scenario 'S5-scroll-top-bottom-top-x20'; Invoke-Mode 'Movies'; for($i=0;$i -lt 20;$i++){ for($j=0;$j -lt 8;$j++){ Send-Key '{PGDN}' 'S5-down'; Start-Sampling $pidNow 1 }; for($j=0;$j -lt 8;$j++){ Send-Key '{PGUP}' 'S5-up'; Start-Sampling $pidNow 1 } }
    Set-Scenario 'S6-fullscreen-windowed-x30'; for($i=0;$i -lt 30;$i++){ Send-Key '{F11}' 'S6-toggle'; Start-Sampling $pidNow 1 }
    Set-Scenario 'S7-close'; Send-Key '%{F4}' 'S7-close'; $exitWatch=[Diagnostics.Stopwatch]::StartNew(); while($exitWatch.Elapsed.TotalSeconds -lt 5 -and (Get-Process -Id $pidNow -ErrorAction SilentlyContinue)){Start-Sleep -Milliseconds 100}; $exitWatch.Stop(); Write-Action 'S7-exit' $(if(!(Get-Process -Id $pidNow -ErrorAction SilentlyContinue)){'exited'}else{'still-running'}) $exitWatch.Elapsed.TotalMilliseconds
} elseif ($Phase -eq 'scroll') {
    [void][TvivoNative]::SetForegroundWindow((Get-TargetWindow $pidNow)); Invoke-Mode 'Movies'
    $rect=New-Object TvivoNative+RECT; [void][TvivoNative]::GetWindowRect((Get-TargetWindow $pidNow),[ref]$rect)
    $x=[int](($rect.Left+$rect.Right)/2); $y=[int](($rect.Top+$rect.Bottom)*.62); [void][TvivoNative]::SetCursorPos($x,$y)
    Set-Scenario 'S5-real-mousewheel-top-bottom-top-x20'
    for($cycle=0;$cycle -lt 20;$cycle++) {
        $sw=[Diagnostics.Stopwatch]::StartNew()
        for($n=0;$n -lt 24;$n++){[TvivoNative]::mouse_event(0x0800,0,0,[uint32]4294967176,[UIntPtr]::Zero)}
        Start-Sampling $pidNow 1
        for($n=0;$n -lt 24;$n++){[TvivoNative]::mouse_event(0x0800,0,0,120,[UIntPtr]::Zero)}
        $sw.Stop(); Write-Action "S5-wheel-cycle:$cycle" 'sent' $sw.Elapsed.TotalMilliseconds "x=$x;y=$y"; Start-Sampling $pidNow 1
    }
} elseif ($Phase -eq 'monkey') {
    $keys=@('{ESC}','{F11}','{ENTER}','{TAB}','{UP}','{DOWN}','{LEFT}','{RIGHT}',' ','{PGDN}','{PGUP}')
    $end=(Get-Date).AddMinutes($DurationMinutes); $rng=[Random]::new(50926)
    while((Get-Date) -lt $end) {
        $choice=$rng.Next(0,100)
        if($choice -lt 55) { Send-Key $keys[$rng.Next($keys.Length)] 'monkey'; Start-Sleep -Milliseconds ($rng.Next(40,300)) }
        elseif($choice -lt 82) { Send-Key '{F11}' 'monkey-toggle'; Start-Sleep -Milliseconds 120 }
        else {
            $hwnd=Get-TargetWindow $pidNow; [void][TvivoNative]::SetForegroundWindow($hwnd)
            $bounds=[System.Windows.Forms.Screen]::VirtualScreen.Bounds; $x=$bounds.Left+$rng.Next(0,[Math]::Max(1,$bounds.Width)); $y=$bounds.Top+$rng.Next(0,[Math]::Max(1,$bounds.Height))
            [void][TvivoNative]::SetCursorPos($x,$y); [TvivoNative]::mouse_event(2,0,0,0,[UIntPtr]::Zero); [TvivoNative]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
            Write-Action 'monkey-random-click' 'sent' 0 "x=$x;y=$y"
        }
        Start-Sampling $pidNow 1
        if (!(Get-Process -Id $pidNow -ErrorAction SilentlyContinue)) { Write-Action 'monkey' 'process-exited'; break }
    }
    Start-Sampling $pidNow 1
    Send-Key '%{F4}' 'monkey-final-close'
} elseif ($Phase -eq 'heavy') {
    $end=(Get-Date).AddMinutes($DurationMinutes)
    while((Get-Date) -lt $end) {
        foreach($mode in @('My Tvivo','Movies','Series','Live TV')) { Invoke-Uia $pidNow $mode; Start-Sleep -Seconds 2; Start-Sampling $pidNow 8 }
        Send-Key '{TAB}' 'heavy'; Send-Key '{ENTER}' 'heavy'; Start-Sampling $pidNow 15
        Send-Key '{ESC}' 'heavy'; Start-Sampling $pidNow 15
        Start-Sampling $pidNow 30
    }
    Send-Key '%{F4}' 'heavy-close'
}

Get-ExceptionSummary
Write-Output "Run artifacts: $RunDir"
