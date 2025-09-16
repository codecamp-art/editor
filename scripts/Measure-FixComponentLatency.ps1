<#
.SYNOPSIS
  Measure component (stack-level) latency by matching FIX Tag11 to proprietary messages.

.DESCRIPTION
  - Reads pcapng files from main/HA for inbound FIX, and main/HA for outbound proprietary.
  - Extracts ClOrdID (Tag 11) from FIX via Wireshark dissector.
  - Extracts ClOrdID from proprietary payload at TCP payload offset 304 (length 20 bytes).
  - Matches by Tag11 and computes latency = proprietary_ts - fix_ts (seconds).
  - Outputs Markdown with min / max / avg / p95 (ms), plus counts.

.NOTES
  Assumes clocks between hosts are reasonably in sync if pcaps come from different servers.
  Measures host stack timing (offloads may be ON — that’s fine for component latency).

.PARAMETERS
  -FixMainPcap   : Path to main server FIX pcapng (inbound FIX to server port).
  -FixHaPcap     : Path to HA server FIX pcapng (optional).
  -PropMainPcap  : Path to main server proprietary pcapng (outgoing payload).
  -PropHaPcap    : Path to HA server proprietary pcapng (optional).
  -FixPort       : Server FIX listening port (to target inbound).
  -PropPort      : Proprietary TCP port used between Windows<->Linux.
  -OutMarkdown   : Output Markdown file path. Default: .\latency_report.md
  -WiresharkBin  : Folder containing tshark.exe. Default auto-resolves.

.EXAMPLE
  .\Measure-FixComponentLatency.ps1 `
    -FixMainPcap  "C:\caps\fix_main.pcapng" `
    -FixHaPcap    "C:\caps\fix_ha.pcapng" `
    -PropMainPcap "C:\caps\prop_main.pcapng" `
    -PropHaPcap   "C:\caps\prop_ha.pcapng" `
    -FixPort 9898 -PropPort 20000 `
    -OutMarkdown "C:\caps\latency_report.md"
#>

[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)]  [string]$FixMainPcap,
  [Parameter(Mandatory=$false)] [string]$FixHaPcap,
  [Parameter(Mandatory=$true)]  [string]$PropMainPcap,
  [Parameter(Mandatory=$false)] [string]$PropHaPcap,
  [Parameter(Mandatory=$true)]  [int]$FixPort,
  [Parameter(Mandatory=$true)]  [int]$PropPort,
  [Parameter(Mandatory=$false)] [string]$OutMarkdown = ".\latency_report.md",
  [Parameter(Mandatory=$false)] [string]$WiresharkBin
)

# ---------- Helpers ----------
function Resolve-Tshark {
  param([string]$BinHint)
  if ($BinHint) {
    $p = Join-Path $BinHint "tshark.exe"
    if (Test-Path $p) { return $p }
  }
  $cmd = Get-Command tshark.exe -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $candidates = @(
    Join-Path $env:ProgramFiles      "Wireshark\tshark.exe"),
    Join-Path ${env:ProgramFiles(x86)} "Wireshark\tshark.exe"
  )
  foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
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
    # Tag11/ClOrdID is ASCII in your spec; if UTF-8 needed, change Encoding
    $s = [Text.Encoding]::ASCII.GetString($bytes)
    return $s.Trim([char]0, " ", "`t", "`r", "`n")
  } catch {
    return $null
  }
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

# ---------- Extractors ----------
function Export-FixEvents([string]$tshark, [string]$pcap, [int]$fixPort) {
  # We prefer inbound to FIX server: dport == fixPort; if unsure, drop the dport term
  $tmp = [System.IO.Path]::GetTempFileName()
  $args = @(
    '-r', $pcap,
    '-o', 'tcp.desegment_tcp_streams:TRUE',
    '-Y', "tcp.port==$fixPort && fix && (tcp.dstport==$fixPort) && fix.clordid",
    '-T', 'fields', '-E', 'header=y', '-E', 'separator=,',
    '-e', 'frame.time_epoch',
    '-e', 'tcp.stream',
    '-e', 'ip.src','-e','tcp.srcport','-e','ip.dst','-e','tcp.dstport',
    '-e', 'fix.clordid',          # primary field
    '-e', 'fix.cl_ord_id'         # fallback on some builds
  )
  & $tshark @args | Out-File -Encoding ascii -FilePath $tmp
  # Parse CSV rows into @{ id, ts }
  $rows = @()
  try { $rows = Import-Csv $tmp } catch { }
  Remove-Item $tmp -ErrorAction SilentlyContinue
  $out = New-Object System.Collections.Generic.List[object]
  foreach ($r in $rows) {
    $ts = [double]$r.'frame.time_epoch'
    $id = $r.'fix.clordid'
    if ([string]::IsNullOrWhiteSpace($id)) { $id = $r.'fix.cl_ord_id' }
    if (-not [string]::IsNullOrWhiteSpace($id)) {
      # normalize
      $id = $id.Trim()
      $out.Add([pscustomobject]@{ Id = $id; Ts = $ts; Source = (Split-Path $pcap -Leaf) })
    }
  }
  return $out
}

function Export-PropEvents([string]$tshark, [string]$pcap, [int]$propPort) {
  # We read any payload on propPort (either direction), slice offset 304 length 20.
  $tmp = [System.IO.Path]::GetTempFileName()
  $args = @(
    '-r', $pcap,
    '-o', 'tcp.desegment_tcp_streams:TRUE',
    '-Y', "tcp.port==$propPort && tcp.len>0",
    '-T', 'fields', '-E', 'header=y', '-E', 'separator=,',
    '-e', 'frame.time_epoch',
    '-e', 'tcp.stream',
    '-e', 'ip.src','-e','tcp.srcport','-e','ip.dst','-e','tcp.dstport',
    # try reassembled first; fallback to raw data
    '-e', 'tcp.reassembled.length',
    '-e', 'tcp.reassembled.data[304:20]',
    '-e', 'data.data[304:20]'
  )
  & $tshark @args | Out-File -Encoding ascii -FilePath $tmp
  $rows = @()
  try { $rows = Import-Csv $tmp } catch { }
  Remove-Item $tmp -ErrorAction SilentlyContinue

  $out = New-Object System.Collections.Generic.List[object]
  foreach ($r in $rows) {
    $ts = [double]$r.'frame.time_epoch'
    $hex = $r.'tcp.reassembled.data[304:20]'
    if ([string]::IsNullOrWhiteSpace($hex)) { $hex = $r.'data.data[304:20]' }
    if ([string]::IsNullOrWhiteSpace($hex)) { continue }
    $id = HexToAscii $hex
    if ([string]::IsNullOrWhiteSpace($id)) { continue }
    $out.Add([pscustomobject]@{ Id = $id; Ts = $ts; Source = (Split-Path $pcap -Leaf) })
  }
  return $out
}

# ---------- Main ----------
$Tshark = Resolve-Tshark -BinHint $WiresharkBin
Write-Host "Using tshark: $Tshark"

$fixEvents = New-Object System.Collections.Generic.List[object]
$propEvents = New-Object System.Collections.Generic.List[object]

if (Ensure-File $FixMainPcap "FIX main pcap")   { $fixEvents.AddRange( (Export-FixEvents  $Tshark $FixMainPcap $FixPort) ) }
if ($FixHaPcap -and (Ensure-File $FixHaPcap "FIX HA pcap")) { $fixEvents.AddRange( (Export-FixEvents  $Tshark $FixHaPcap   $FixPort) ) }

if (Ensure-File $PropMainPcap "Prop main pcap") { $propEvents.AddRange( (Export-PropEvents $Tshark $PropMainPcap $PropPort) ) }
if ($PropHaPcap -and (Ensure-File $PropHaPcap "Prop HA pcap")) { $propEvents.AddRange( (Export-PropEvents $Tshark $PropHaPcap   $PropPort) ) }

if ($fixEvents.Count -eq 0)  { throw "No FIX events found." }
if ($propEvents.Count -eq 0) { throw "No proprietary events found." }

# Group by Id
$fixById  = $fixEvents  | Group-Object Id
$propById = $propEvents | Group-Object Id

# Build outbound time arrays (sorted) per Id for quick lookup
$propTimesById = @{}
foreach ($g in $propById) {
  $times = ($g.Group | Select-Object -ExpandProperty Ts | Sort-Object) | ForEach-Object {[double]$_}
  # cast to [double[]] for BinarySearch
  $propTimesById[$g.Name] = [double[]]$times
}

# Pairing: earliest proprietary ts >= inbound fix ts for same Id
$latencies = New-Object System.Collections.Generic.List[double]
$paired    = New-Object System.Collections.Generic.List[pscustomobject]
$unmatchedFix  = 0
$unmatchedProp = ($propById.Count)

foreach ($g in $fixById) {
  $id = $g.Name
  if (-not $propTimesById.ContainsKey($id)) { $unmatchedFix++ ; continue }
  $outs = $propTimesById[$id]
  if ($outs.Length -eq 0) { $unmatchedFix++ ; continue }

  # For each inbound FIX ts for this Id, pick first outbound >= ts (only one pair per Id by default)
  $inTs = ($g.Group | Select-Object -ExpandProperty Ts | Sort-Object) | Select-Object -First 1
  # Binary search to find insertion index
  $idx = [Array]::BinarySearch($outs, [double]$inTs)
  if ($idx -lt 0) { $idx = -$idx - 1 } # nearest >=
  if ($idx -ge $outs.Length) {
    # No outbound after inbound; skip (clocks may be skewed). You could choose earliest outbound instead:
    # $idx = 0
    $unmatchedFix++
    continue
  }
  $outTs = $outs[$idx]
  $lat = [double]($outTs - $inTs)
  if ($lat -ge 0) {
    $latencies.Add($lat)
    $paired.Add([pscustomobject]@{ Id=$id; InTs=$inTs; OutTs=$outTs; Latency_s=$lat })
  } else {
    # Negative -> likely clock skew; skip
    $unmatchedFix++
  }
}

# ---------- Stats ----------
if ($latencies.Count -eq 0) { throw "No matched pairs (check ports / clocks / Tag11 extraction)." }

$latArr = [double[]]$latencies
$min  = ($latArr | Measure-Object -Minimum).Minimum
$max  = ($latArr | Measure-Object -Maximum).Maximum
$avg  = ($latArr | Measure-Object -Average).Average
$p95  = Percentile $latArr 0.95

# Convert to ms with 3 decimals
function ms3([double]$s) { return [Math]::Round($s * 1000.0, 3) }

$minMs = ms3 $min
$maxMs = ms3 $max
$avgMs = ms3 $avg
$p95Ms = ms3 $p95

# ---------- Markdown output ----------
$md = @()
$md += "# Component Latency Report"
$md += ""
$md += "**Inputs**"
$md += ""
$md += "- FIX main:  $FixMainPcap"
if ($FixHaPcap)   { $md += "- FIX HA:    $FixHaPcap" }
$md += "- PROP main: $PropMainPcap"
if ($PropHaPcap)  { $md += "- PROP HA:   $PropHaPcap" }
$md += "- FIX port:  $FixPort"
$md += "- PROP port: $PropPort"
$md += ""
$md += "**Counts**"
$md += ""
$md += "- FIX events: $($fixEvents.Count)"
$md += "- PROP events: $($propEvents.Count)"
$md += "- Matched Tag11 pairs: $($latArr.Count)"
$md += "- Unmatched FIX Tag11: $unmatchedFix"
$md += ""
$md += "| Metric | Value (ms) |"
$md += "|---|---:|"
$md += "| Min | $minMs |"
$md += "| Max | $maxMs |"
$md += "| Avg | $avgMs |"
$md += "| P95 | $p95Ms |"
$md += ""
$md += "_Note_: Stack-level timing (host capture). If pcaps are from different hosts, keep clocks in sync."

$uft8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($OutMarkdown, $md, $uft8NoBom)
# Set-Content -LiteralPath $OutMarkdown -Value ($md -join "`r`n") -Encoding UTF8

# Also drop a CSV of pairs next to the MD for auditing
$csvPath = [System.IO.Path]::ChangeExtension($OutMarkdown, ".pairs.csv")
$paired | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8

Write-Host "✅ Wrote $OutMarkdown"
Write-Host "✅ Wrote $csvPath"
