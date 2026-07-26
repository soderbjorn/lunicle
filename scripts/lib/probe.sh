#!/usr/bin/env bash
#
# Source (don't run) this for "is it up?" and "who has the port?".
#
#   source "$SCRIPT_DIR/lib/probe.sh"
#
# Both questions used to be answered by copy-pasted curl loops and lsof
# invocations in four scripts, each carrying its own copy of the two subtleties
# below. They are the kind of thing you get right once and then silently get
# wrong in the fifth copy, so there is now exactly one of each.

# The URL that answers "is a Lunicle listening here?".
#
# /api/session, NOT /api/counter: the counter 401s for a signed-out caller (it
# belongs to a user), and `curl -sf` treats 401 as failure — so probing it would
# report a perfectly healthy server as absent. /api/session answers 200 for
# everyone, signed in or not, which is exactly what a liveness probe wants.
lunicle_probe_url() {
  echo "http://localhost:$1/api/session"
}

# What is LISTENING on a port, as a human-readable line — or nothing.
#
# -sTCP:LISTEN matters: a bare `lsof -i :PORT` also matches CLIENTS with a
# connection to that port, so an open browser tab pointing at localhost:8080 —
# or Docker's own backend proxy — would count as "the port is taken". Only a
# listener actually holds it.
#
# The trailing `|| true` is not decoration. lsof exits 1 when it matches
# nothing, and every caller runs under `set -o pipefail`, so without it this
# function FAILS precisely when the port is free — and `set -e` then kills the
# caller mid-sentence. That is how `stop-all.sh --status` came to exit 1 without
# ever printing its verdict: the happy path was the fatal one.
port_holder() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {print $1" (pid "$2")"; exit}' || true
}

# port_is_held PORT — true if anything is listening.
port_is_held() {
  [[ -n "$(port_holder "$1")" ]]
}

# wait_for_http URL TRIES INTERVAL [GUARD_PID]
#
#   0 — it answered
#   1 — it never did within TRIES
#   2 — GUARD_PID died first, so waiting the full TRIES would be pointless
#
# The guard exists because the interesting failure is not "slow", it is "the
# thing you are waiting for exited two seconds in". Without it you wait out the
# entire timeout and then report a timeout, when the real answer — and the
# output explaining it — was available immediately.
wait_for_http() {
  local url="$1" tries="$2" interval="$3" guard="${4:-}"
  local i
  for ((i = 0; i < tries; i++)); do
    if curl -sf -o /dev/null "$url" 2>/dev/null; then return 0; fi
    if [[ -n "$guard" ]] && ! kill -0 "$guard" 2>/dev/null; then return 2; fi
    sleep "$interval"
  done
  return 1
}

# wait_for_port_free PORT — after a kill. `pkill` returning 0 means a signal was
# delivered, not that the process is gone: a JVM takes a moment to die, and
# reporting "stopped" while it still holds the port is exactly the lie that
# sends you back to the run script only to meet the same error again.
wait_for_port_free() {
  local i
  for ((i = 0; i < 20; i++)); do
    port_is_held "$1" || return 0
    sleep 0.25
  done
  return 1
}
