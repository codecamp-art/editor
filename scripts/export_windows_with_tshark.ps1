param(
  [string]$WiresharkBin = "C:\Program Files\Wireshark",
  [string]$InDir = "C:\captures",
  [int]$CLIENT_FIX_PORT = 9898,
  [int]$WL_PROP_PORT   = 20000
)

$mergecap = Join-Path $WiresharkBin "mergecap.exe"
$tshark   = Join-Path $WiresharkBin "tshark.exe"
$capinfos = Join-Path $WiresharkBin "capinfos.exe"

if (!(Test-Path $mergecap)) { throw "mergecap not found at $mergecap" }
if (!(Test-Path $tshark))   { throw "tshark not found at $tshark" }
if (!(Test-Path $capinfos)) { throw "capinfos not found at $capinfos" }

# Merge the rotated files (keeps chronological order automatically)
$merged = Join-Path $InDir "win_merged.pcapng"
Remove-Item $merged -ErrorAction SilentlyContinue
& $mergecap -w $merged (Get-ChildItem $InDir -Filter "win_*.pcapng" | Sort-Object Name | ForEach-Object { $_.FullName })

# (Optional) quick stats
& $capinfos -S $merged

# Export FIX messages to CSV (client <-> Windows)
$fixCsv  = Join-Path $InDir "windows_fix.csv"
& $tshark -r $merged `
  -o "tcp.desegment_tcp_streams:TRUE" `
  -Y "fix && tcp.port==$CLIENT_FIX_PORT" `
  -T fields -E header=y -E separator=, `
  -e frame.time_epoch -e tcp.stream -e ip.src -e tcp.srcport -e ip.dst -e tcp.dstport `
  -e fix.msgtype -e fix.clordid -e fix.origclordid -e fix.sendingtime -e fix.exectype -e fix.ordstatus `
  > $fixCsv

# Export proprietary TCP (Windows <-> Linux)
$propCsv = Join-Path $InDir "windows_prop.csv"
& $tshark -r $merged `
  -o "tcp.desegment_tcp_streams:TRUE" `
  -Y "tcp.port==$WL_PROP_PORT && tcp.len>0" `
  -T fields -E header=y -E separator=, `
  -e frame.time_epoch -e tcp.stream -e ip.src -e tcp.srcport -e ip.dst -e tcp.dstport -e tcp.len `
  > $propCsv

"`nExported:`n  $fixCsv`n  $propCsv"
