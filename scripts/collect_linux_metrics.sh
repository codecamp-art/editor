#!/usr/bin/env bash
# Usage:
#   ./collect_linux_metrics_compat.sh OUT_DIR INTERVAL_SEC DURATION_SEC "name1,name2" USERNAME [echo]
# Example:
#   ./collect_linux_metrics_compat.sh /var/tmp/perf 1 0 "fix-adapter,quickfixd" myuser true
set -euo pipefail

OUT_DIR=${1:-/var/tmp/perf}
INTERVAL=${2:-1}
DURATION=${3:-0}
NAMES_CSV=${4:-fix-adapter}
USER_NAME=${5:-"$USER"}
ECHO=${6:-false}

mkdir -p "$OUT_DIR"
SYS_CSV="$OUT_DIR/linux_system.csv"
PROC_CSV="$OUT_DIR/linux_process.csv"

append_echo() { local line="$1" file="$2"; if [ "$ECHO" = true ]; then printf '%s\n' "$line" | tee -a "$file" >/dev/null; else printf '%s\n' "$line" >> "$file"; fi; }
need_cmd()   { command -v "$1" >/dev/null 2>&1 || echo "WARN: $1 not found; some metrics may be blank."; }

append_echo "timestamp,cpu_total_percent,cpu_user_percent,cpu_system_percent,cpu_queue_length,context_switches_per_sec_total,context_switches_per_sec_per_core,mem_available_mb,mem_committed_mb,pages_per_sec,network_bytes_total_per_sec" "$SYS_CSV"
append_echo "timestamp,process_name,process_id,cpu_percent,cpu_user_percent,cpu_system_percent,ctx_switches_per_sec_total,ctx_switches_per_sec_per_core,working_set_mb,private_bytes_mb,thread_count,handle_count,page_faults_per_sec" "$PROC_CSV"

IFS=',' read -r -a PROC_NAMES <<< "$NAMES_CSV"
cores=$(nproc || echo 1)

need_cmd mpstat
need_cmd vmstat
need_cmd pidstat
need_cmd sar

net_sample() { awk 'NR>2 {rx+=$2; tx+=$10} END{print rx+0, tx+0}' /proc/net/dev; }
net_diff_1s(){ read rx1 tx1 <<<"$(net_sample)"; sleep 1; read rx2 tx2 <<<"$(net_sample)"; echo $((rx2-rx1)) $((tx2-tx1)); }

get_pids() {
  local pids=()
  for name in "${PROC_NAMES[@]}"; do
    while IFS= read -r one; do [ -n "$one" ] && pids+=("$one"); done < <(pgrep -u "$USER_NAME" -f "$name" 2>/dev/null || true)
  done
  printf "%s\n" "${pids[@]}" | sort -n | uniq | tr '\n' ' '
}

i=0
while :; do
  ts=$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)

  # ----- system (1s sample) -----
  if command -v mpstat >/dev/null 2>&1; then
    read usr sys idle <<<"$(mpstat 1 1 | awk '/all/ {print $3,$5,$12}' | tail -n1)"
  else usr=; sys=; idle=; sleep 1; fi

  if command -v vmstat >/dev/null 2>&1; then
    read runq ctx si so <<<"$(vmstat 1 1 | awk 'END{print $1" "$12" "$7" "$8}')"
  else runq=; ctx=; si=; so=; fi

  mem_avail_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo 2>/dev/null || echo "")
  committed_as_kb=$(awk '/Committed_AS:/ {print $2}' /proc/meminfo 2>/dev/null || echo "")

  if command -v sar >/dev/null 2>&1; then
    read rx tx <<<"$(sar -n DEV 1 1 2>/dev/null | awk '/Average:/ && $3!="lo"{rx+=$5;tx+=$6} END{print rx+0, tx+0}')"
  else
    read rx tx <<<"$(net_diff_1s)"
  fi

  cpu_total=""
  [ -n "${idle:-}" ] && cpu_total=$(awk -v i="$idle" 'BEGIN{printf "%.2f", 100.0 - i}')
  ctx_per_core=""
  [ -n "${ctx:-}" ] && ctx_per_core=$(awk -v cs="$ctx" -v c="$cores" 'BEGIN{ if(c>0) printf "%.2f", cs/c; else print "" }')
  mem_avail_mb=""
  [ -n "${mem_avail_kb:-}" ] && mem_avail_mb=$(awk -v x="$mem_avail_kb" 'BEGIN{printf "%.0f", x/1024}')
  mem_commit_mb=""
  [ -n "${committed_as_kb:-}" ] && mem_commit_mb=$(awk -v x="$committed_as_kb" 'BEGIN{printf "%.0f", x/1024}')
  pages_sum=$(awk -v a="${si:-0}" -v b="${so:-0}" 'BEGIN{printf "%.2f", a+b}')
  net_total=$(awk -v a="${rx:-0}" -v b="${tx:-0}" 'BEGIN{printf "%.0f", a+b}')

  append_echo "$ts,$cpu_total,$usr,$sys,$runq,$ctx,$ctx_per_core,$mem_avail_mb,$mem_commit_mb,$pages_sum,$net_total" "$SYS_CSV"

  # ----- process (1s sample) -----
  PIDS=($(get_pids))
  if [[ ${#PIDS[@]} -gt 0 && $(command -v pidstat) ]]; then
    # CPU user/system & threads & RSS/VSZ
    TMP_CPU=$(mktemp)
    pidstat -rud -h -p "$(IFS=,; echo "${PIDS[*]}")" 1 1 2>/dev/null > "$TMP_CPU" || true
    # Context switches
    TMP_CTX=$(mktemp)
    pidstat -w -h -p "$(IFS=,; echo "${PIDS[*]}")" 1 1 2>/dev/null > "$TMP_CTX" || true

    # join by PID
    awk -v TS="$ts" -v CORES="$cores" '
      FNR==NR && $1 ~ /^[0-9]+$/ {
        pid=$1; usr=$7; sys=$8; cpu=$9; vsz=$12; rss=$11; thr=$6; cmd=$NF
        cpu_usr[pid]=usr; cpu_sys[pid]=sys; cpu_tot[pid]=cpu; rsskb[pid]=rss; vszkb[pid]=vsz; th[pid]=thr; name[pid]=cmd
        next
      }
      $1 ~ /^[0-9]+$/ {
        pid=$1; cs=$4; ncs=$5; ctx=cs+ncs; ctxpc=(CORES>0?ctx/CORES:"")
        # Windows-compatible columns:
        # timestamp,process_name,process_id,cpu_percent,cpu_user_percent,cpu_system_percent,ctx_switches_per_sec_total,ctx_switches_per_sec_per_core,working_set_mb,private_bytes_mb,thread_count,handle_count,page_faults_per_sec
        ws_mb  = (rsskb[pid]==""? "": sprintf("%.0f", rsskb[pid]/1024.0))
        # private_bytes_mb left blank on Linux
        printf "%s,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%s,,%s,,\n",
          TS, name[pid], pid, (cpu_tot[pid]==""?0:cpu_tot[pid]),
          (cpu_usr[pid]==""?0:cpu_usr[pid]), (cpu_sys[pid]==""?0:cpu_sys[pid]),
          ctx, ctxpc, ws_mb, th[pid]
      }' "$TMP_CPU" "$TMP_CTX" | while IFS= read -r line; do append_echo "$line" "$PROC_CSV"; done

    rm -f "$TMP_CPU" "$TMP_CTX"
  else
    append_echo "$ts,,,," "$PROC_CSV"
  fi

  i=$((i+1))
  if [[ $DURATION -gt 0 && $((i*INTERVAL)) -ge $DURATION ]]; then break; fi
  if [[ $INTERVAL -gt 1 ]]; then sleep $((INTERVAL - 1)); fi
done

echo "Done. Files:"
echo "  $SYS_CSV"
echo "  $PROC_CSV"
