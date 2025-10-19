#!/usr/bin/env bash
#
# run_scenario.sh — Execute one scenario (or all) defined in run_tests.sh
#
# Purpose
# - Convenience wrapper to run a single scenario without executing the whole test suite.
# - Delegates build/start/stop logic to run_tests.sh by sourcing it, then calls the chosen scenario.
#
# Usage
#   ./run_scenario.sh <scenario> [config]
#     <scenario> : 1|2|3|s1|s2|s3|scenario1|scenario2|scenario3|all
#     [config]   : optional path to network config file (default: network.config)
#
# Examples
#   ./run_scenario.sh s1
#   ./run_scenario.sh scenario2 my_network.config
#   ./run_scenario.sh all
#
# Notes
# - This script uses a subshell to avoid leaking traps/vars from run_tests.sh into the parent shell.
# - Per-member logs and scenario summaries are written under logs/.
# - Scenarios 3a/3b/3c are run together via scenario3.
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$ROOT_DIR"

usage() {
  # Print compact usage help
  echo "Usage: $0 <scenario> [config]" >&2
  echo "  scenario: 1|2|3|s1|s2|s3|scenario1|scenario2|scenario3|all" >&2
  echo "  config  : optional config file path (default: network.config)" >&2
}

if [[ ${1:-} == "-h" || ${1:-} == "--help" || $# -lt 1 ]]; then
  usage
  exit 1
fi

scenario_raw=${1}                 # raw scenario selector from user
config=${2:-network.config}       # optional config path (defaults to network.config)

normalize() {
  # Map various aliases to canonical function names defined in run_tests.sh
  local s="$1"
  case "${s,,}" in
    1|s1|scenario1) echo "scenario1" ;;
    2|s2|scenario2) echo "scenario2" ;;
    3|s3|scenario3) echo "scenario3" ;;
    all) echo "all" ;;
    *) echo "" ;;
  esac
}

scenario=$(normalize "$scenario_raw")
if [[ -z "$scenario" ]]; then
  echo "Unknown scenario: $scenario_raw" >&2
  usage
  exit 1
fi

run_cmd() {
  # Execute the chosen scenario in a fresh Bash process
  local scen="$1"; shift
  # Run in a fresh bash so run_tests.sh settings/traps don't leak to this script's shell
  bash -c '
    set -euo pipefail
    ROOT_DIR=$(cd "$(dirname "$0")" && pwd)
    cd "$ROOT_DIR"
    # Source with config as $1 inside run_tests.sh
    source ./run_tests.sh "$1"
    ensure_logs_dir
    clear_member_logs
    case "$2" in
      scenario1)
        scenario1 |& tee logs/scenario1.log || true
        save_member_logs s1
        ;;
      scenario2)
        scenario2 |& tee logs/scenario2.log || true
        save_member_logs s2
        ;;
      scenario3)
        scenario3 |& tee logs/scenario3.log || true
        save_member_logs s3
        ;;
      all)
        main_all
        ;;
    esac
    clear_member_logs
  ' bash "$config" "$scen"
}

run_cmd "$scenario"

echo "Done. See logs/*.log and per-scenario archives under logs/"
