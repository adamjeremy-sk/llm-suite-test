#!/usr/bin/env bash
# Config sweep: for each line of a configs file, start your llama.cpp server script with those flag
# changes, run tools/bench.py against it, stop the server, repeat. Then build one report.
#
# Usage (from anywhere):
#   SERVER_SCRIPT=~/.local/bin/qwen-server bash tools/sweep.sh configs/baseline.txt
#
# Environment:
#   SERVER_SCRIPT  your server launch script (required; QWEN_SERVER also accepted). It must be a bash
#                  script with one flag per line ending in " \", including --log-file, --host/--port.
#                  It is never modified: each config runs from a temporary copy in the run folder.
#   MODEL          results folder name; default: the script's --alias value
#   RUNS=3 LONG_RUNS=5 MAX_TOKENS=1024   repetitions per short / long prompt, generation cap
#   NO_LONG=1      skip the long-context prompts (much faster; generation-at-depth not measured)
#   ONLY="a b"     run only these config names from the file
#   MIN_TPS=20     a config slower than this on a 64-token check is skipped (spilled out of VRAM)
#   PORT=8080      must match the --port in the server script
#   LLAMA_CMD      how to call llama.cpp for --help/--version/--list-devices (default: "llama server"
#                  if a `llama` binary exists, else "llama-server")
#   HW_NOTES       free text added to the report header (e.g. "PL 300W, -25mV, core 2800, mem +1300")
#
# Output:
#   runs/<model>/<date>_<configs name>/      raw: CSVs, responses, server logs, temp scripts (git-ignored)
#   models/<model>/results/<date>_<configs name>.md   the report (tracked)
set -u

SUITE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BENCH="$SUITE/tools/bench.py"; TOOLS="$SUITE/tools/report.py"
CONFIGS="${1:-${CONFIGS:-$SUITE/configs/baseline.txt}}"
SERVER_SCRIPT="${SERVER_SCRIPT:-${QWEN_SERVER:-}}"
RUNS="${RUNS:-3}"; LONG_RUNS="${LONG_RUNS:-5}"; MAX_TOKENS="${MAX_TOKENS:-1024}"
PORT="${PORT:-8080}"; URL="http://127.0.0.1:$PORT"
if [ -z "${LLAMA_CMD:-}" ]; then
  if command -v llama >/dev/null; then LLAMA_CMD="llama server"; else LLAMA_CMD="llama-server"; fi
fi

[ -f "$CONFIGS" ] || { echo "Configs file not found: $CONFIGS"; exit 1; }
[ -n "$SERVER_SCRIPT" ] && [ -f "$SERVER_SCRIPT" ] || { echo "Set SERVER_SCRIPT=/path/to/your-server-script"; exit 1; }
MODEL="${MODEL:-$(grep -oE -- '--alias[ =][^ \\]+' "$SERVER_SCRIPT" | head -1 | sed -E 's/--alias[ =]//')}"
[ -n "$MODEL" ] || { echo "No --alias in $SERVER_SCRIPT; set MODEL=<name>"; exit 1; }
STAMP="$(date +%Y%m%d_%H%M%S)"; CNAME="$(basename "$CONFIGS" .txt)"
OUT="$SUITE/runs/$MODEL/${STAMP}_$CNAME"; RESULTS="$SUITE/models/$MODEL/results"
mkdir -p "$OUT" "$RESULTS"
LONG_ARGS=(--long-runs "$LONG_RUNS"); [ -n "${NO_LONG:-}" ] && LONG_ARGS=(--no-long)

vram_mib() {  # used VRAM on the largest amdgpu card; "n/a" elsewhere (add your own for other vendors)
  local best_t=0 best_u=""
  for c in /sys/class/drm/card*/device; do
    [ -r "$c/mem_info_vram_total" ] || continue
    local t u; t=$(cat "$c/mem_info_vram_total"); u=$(cat "$c/mem_info_vram_used")
    if [ "$t" -gt "$best_t" ]; then best_t=$t; best_u=$u; fi
  done
  if [ -n "$best_u" ]; then echo $(( best_u / 1048576 ))
  elif command -v nvidia-smi >/dev/null; then nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits | sort -n | tail -1
  else echo "n/a"; fi
}

stop_server() {
  # Wait until the server PROCESSES are gone, not just the port: a server can close its port and
  # still hold its VRAM while exiting, and the next server would then spill into system RAM.
  [ -n "${SPID:-}" ] && kill "$SPID" 2>/dev/null
  pkill -f -- 'llama[ -]server' 2>/dev/null
  for _ in $(seq 1 60); do pgrep -f -- 'llama[ -]server' >/dev/null || break; sleep 1; done
  if pgrep -f -- 'llama[ -]server' >/dev/null; then
    pkill -9 -f -- 'llama[ -]server' 2>/dev/null
    for _ in $(seq 1 30); do pgrep -f -- 'llama[ -]server' >/dev/null || break; sleep 1; done
  fi
  sleep 3
}
server_still_running() { pgrep -f -- 'llama[ -]server' >/dev/null; }

stop_server; sleep 2
HELP="$($LLAMA_CMD --help 2>&1)"
echo "$HELP" | grep -iE -- 'spec|draft|mtp|ngram|lookup|p-min|n-min|min-p|cache-type|ubatch|batch-size' > "$OUT/help_spec.txt"
gpu_power() { for h in /sys/class/drm/card*/device/hwmon/hwmon*/power1_cap; do [ -r "$h" ] && echo "$(( $(cat "$h") / 1000000 )) W"; done | sort -u | tr '\n' ' '; }
{
  echo "- Date: $(date '+%Y-%m-%d %H:%M %Z')   Suite: prompts v$(cat "$SUITE/prompts/VERSION" 2>/dev/null), configs: $CNAME"
  echo "- Model: $MODEL   Server script: $SERVER_SCRIPT"
  echo "- llama.cpp: $($LLAMA_CMD --version 2>&1 | grep -iE 'version|build' | head -2 | tr '\n' ' ')"
  echo "- Devices: $($LLAMA_CMD --list-devices 2>&1 | grep -iE 'vulkan|cuda|rocm|metal|sycl' | head -4 | sed 's/^ *//' | tr '\n' ';')"
  echo "- CPU: $(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2 | sed 's/^ //')   RAM: $(free -g 2>/dev/null | awk '/Mem:/{print $2" GB"}')   Kernel: $(uname -r)"
  echo "- GPU power cap: $(gpu_power)  ${HW_NOTES:+  Notes: $HW_NOTES}"
  echo "- Runs: short ×$RUNS, long ×$LONG_RUNS (${NO_LONG:+skipped}), max_tokens $MAX_TOKENS, seeds 1001+; idle VRAM before sweep: $(vram_mib) MiB"
} > "$OUT/header.txt"
cp "$CONFIGS" "$OUT/configs.txt"
printf "name\tflags\tnote\tstatus\tload_s\tvram_mib\n" > "$OUT/meta.tsv"
echo "Model: $MODEL   Raw output: $OUT"

trap 'echo; echo "Interrupted; stopping server."; stop_server; exit 130' INT TERM

while IFS=$'\t' read -r NAME FLAGS NOTE; do
  [ "$FLAGS" = "-" ] && FLAGS=""; [ "$NOTE" = "-" ] && NOTE=""
  echo; echo "===== $NAME: ${FLAGS:-(script unchanged)} ====="
  status="ok"; load_s="-"; vram="-"
  if [ -n "${ONLY:-}" ] && ! grep -qw -- "$NAME" <<<"$ONLY"; then echo "(not in ONLY list, skipped)"; continue; fi
  for f in $FLAGS; do
    k="${f%%=*}"
    if ! grep -qE -- "(^|[ ,])${k}([ ,=]|$)" <<<"$HELP"; then status="skipped: $k not in this llama.cpp build"; fi
  done
  if [ "$status" != "ok" ]; then
    echo "$status"
    printf "%s\t%s\t%s\t%s\t%s\t%s\n" "$NAME" "$FLAGS" "$NOTE" "$status" "$load_s" "$vram" >> "$OUT/meta.tsv"
    continue
  fi
  stop_server
  S="$OUT/server-$NAME.sh"; LOG="$OUT/server_$NAME.log"
  # shellcheck disable=SC2086
  python3 "$TOOLS" edit "$SERVER_SCRIPT" "$S" "$LOG" $FLAGS || status="config edit failed"
  if [ "$status" = "ok" ] && server_still_running; then status="previous server would not stop (VRAM busy); not run"; fi
  if [ "$status" = "ok" ]; then
    t0=$(date +%s)
    "$S" > "$OUT/server_$NAME.stdout" 2>&1 &
    SPID=$!
    until curl -sf -o /dev/null "$URL/health"; do
      if ! kill -0 "$SPID" 2>/dev/null; then status="server exited during load"; break; fi
      if [ $(( $(date +%s) - t0 )) -gt 600 ]; then status="load timeout (600 s)"; break; fi
      sleep 2
    done
    load_s=$(( $(date +%s) - t0 ))
  fi
  if [ "$status" = "ok" ]; then
    vram=$(vram_mib)
    probe=$(curl -s -m 120 "$URL/v1/chat/completions" -H 'Content-Type: application/json' \
      -d '{"messages":[{"role":"user","content":"Count from 1 to 40."}],"max_tokens":64}' |
      python3 -c 'import sys,json
try: print(round(json.load(sys.stdin)["timings"]["predicted_per_second"]))
except Exception: print(0)')
    if [ "${probe:-0}" -lt "${MIN_TPS:-20}" ]; then
      status="too slow (${probe} t/s on a 64-token check; likely spilled out of VRAM); not run"
    else
      echo "Loaded in ${load_s}s, VRAM ${vram} MiB, speed check ${probe} t/s. Benchmarking..."
      ( cd "$OUT" && python3 "$BENCH" run "$NAME" --url "$URL" --runs "$RUNS" --max-tokens "$MAX_TOKENS" \
          --suite "$SUITE" "${LONG_ARGS[@]}" ) || status="benchmark error"
    fi
  fi
  if [ "$status" != "ok" ]; then
    echo "$NAME: $status"
    [ -s "$LOG" ] || cp "$OUT/server_$NAME.stdout" "$LOG" 2>/dev/null
  fi
  printf "%s\t%s\t%s\t%s\t%s\t%s\n" "$NAME" "$FLAGS" "$NOTE" "$status" "$load_s" "$vram" >> "$OUT/meta.tsv"
  stop_server; SPID=""
done < <(python3 "$TOOLS" configs "$CONFIGS")

echo; echo "===== Building report ====="
python3 "$TOOLS" report "$OUT"
FINAL="$RESULTS/${STAMP}_$CNAME.md"
cp "$OUT/report.md" "$FINAL"
echo "Done. Report: $FINAL"
echo "Raw output: $OUT"
echo "(No server is running now; start yours as usual.)"
