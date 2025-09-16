#!/usr/bin/env bash
# capture_linux.sh
set -euo pipefail

IFACE="eth0"
CLIENT_FIX_PORT=9898     # Only if Linux also sees client, else omit
WL_PROP_PORT=20000
EXCH_FIX_PORT=9900
OUTDIR="/var/captures"
SNAPLEN=0                # 0 = full packets
KBUF_MB=4096             # enlarge buffer; needs privilege
FILE_MB=200              # rotate every 200MB
MAXFILES=50

sudo mkdir -p "$OUTDIR"

# Prefer capturing on the real device (not "any") for proper TCP reassembly
sudo tcpdump -i "$IFACE" -s $SNAPLEN -B $KBUF_MB -n \
  '(tcp port '"$WL_PROP_PORT"' or tcp port '"$EXCH_FIX_PORT"''\
${CLIENT_FIX_PORT:+ ' or tcp port '"$CLIENT_FIX_PORT"''}) \
' -w "$OUTDIR/linux_%Y%m%d_%H%M%S.pcap" \
  -C $FILE_MB -W $MAXFILES
