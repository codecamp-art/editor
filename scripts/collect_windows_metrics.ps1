param(
  [string[]]$ProcessNames = @("FixAdapter"),
  [string]  $OutDir       = "C:\perf",
  [int]     $IntervalSec  = 1,
  [int]     $DurationSec  = 0,
  [switch]  $EchoToConsole
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

$sysCsv  = Join-Path $OutDir "win_system.csv"
$procCsv = Join-Path $OutDir "win_process.csv"

function Write-Line { param([string]$Line,[string]$Path) Add-Content -Path $Path -Value $Line; if($EchoToConsole){Write-Host $Line} }

# CSV headers (human-friendly, aligned with Linux)
Write-Line "timestamp,cpu_total_percent,cpu_user_percent,cpu_system_percent,cpu_queue_length,context_switches_per_sec_total,context_switches_per_sec_per_core,mem_available_mb,mem_committed_mb,pages_per_sec,network_bytes_total_per_sec" $sysCsv
Write-Line "timestamp,process_name,process_id,cpu_percent,cpu_user_percent,cpu_system_percent,ctx_switches_per_sec_total,ctx_switches_per_sec_per_core,working_set_mb,private_bytes_mb,thread_count,handle_count,page_faults_per_sec" $procCsv

$logical = (Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors

# Rolling state for per-PID CPU splits
$prevUserSec = @{}  # pid -> cumulative user sec
$prevKernSec = @{}  # pid -> cumulative kernel sec
$prevTs      = @{}  # pid -> DateTime

# Build a map of PID -> Process-instance-name (handles name collisions)
function Get-ProcInstanceMap {
  $map = @{} # pid -> instanceName
  $samples = Get-Counter '\Process(*)\ID Process'
  foreach ($s in $samples.CounterSamples) {
    $pid = [int]$s.CookedValue
    $inst = ($s.Path -replace '^\\\\[^\\]+\\Process\(', '') -replace '\)\\ID Process$',''
    if ($pid -gt 0 -and -not $map.ContainsKey($pid)) { $map[$pid] = $inst }
  }
  return $map
}

function Get-SystemRow {
  $counters = Get-Counter -Counter @(
    '\Processor(_Total)\% Processor Time',
    '\Processor Information(_Total)\% User Time',
    '\Processor Information(_Total)\% Privileged Time',
    '\System\Processor Queue Length',
    '\System\Context Switches/sec',
    '\Memory\Available MBytes',
    '\Memory\Committed Bytes',
    '\Memory\Pages/sec',
    '\Network Interface(*)\Bytes Total/sec'
  )
  $ts = (Get-Date).ToUniversalTime().ToString("o")
  $val = @{}
  foreach ($s in $counters.CounterSamples) {
    $short = ($s.Path -replace '^\\\\[^\\]+', '').ToLower()
    $val[$short] = [double]$s.CookedValue
  }
  $netSum = ($counters.CounterSamples | Where-Object { $_.Path -like '*\Network Interface(*)\Bytes Total/sec*' } | Measure-Object -Property CookedValue -Sum).Sum
  if (-not $netSum) { $netSum = 0 }

  $cpuTotal   = $val['\processor(_total)\% processor time']
  $cpuUser    = $val['\processor information(_total)\% user time']
  $cpuKernel  = $val['\processor information(_total)\% privileged time']
  $queueLen   = $val['\system\processor queue length']
  $ctxSwitch  = $val['\system\context switches/sec']
  $ctxPerCore = if ($logical -gt 0) { $ctxSwitch / $logical } else { $ctxSwitch }
  $memAvailMB = $val['\memory\available mbytes']
  $commitMB   = ($val['\memory\committed bytes'] / 1MB)
  $pagesSec   = $val['\memory\pages/sec']

  # F-format: no thousands separators
  "{0},{1:F2},{2:F2},{3:F2},{4:F0},{5:F2},{6:F2},{7:F0},{8:F0},{9:F2},{10:F0}" -f `
    $ts,$cpuTotal,$cpuUser,$cpuKernel,$queueLen,$ctxSwitch,$ctxPerCore,$memAvailMB,$commitMB,$pagesSec,$netSum
}

function Get-ProcessRows {
  $tsNow = Get-Date
  $tsStr = $tsNow.ToUniversalTime().ToString("o")

  # collect matching processes
  $all = @()
  foreach ($name in $ProcessNames) {
    $got = Get-Process -Name $name -ErrorAction SilentlyContinue
    if ($got) { $all += $got }
  }
  if (-not $all) { return @() }

  # map pid -> perf instance (for context switches / page faults)
  $pidToInst = Get-ProcInstanceMap

  $rows = @()
  foreach ($p in $all) {
    $procId  = $p.Id
    $pName   = $p.ProcessName

    # --- CPU user & kernel percent via deltas ---
    $userSec = 0.0; $kernSec = 0.0
    try { $userSec = [double]$p.UserProcessorTime.TotalSeconds } catch {}
    try { $kernSec = [double]$p.PrivilegedProcessorTime.TotalSeconds } catch {}

    $cpuUserPct = 0.0; $cpuKernPct = 0.0; $cpuPct = 0.0
    if (-not $prevUserSec.ContainsKey($procId)) {
      $prevUserSec[$procId] = $userSec
      $prevKernSec[$procId] = $kernSec
      $prevTs[$procId]      = $tsNow
    } else {
      $deltaUser = $userSec - $prevUserSec[$procId]
      $deltaKern = $kernSec - $prevKernSec[$procId]
      $deltaWall = ($tsNow - $prevTs[$procId]).TotalSeconds
      if ($deltaWall -gt 0) {
        $cpuUserPct = [math]::Max(0.0, ($deltaUser / $deltaWall) * 100.0 / $logical)
        $cpuKernPct = [math]::Max(0.0, ($deltaKern / $deltaWall) * 100.0 / $logical)
        $cpuPct     = $cpuUserPct + $cpuKernPct
      }
      $prevUserSec[$procId] = $userSec
      $prevKernSec[$procId] = $kernSec
      $prevTs[$procId]      = $tsNow
    }

    # --- Context switches/sec via Process counters (by instance) ---
    $ctxTotal = ""
    if ($pidToInst.ContainsKey($procId)) {
      $inst = $pidToInst[$procId]
      try {
        $ctxTotal = [double](Get-Counter "\Process($inst)\Context Switches/sec").CounterSamples[0].CookedValue
      } catch { $ctxTotal = "" }
    }
    $ctxPerCore = ""
    if ($ctxTotal -ne "") { $ctxPerCore = if ($logical -gt 0) { $ctxTotal / $logical } else { $ctxTotal } }

    # --- Memory & others ---
    $wsMB   = [math]::Round(($p.WorkingSet64/1MB),0)
    $privMB = [math]::Round(($p.PrivateMemorySize64/1MB),0)
    $thr    = [int]$p.Threads.Count
    $hdl    = [int]$p.Handles

    # Page Faults/sec (best-effort)
    $pf = ""
    if ($pidToInst.ContainsKey($procId)) {
      $inst = $pidToInst[$procId]
      try { $pf = [double](Get-Counter "\Process($inst)\Page Faults/sec").CounterSamples[0].CookedValue } catch { $pf = "" }
    }

    $rows += ("{0},{1},{2},{3:F2},{4:F2},{5:F2},{6},{7},{8:F0},{9:F0},{10},{11},{12}" -f `
      $tsStr,$pName,$procId,$cpuPct,$cpuUserPct,$cpuKernPct,
      ($ctxTotal -as [string]), ($ctxPerCore -as [string]),
      $wsMB,$privMB,$thr,$hdl, ($pf -as [string]) )
  }
  return $rows
}

Write-Host ("Start: names=[{0}], interval={1}s, duration={2}s, out={3}" -f ($ProcessNames -join ","),$IntervalSec,$DurationSec,$OutDir)

$iterations = 0
while ($true) {
  try {
    Write-Line (Get-SystemRow) $sysCsv
    $rows = Get-ProcessRows
    foreach ($r in $rows) { Write-Line $r $procCsv }
  } catch { Write-Warning $_.Exception.Message }

  $iterations++
  if ($DurationSec -gt 0 -and ($iterations * $IntervalSec) -ge $DurationSec) { break }
  Start-Sleep -Seconds $IntervalSec
}
Write-Host "Done.`n  $sysCsv`n  $procCsv"
