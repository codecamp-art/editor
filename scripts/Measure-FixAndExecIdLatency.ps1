<#
.SYNOPSIS
  Measure latency from inbound FIX (Tag11/ClOrdID) to proprietary ExecID payload.

.DESCRIPTION
  - Reads up to 2 FIX pcaps (main + HA).
  - Reads up to 2 proprietary pcaps (main + HA).
  - Extracts Tag11 from FIX via Wireshark dissector.
  - Extracts ExecID from proprietary TCP payload at offset 314, len 10
    (prefers tcp.reassembled.data, falls back to data.data).
  - Uses a Tag11↔ExecId mapping CSV to pair events.
  - Latency = first ExecID timestamp >= Tag11 timestamp (seconds).
  - Writes Markdown summary and CSV of matched pairs.

.PARAMETERS
  -FixMainPcap, -FixHaPcap     : inbound FIX pcaps (server side; optional HA)
  -PropMainPcap, -PropHaPcap   : proprietary pcaps with ExecID (optional HA)
  -FixPort                     : FIX server port (to target inbound)
  -PropPort                    : proprietary TCP port carrying ExecID
  -Tag11ExecIdCsv              : CSV with Tag11<->ExecId mapping (headers auto-detected)
  -OutMarkdown                 : output .md (default .\latency_fix_to_exec.md)
  -WiresharkBin                : folder containing tshark.exe (auto-resolves)
  -ExecOffset                  : byte offset of ExecID in TCP payload (default 314)
  -ExecLength                  : byte length of ExecID (default 10)
  -MaxSearchWindowSeconds      : optional upper bound for pairing window (default 0 = unlimited)

.NOTES
  - Stack-level timing (host capture). Keep capture settings & clocks consistent across runs.
  - PowerShell 5.1 compatible.

.EXAMPLE
  .\Measure-FixToExecLatency.ps1 `
    -FixMainPcap  "C:\caps\fix_main.pcapng" `
    -FixHaPcap    "C:\caps\fix_ha.pcapng" `
    -PropMainPcap "C:\caps\prop_main_exec.pcapng" `
    -PropHaPcap   "C:\caps\prop_ha_exec.pcapng" `
    -FixPort 9898 -PropPort 20000 `
    -Tag11ExecIdCsv "C:\caps\tag11_execid_map.csv" `
    -OutMarkdown "C:\caps\latency_fix_to_exec.md"
#>

[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)]  [string]$FixMainPcap,
  [Parameter(Mandatory=$false)] [string]$FixHaPcap,

  [Parameter(Mandatory=$true)]  [string]$PropMainPcap,
  [Parameter(Mandatory=$false)] [string]$PropHaPcap,

  [Parameter(Mandatory=$true)]  [int]$FixPort,
  [Parameter(Mandatory=$true)]  [int]$PropPort,

  [Parameter(Mandatory=$true)]  [string]$Tag11ExecIdCsv,

  [Parameter(Mandatory=$false)] [string]$OutMarkdown = ".\latency_fix_to_exec.md",
  [Parameter(Mandatory=$false)] [string]$WiresharkBin,

  [Parameter(Mandatory=$false)] [int]$ExecOffset = 314,
  [Parameter(Mandatory=$false)] [int]$ExecLength = 10,

  [Parameter(Mandatory=$false)] [double]$MaxSearchWindowSeconds = 0
)

# ---------------- Helpers ----------------
function Resolve-Tshark {
  param([string]$BinHint)
  if ($BinHint) {
    $p = Join-Path $BinHint "tshark.exe"
    if (Test-Path $p) { return $p }
  }
  $cmd = Get-Command tshark.exe -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $candidates = @(
    (Join-Path $env:ProgramFiles 'Wireshark\tshark.exe'),
    (Join-Path ${env:ProgramFiles(x86)} 'Wireshark\tshark.exe')
  )
  foreach ($p in $candidates) { if (Test-Path $p) { return $p } }
  throw "Cannot find tshark.exe. Install Wireshark or set -WiresharkBin."
}

function Ensure-File([string]$path, [string]$label) {
  if (-not $path) { return $false }
  if (-not (Test-Path $path)) {
    Write-Warning "$label '$path' not found; skipping."
    return $false
  }
  return $true
}

function HexToAscii([string]$hex) {
  if ([string]::IsNullOrWhiteSpace($hex)) { return $null }
  $clean = $hex.Replace(":","").Replace(" ","")
  if ($clean.Length -eq 0) { return $null }
  if ($clean.Length % 2 -ne 0) { $clean = "0$clean" }
  try {
    $bytes = New-Object byte[] ($clean.Length/2)
    for ($i=0; $i -lt $bytes.Length; $i++) {
      $bytes[$i] = [Convert]::ToByte($clean.Substring($i*2,2),16)
    }
    $s = [Text.Encoding]::ASCII.GetString($bytes)  # swap to UTF8 if needed
    return $s.Trim([char]0, " ", "`t", "`r", "`n")
  } catch { return $null }
}

function Percentile([double[]]$arr, [double]$p) {
  if (-not $arr -or $arr.Count -eq 0) { return $null }
  $sorted = $arr | Sort-Object
  $n = $sorted.Count
  $rank = [Math]::Ceiling($p * $n)
  if ($rank -lt 1) { $rank = 1 }
  if ($rank -gt $n) { $rank = $n }
  return [double]$sorted[$rank-1]
}

function ms3([double]$s) { [Math]::Round($s * 1000.0, 3) }

# ---------------- Tshark exporters ----------------
function Export-FixTag11([string]$tshark, [string]$pcap, [int]$fixPort) {
  $tmp = [System.IO.Path]::GetTempFileName()
  $args = @(
    '-r', $pcap,
    '-o', 'tcp.desegment_tcp_streams:TRUE',
    '-Y', "tcp.port==$fixPort AND fix AND (tcp.dstport==$fixPort) AND (fix.clordid OR fix.cl_ord_id)",
    '-T', 'fields', '-E', 'header=y', '-E', 'separator=,',
    '-e', 'frame.time_epoch',
    '-e', 'tcp.stream',
    '-e', 'ip.src','-e','tcp.srcport','-e','ip.dst','-e','tcp.dstport',
    '-e', 'fix.clordid',
    '-e', 'fix.cl_ord_id'
  )
  & $tshark @args | Out-File -Encoding ascii -FilePath $tmp
  $rows = @(); try { $rows = Import-Csv $tmp } catch {}
  Remove-Item $tmp -ErrorAction SilentlyContinue

  $out = New-Object System.Collections.Generic.List[object]
  foreach ($r in $rows) {
    $ts = [double]$r.'frame.time_epoch'
    $id = $r.'fix.clordid'; if ([string]::IsNullOrWhiteSpace($id)) { $id = $r.'fix.cl_ord_id' }
    if (-not [string]::IsNullOrWhiteSpace($id)) {
      $out.Add([pscustomobject]@{ Tag11=$id.Trim(); Ts=$ts; Source=(Split-Path $pcap -Leaf) })
    }
  }
  return $out
}

function Export-ExecId([string]$tshark, [string]$pcap, [int]$propPort, [int]$offset, [int]$length) {
  $slice1 = "tcp.reassembled.data[$offset`:$length]"
  $slice2 = "data.data[$offset`:$length]"
  $tmp = [System.IO.Path]::GetTempFileName()
  $args = @(
    '-r', $pcap,
    '-o', 'tcp.desegment_tcp_streams:TRUE',
    '-Y', "tcp.port==$propPort AND tcp.len>0",
    '-T', 'fields', '-E', 'header=y', '-E', 'separator=,',
    '-e', 'frame.time_epoch',
    '-e', 'tcp.stream',
    '-e', 'ip.src','-e','tcp.srcport','-e','ip.dst','-e','tcp.dstport',
    '-e', $slice1,
    '-e', $slice2
  )
  & $tshark @args | Out-File -Encoding ascii -FilePath $tmp
  $rows = @(); try { $rows = Import-Csv $tmp } catch {}
  Remove-Item $tmp -ErrorAction SilentlyContinue

  $out = New-Object System.Collections.Generic.List[object]
  foreach ($r in $rows) {
    $ts  = [double]$r.'frame.time_epoch'
    $hex = $r.$slice1; if ([string]::IsNullOrWhiteSpace($hex)) { $hex = $r.$slice2 }
    if ([string]::IsNullOrWhiteSpace($hex)) { continue }
    $exec = HexToAscii $hex
    if ([string]::IsNullOrWhiteSpace($exec)) { continue }
    $out.Add([pscustomobject]@{ ExecId=$exec; Ts=$ts; Source=(Split-Path $pcap -Leaf) })
  }
  return $out
}

# ---------------- Load inputs ----------------
$Tshark = Resolve-Tshark -BinHint $WiresharkBin
Write-Host "Using tshark: $Tshark"

$fix = New-Object System.Collections.Generic.List[object]
if (Ensure-File $FixMainPcap "FIX main pcap") { $fix.AddRange( (Export-FixTag11 $Tshark $FixMainPcap $FixPort) ) }
if ($FixHaPcap -and (Ensure-File $FixHaPcap "FIX HA pcap")) { $fix.AddRange( (Export-FixTag11 $Tshark $FixHaPcap $FixPort) ) }
if ($fix.Count -eq 0) { throw "No FIX Tag11 events found." }

$exec = New-Object System.Collections.Generic.List[object]
if (Ensure-File $PropMainPcap "Prop main (ExecId) pcap") { $exec.AddRange( (Export-ExecId $Tshark $PropMainPcap $PropPort $ExecOffset $ExecLength) ) }
if ($PropHaPcap -and (Ensure-File $PropHaPcap "Prop HA (ExecId) pcap")) { $exec.AddRange( (Export-ExecId $Tshark $PropHaPcap $PropPort $ExecOffset $ExecLength) ) }
if ($exec.Count -eq 0) { throw "No ExecId payload events found at offset $ExecOffset, len $ExecLength." }

# ---------------- Load Tag11<->ExecId map ----------------
if (-not (Test-Path $Tag11ExecIdCsv)) { throw "Mapping CSV not found: $Tag11ExecIdCsv" }
$mapRows = Import-Csv -Path $Tag11ExecIdCsv

# Detect columns (case-insensitive)
$headers = @()
if ($mapRows -and $mapRows.Count -gt 0) { $headers = $mapRows[0].psobject.Properties.Name }
function Find-Col($names, $pattern) { foreach ($n in $names) { if ($n -match $pattern) { return $n } } $null }
$colTag11 = Find-Col $headers '(?i)^(clordid|tag\s*11|tag11)$'
$colExec  = Find-Col $headers '(?i)^(execid|exec\s*id)$'
if (-not $colTag11 -or -not $colExec) {
  throw "Mapping CSV must contain Tag11 and ExecId columns (e.g., ClOrdID, ExecId). Detected: [$($headers -join ', ')]"
}

# Build Tag11 -> ExecId map (first seen wins)
$tagToExec = @{}
foreach ($row in $mapRows) {
  $t = [string]$row.$colTag11
  $e = [string]$row.$colExec
  if ([string]::IsNullOrWhiteSpace($t) -or [string]::IsNullOrWhiteSpace($e)) { continue }
  $t = $t.Trim(); $e = $e.Trim()
  if (-not $tagToExec.ContainsKey($t)) { $tagToExec[$t] = $e }
}

# ---------------- Index events ----------------
# FIX: earliest inbound ts per Tag11
$fixByTag = @{}
foreach ($g in ($fix | Group-Object Tag11)) {
  $tmin = ($g.Group | Select-Object -ExpandProperty Ts | Sort-Object | Select-Object -First 1)
  $fixByTag[$g.Name] = [double]$tmin
}

# Exec: times per ExecId (sorted arrays)
$execTimes = @{}
foreach ($g in ($exec | Group-Object ExecId)) {
  $times = ($g.Group | Select-Object -ExpandProperty Ts | Sort-Object) | ForEach-Object { [double]$_ }
  $execTimes[$g.Name] = [double[]]$times
}

# ---------------- Pairing & latency ----------------
$lat = New-Object System.Collections.Generic.List[double]
$pairs = New-Object System.Collections.Generic.List[pscustomobject]
$missingMap = 0
$unmatched  = 0
$window     = [double]$MaxSearchWindowSeconds

foreach ($kv in $fixByTag.GetEnumerator()) {
  $tag11 = $kv.Key
  $tFix  = [double]$kv.Value

  if (-not $tagToExec.ContainsKey($tag11)) { $missingMap++; continue }
  $execId = $tagToExec[$tag11]
  if (-not $execTimes.ContainsKey($execId)) { $unmatched++; continue }

  $outs = $execTimes[$execId]
  if ($outs.Length -eq 0) { $unmatched++; continue }

  # first ExecId ts >= tFix
  $idx = [Array]::BinarySearch($outs, $tFix)
  if ($idx -lt 0) { $idx = -$idx - 1 }
  if ($idx -ge $outs.Length) { $unmatched++; continue }

  $tOut = $outs[$idx]
  $d = [double]($tOut - $tFix)
  if ($d -lt 0) { $unmatched++; continue }
  if ($window -gt 0 -and $d -gt $window) { $unmatched++; continue }  # out of window

  $lat.Add($d)
  $pairs.Add([pscustomobject]@{ Tag11=$tag11; ExecId=$execId; InTs=$tFix; OutTs=$tOut; Latency_s=$d })
}

if ($lat.Count -eq 0) { throw "No matched Tag11→ExecId pairs. Check ports, offsets, mapping, or time alignment." }

# ---------------- Stats ----------------
$arr = [double[]]$lat
$min = ($arr | Measure-Object -Minimum).Minimum
$max = ($arr | Measure-Object -Maximum).Maximum
$avg = ($arr | Measure-Object -Average).Average
$p95 = Percentile $arr 0.95

# ---------------- Output ----------------
$md = @()
$md += "# Latency: FIX(Tag11) → Proprietary(ExecId @ $ExecOffset:$ExecLength)"
$md += ""
$md += "## Inputs"
$md += "- FIX main:  $FixMainPcap"
if ($FixHaPcap)   { $md += "- FIX HA:    $FixHaPcap" }
$md += "- PROP main: $PropMainPcap"
if ($PropHaPcap)  { $md += "- PROP HA:   $PropHaPcap" }
$md += "- FIX port:  $FixPort"
$md += "- PROP port: $PropPort"
$md += "- Mapping CSV: $Tag11ExecIdCsv"
if ($MaxSearchWindowSeconds -gt 0) { $md += "- Max pairing window: $MaxSearchWindowSeconds s" }
$md += ""
$md += "## Counts"
$md += "- FIX Tag11 events: $($fix.Count)"
$md += "- ExecId events: $($exec.Count)"
$md += "- Tag11 in map: $($tagToExec.Keys.Count)"
$md += "- Matched pairs: $($arr.Length)"
$md += "- Unmapped Tag11: $missingMap"
$md += "- Unmatched after mapping: $unmatched"
$md += ""
$md += "## Summary"
$md += "| Metric | Value (ms) |"
$md += "|---|---:|"
$md += "| Min | $(ms3 $min) |"
$md += "| Max | $(ms3 $max) |"
$md += "| Avg | $(ms3 $avg) |"
$md += "| P95 | $(ms3 $p95) |"
$md += ""
$md += "_Notes_: Times are **stack-level** (host capture). Prefer identical capture settings and good clock sync across hosts."

# Write Markdown (UTF-8 with BOM is OK for PS 5.1)
Set-Content -LiteralPath $OutMarkdown -Value ($md -join "`r`n") -Encoding UTF8

# Matched-pair CSV next to MD
$csvPath = [System.IO.Path]::ChangeExtension($OutMarkdown, ".pairs.csv")
$pairs | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8

Write-Host "✅ Wrote $OutMarkdown"
Write-Host "✅ Wrote $csvPath"
