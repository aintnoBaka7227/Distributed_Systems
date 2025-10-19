#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$ROOT_DIR"

CONFIG=${1:-network.config}
MEMBERS=(M1 M2 M3 M4 M5 M6 M7 M8 M9)

echo "Cleaning previous build and logs..."
rm -rf out logs 2>/dev/null || true

echo "Compiling sources..."
if command -v make >/dev/null 2>&1; then
  make build
else
  mkdir -p out
  javac -d out src/main/java/org/*.java
fi

kill_bg() {
  jobs -p | xargs -r kill || true
  # Fallback: kill any lingering CouncilMember processes
  if command -v pkill >/dev/null 2>&1; then
    pkill -f "org.CouncilMember" || true
  fi
  # Give the OS a moment to release ports
  sleep 1
}
trap kill_bg EXIT

# Ensure a proper logs directory even if a file named 'logs' exists
ensure_logs_dir() {
  if [ -e logs ] && [ ! -d logs ]; then
    echo "[init] 'logs' exists as a file; renaming to logs.bak"
    mv -f logs "logs.bak.$(date +%s)" || true
  fi
  mkdir -p logs
}

# Tunable timeouts (seconds). Override via environment variables.
TIMEOUT_S1=${TIMEOUT_S1:-120}
TIMEOUT_S2=${TIMEOUT_S2:-240}
TIMEOUT_S3A=${TIMEOUT_S3A:-180}
TIMEOUT_S3B=${TIMEOUT_S3B:-180}
TIMEOUT_S3C=${TIMEOUT_S3C:-180}

start_member() {
  local id=$1
  local profile=$2
  echo "Starting $id ($profile)"
  ensure_logs_dir
  java -cp out org.CouncilMember "$id" --profile "$profile" --config "$CONFIG" > "logs/logs_${id}.txt" 2>&1 &
  echo $!
}

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
  echo "[S1] Stopping members"
  kill_bg
}

scenario2() {
  echo "Scenario 2: Concurrent Proposals"
  echo "[S2] Starting members..."
  pids=()
  for id in M1 M2 M3 M4 M5 M6 M7 M8 M9; do
    pids+=( $(start_member "$id" reliable) )
  done
  wait_ports 15 9001 9002 9003 9004 9005 9006 9007 9008 9009
  # Concurrent proposals from M1 and M8
  echo "[S2] Proposing concurrently: M1 -> M1, M8 -> M8"; send_cmd 9001 propose M1
  sleep 0.001
  send_cmd 9008 propose M8
  echo "[S2] Waiting for consensus..."
  wait_and_time 9 "$TIMEOUT_S2" S2 || true
  echo "[S2] Stopping members"
  kill_bg
}

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
  wait_ports 15 9001 9002 9003 9004 9005 9006 9007 9008 9009
  # 3a: M4 proposes M5
  echo "[S3a] Proposing: M4 -> M5"; send_cmd 9004 propose M5
  echo "[S3a] Waiting for consensus..."
  wait_and_time 9 "$TIMEOUT_S3A" S3a || true
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
  wait_ports 15 9001 9002 9003 9004 9005 9006 9007 9008 9009
  echo "[S3b] Proposing: M2 -> M2"; send_cmd 9002 propose M2
  echo "[S3b] Waiting for consensus..."
  wait_and_time 9 "$TIMEOUT_S3B" S3b || true
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
  wait_ports 15 9001 9002 9003 9004 9005 9006 9007 9008 9009
  # M3 starts then crashes quickly after PREPARE (before Phase 2)
  echo "[S3c] Throttling M3 then proposing"
  send_cmd 9003 latency 800 1200
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
  kill_bg
  save_member_logs s3c
}

ensure_logs_dir

# Utilities to manage per-scenario log rotation
clear_member_logs() {
  rm -f logs/log_M*.txt logs/logs_M*.txt 2>/dev/null || true
}

save_member_logs() {
  local tag=$1
  local dir="logs/${tag}"
  mkdir -p "$dir"
  shopt -s nullglob
  for f in logs/log_M*.txt; do cp "$f" "$dir/"; done
  for f in logs/logs_M*.txt; do cp "$f" "$dir/"; done
  shopt -u nullglob
}

# Send an admin command to a member (uses nc/ncat if present; falls back to AdminClient)
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

# Helpers to wait for consensus lines in app logs
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

# Time the consensus wait and print elapsed duration
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
