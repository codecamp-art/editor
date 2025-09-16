param(
  [string]$WiresharkBin = "C:\Program Files\Wireshark",  # folder with dumpcap.exe, tshark.exe, etc.
  [string]$Interface = "Ethernet",     # or numeric index from dumpcap -D
  [int]$CLIENT_FIX_PORT = 9898,        # client <-> Windows FIX listener
  [int]$WL_PROP_PORT   = 20000,        # Windows <-> Linux proprietary TCP
  [string]$OutDir = "C:\captures",
  [int]$BufMB = 1024,                  # capture ring buffer MB
  [int]$FileSeconds = 300,             # rotate every 5 minutes
  [int]$MaxFiles = 48                  # keep last 4 hours
)

$dumpcap = Join-Path $WiresharkBin "dumpcap.exe"
$tshark  = Join-Path $WiresharkBin "tshark.exe"

if (!(Test-Path $dumpcap)) { throw "dumpcap not found at $dumpcap" }
if (!(Test-Path $tshark))  { throw "tshark not found at $tshark" }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# Show interfaces so you can confirm the right one (index or name both work on most hosts)
& $dumpcap -D

# Capture filter to limit load and drops
$Filter = "tcp port $CLIENT_FIX_PORT or tcp port $WL_PROP_PORT"

# Start capture in the foreground (Ctrl+C to stop) or use Start-Process for background
& $dumpcap `
  -i $Interface `
  -f $Filter `
  -s 0 `
  -B $BufMB `
  -b duration:$FileSeconds `
  -b files:$MaxFiles `
  -w (Join-Path $OutDir "win_%Y%m%d_%H%M%S.pcapng")
