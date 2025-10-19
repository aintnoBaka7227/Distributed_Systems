#!/usr/bin/env bash
# Robust shell settings: 
#  -e : exit immediately on any command with a non-zero status
#  -u : treat unset variables as errors
#  -o pipefail : a pipeline fails if any command in the pipeline fails
set -euo pipefail

# Resolve the repository root to the directory that contains this script,
# then change into it so all relative paths are stable regardless of caller CWD.
ROOT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$ROOT_DIR"

# Configuration file can be supplied as the first argument; default to network.config.
CONFIG=${1:-network.config}
# Logical member IDs used across scenarios; these map to ports 9001..9009 by convention in the Java code.
MEMBERS=(M1 M2 M3 M4 M5 M6 M7 M8 M9)

echo "Cleaning previous build and logs..."
# Remove previous build outputs and log folders (if present). Ignore errors if they don't exist.
rm -rf out logs 2>/dev/null || true

echo "Compiling sources..."
# Prefer a project-provided Makefile target if available; otherwise compile the Java sources directly
# into the ./out directory (single-package layout under org/* assumed).
if command -v make >/dev/null 2>&1; then
  make build
else
  mkdir -p out
  javac -d out src/main/java/org/*.java
fi

# Graceful shutdown/cleanup for any background processes we spawn.
kill_bg() {
  # Try to kill any background jobs started by this script.
  jobs -p | xargs -r kill || true
  # Safety net: if anything named org.CouncilMember is still around, kill it.
  if command -v pkill >/dev/null 2>&1; then
    pkill -f "org.CouncilMember" || true
  fi
  # Give the OS a moment to release ports so the next scenario can bind.
  sleep 1
}
# Ensure kill_bg runs no matter how the script exits.
trap kill_bg EXIT

# Ensure we always have a usable logs/ directory even if a file named "logs" exists.
ensure_logs_dir() {
  if [ -e logs ] && [ ! -d logs ]; then
    echo "[init] 'logs' exists as a file; renaming to logs.bak"
    mv -f logs "logs.bak.$(date +%s)" || true
  fi
  mkdir -p logs
}

# Tunable timeouts (seconds) per scenario. Can be overridden via environment, e.g. TIMEOUT_S1=30 ./run_scenarios.sh
TIMEOUT_S1=${TIMEOUT_S1:-120}
TIMEOUT_S2=${TIMEOUT_S2:-240}
TIMEOUT_S3A=${TIMEOUT_S3A:-180}
TIMEOUT_S3B=${TIMEOUT_S3B:-180}
TIMEOUT_S3C=${TIMEOUT_S3C:-180}
# Stagger between two concurrent proposals in Scenario 2 (allows race testing).
S2_STAGGER=${S2_STAGGER:-0.0001}

# Launch a single CouncilMember process with a profile and capture its PID.
# Logs are redirected to logs/logs_<ID>.txt (note the plural "logs_...").
start_member() {
  local id=$1
  local profile=$2
  echo "Starting $id ($profile)"
  ensure_logs_dir
  # Write per-member logs using the canonical pattern: logs/log_M*.txt
  java -cp out org.CouncilMember "$id" --profile "$profile" --config "$CONFIG" > "logs/log_${id}.txt" 2>&1 &
  echo $!
}

# Poll each Admin port (9001..9009 typically) using the AdminClient's 'help' command
# until the member becomes responsive, or a timeout is hit.
wait_ports() {
  local timeout=${1:-15}
  local start=$(date +%s)
  shift || true
  local ports=("$@")
  for p in "${ports[@]}"; do
    while true; do
      if java -cp out org.AdminClient localhost "$p" help >/dev/null 2>&1; then
        break
      fi
      now=$(date +%s)
      if [ $((now-start)) -ge $timeout ]; then
        echo "Timeout waiting for port $p" >&2
        return 1
      fi
      sleep 0.2
    done
  done
}

# -----------------
# Scenario 1: all nodes reliable; single proposal from M4 for M5.
# -----------------
scenario1() {
  echo "Scenario 1: Ideal Network"
  echo "[S1] Starting members..."
  pids=()
  for id in M1 M2 M3 M4 M5 M6 M7 M8 M9; do
    pids+=( $(start_member "$id" reliable) )
  done
  wait_ports 15 9001 9002 9003 9004 9005 9006 9007 9008 9009
  # Trigger proposal: M4 proposes M5
  echo "[S1] Proposing: M4 -> M5"; send_cmd 9004 propose M5
  echo "[S1] Waiting for consensus..."
  wait_and_time 9 "$TIMEOUT_S1" S1 || true
  # Print winner in required format (first line encountered). Note: search pattern below expects files named log_M*.txt;
  # our start_member writes to logs/logs_M*.txt. The script handles both patterns elsewhere.
  grep -h "^CONSENSUS:" logs/log_M*.txt 2>/dev/null | head -n 1 || true
  echo "[S1] Stopping members"
  kill_bg
}

# -----------------
# Scenario 2: concurrent proposals from M1 and M8, with a tiny configurable stagger.
# -----------------
scenario2() {
  echo "Scenario 2: Concurrent Proposals"
  echo "[S2] Starting members..."
  pids=()
  for id in M1 M2 M3 M4 M5 M6 M7 M8 M9; do
    pids+=( $(start_member "$id" reliable) )
  done
  wait_ports 15 9001 9002 9003 9004 9005 9006 9007 9008 9009
  # Concurrent proposals from M1 and M8
  echo "[S2] Proposing concurrently (stagger=${S2_STAGGER}s): M1 -> M1, M8 -> M8"
  (
    send_cmd 9001 propose M1
  ) &
  (
    sleep "$S2_STAGGER"; send_cmd 9008 propose M8
  ) &
  wait
  echo "[S2] Waiting for consensus..."
  wait_and_time 9 "$TIMEOUT_S2" S2 || true
  # Print winner in required format
  grep -h "^CONSENSUS:" logs/log_M*.txt 2>/dev/null | head -n 1 || true
  echo "[S2] Stopping members"
  kill_bg
}

# -----------------
# Scenario 3: fault-tolerance under mixed profiles and crash behavior.
#   3a: mixed profiles; M4 proposes M5
#   3b: relaunch; M2 proposes self
#   3c: crash M3 shortly after PREPARE; M4 drives consensus on M7, requiring 8 learners
# -----------------
scenario3() {
  echo "Scenario 3: Fault-Tolerance"
  echo "[S3a] Starting members..."
  start_member M1 reliable
  start_member M2 latent
  start_member M3 failure
  start_member M4 standard
  start_member M5 standard
  start_member M6 standard
  start_member M7 standard
  start_member M8 standard
  start_member M9 standard
  wait_ports 20 9001 9002 9003 9004 9005 9006 9007 9008 9009
  # 3a: M4 proposes M5
  echo "[S3a] Proposing: M4 -> M5"; send_cmd 9004 propose M5
  echo "[S3a] Waiting for consensus..."
  wait_and_time 9 "$TIMEOUT_S3A" S3a || true
  grep -h "^CONSENSUS:" logs/log_M*.txt 2>/dev/null | head -n 1 || true
  kill_bg
  save_member_logs s3a
  clear_member_logs

  # Relaunch for 3b
  echo "[S3b] Restarting members..."
  start_member M1 reliable
  start_member M2 latent
  start_member M3 failure
  start_member M4 standard
  start_member M5 standard
  start_member M6 standard
  start_member M7 standard
  start_member M8 standard
  start_member M9 standard
  wait_ports 20 9001 9002 9003 9004 9005 9006 9007 9008 9009
  echo "[S3b] Proposing: M2 -> M2"; send_cmd 9002 propose M2
  echo "[S3b] Waiting for consensus..."
  wait_and_time 9 "$TIMEOUT_S3B" S3b || true
  grep -h "^CONSENSUS:" logs/log_M*.txt 2>/dev/null | head -n 1 || true
  kill_bg
  save_member_logs s3b
  clear_member_logs

  # Relaunch for 3c
  echo "[S3c] Restarting members..."
  start_member M1 reliable
  start_member M2 latent
  start_member M3 failure
  start_member M4 standard
  start_member M5 standard
  start_member M6 standard
  start_member M7 standard
  start_member M8 standard
  start_member M9 standard
  wait_ports 30 9001 9002 9003 9004 9005 9006 9007 9008 9009
  # M3 starts then crashes quickly after PREPARE (before Phase 2)
  echo "[S3c] Throttling M3 then proposing"
  send_cmd 9003 propose M3
  sleep 0.05
  # simulate crash quickly to avoid ACCEPT phase from M3
  echo "[S3c] Crashing M3 quickly"
  send_cmd 9003 crash
  # Another member drives to consensus
  sleep 0.2
  echo "[S3c] Proposing: M4 -> M7"; send_cmd 9004 propose M7
  echo "[S3c] Waiting for consensus (8 learners expected)..."
  wait_and_time 8 "$TIMEOUT_S3C" S3c || true
  grep -h "^CONSENSUS:" logs/log_M*.txt 2>/dev/null | head -n 1 || true
  kill_bg
  save_member_logs s3c
}

# Prepare logs/ upfront so subsequent functions don't race on directory presence.
ensure_logs_dir

# -----------------
# Utilities for per-scenario log rotation and archival.
# Note: different code paths write to either logs/log_M*.txt or logs/logs_M*.txt.
# We conservatively manage both patterns below.
# -----------------
clear_member_logs() {
  rm -f logs/log_M*.txt logs/logs_M*.txt 2>/dev/null || true
}

normalize_member_log_names() {
  # Rename any legacy files logs/logs_M*.txt -> logs/log_M*.txt
  shopt -s nullglob
  for f in logs/logs_M*.txt; do
    base=$(basename "$f")          # e.g., logs_M1.txt
    id=${base#logs_}                # -> M1.txt
    dest="logs/log_${id}"          # -> logs/log_M1.txt
    mv -f "$f" "$dest" 2>/dev/null || true
  done
  shopt -u nullglob
}

save_member_logs() {
  local tag=$1
  local dir="logs/${tag}"
  mkdir -p "$dir"
  # Ensure only canonical names are archived
  normalize_member_log_names
  shopt -s nullglob
  for f in logs/log_M*.txt; do cp "$f" "$dir/"; done
  shopt -u nullglob
}

# Send an admin command to a member on a given port. Prefer nc/ncat for speed; fall back to AdminClient.
send_cmd() {
  local port=$1; shift
  local cmdline="$*"
  if command -v nc >/dev/null 2>&1; then
    printf "%s\n" "$cmdline" | nc localhost "$port" >/dev/null 2>&1 || true
  elif command -v ncat >/dev/null 2>&1; then
    printf "%s\n" "$cmdline" | ncat localhost "$port" >/dev/null 2>&1 || true
  else
    java -cp out org.AdminClient localhost "$port" $cmdline >/dev/null 2>&1 || true
  fi
}

# Count the number of "Consensus reached:" lines across member logs.
# Useful to gate progress until a quorum/total is achieved.
count_consensus() {
  local pattern=${1:-"Consensus reached:"}
  local total=0
  shopt -s nullglob
  for f in logs/log_M*.txt; do
    # grep returns 1 when no matches; ignore that
    local c
    c=$(grep -c "$pattern" "$f" 2>/dev/null || true)
    total=$(( total + c ))
  done
  shopt -u nullglob
  echo "$total"
}

# Wait until 'required' consensus lines appear in logs, or time out.
# Also emits which members are still missing a consensus line to aid debugging.
wait_consensus() {
  local required=$1; shift
  local timeout=${1:-20}; shift || true
  local start=$(date +%s)
  while true; do
    local got=$(count_consensus)
    echo "[wait] consensus lines seen: $got / $required"
    # Show which members are missing, to aid debugging
    local missing=()
    for id in "${MEMBERS[@]}"; do
      local f="logs/log_M${id#M}.txt"
      # our filenames are log_M<ID>.txt where <ID> is like M1; construct path accordingly
      f="logs/log_${id}.txt"
      if [ -f "$f" ]; then
        local c
        c=$(grep -c "Consensus reached:" "$f" 2>/dev/null || true)
        if [ "${c:-0}" -lt 1 ]; then missing+=("$id"); fi
      else
        missing+=("$id")
      fi
    done
    if [ ${#missing[@]} -gt 0 ]; then
      echo "[wait] missing: ${missing[*]}"
    fi
    if [ "$got" -ge "$required" ]; then
      return 0
    fi
    local now=$(date +%s)
    if [ $((now-start)) -ge $timeout ]; then
      echo "[wait] timeout after ${timeout}s with $got/$required consensus" >&2
      return 1
    fi
    sleep 1
  done
}

# Measure the time spent in wait_consensus and print a friendly summary label.
wait_and_time() {
  local required=$1; shift
  local timeout=$1; shift || true
  local label=${1:-wait}; shift || true
  local t0=$(date +%s)
  if wait_consensus "$required" "$timeout"; then
    local t1=$(date +%s)
    echo "[${label}] Consensus achieved in $((t1 - t0))s"
    return 0
  else
    local t1=$(date +%s)
    echo "[${label}] Timed out after $((t1 - t0))s (required=$required, timeout=$timeout)" >&2
    return 1
  fi
}


# ---- Orchestrate scenarios when executed directly.
main_all() {
  clear_member_logs
  scenario1 |& tee logs/scenario1.log || true
  save_member_logs s1
  clear_member_logs

  clear_member_logs
  scenario2 |& tee logs/scenario2.log || true
  save_member_logs s2
  clear_member_logs

  clear_member_logs
  scenario3 |& tee logs/scenario3.log || true
  save_member_logs s3
  clear_member_logs

  echo "Done. See logs/*.log and logs_M*.txt"
}

# If this file is executed (not sourced), run all scenarios.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main_all
fi
