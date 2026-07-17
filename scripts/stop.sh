#!/usr/bin/env bash
#
# Stop whatever Lunicle you have running locally, whichever way you started it.
#
#   ./scripts/stop.sh              # stop everything local. Keeps all data.
#   ./scripts/stop.sh --status     # say what's running; change nothing.
#
# Env:
#   LUNICLE_PORT   the port to inspect (default: 8080)
#
# ── Why this exists ──────────────────────────────────────────────────────────
#
# There are three ways to have a Lunicle listening on 8080, and they are stopped
# in three different ways:
#
#   * ./scripts/dev-local.sh      → a JVM forked by the Gradle *daemon*. Ctrl-C
#                                   on the script does not always take it with
#                                   it, because it was never the script's child.
#   * ./scripts/container-up.sh   → a Docker container, which outlives your
#     (or run-standalone.sh,        terminal entirely and is still there after a
#      or run-embedded.sh)          reboot if Docker starts on login.
#   * ./scripts/dev-local.sh      → a python http.server for the lunamux site,
#     --embed                       on SITE_PORT.
#
# Before this script, "something is already serving http://localhost:8080/" left
# you to work out which of the three it was and which incantation stopped it.
# That is a papercut every single time, and the answer is never interesting.
#
# ── What it will not do ──────────────────────────────────────────────────────
#
# It only stops things it can positively attribute to *this checkout*: the
# container by name, the server by a marker containing this repo's absolute
# path, the site server by its port and command line. Anything else holding the
# port is reported and left alone — a stop script that killed whatever it found
# on 8080 would eventually take out something you cared about.
#
# It never deletes data. The Docker volume and ~/.lunicle/ both survive; that is
# the point of them. To wipe:
#
#   ./scripts/container-down.sh --wipe   # the container's volume
#   ./scripts/local-db.sh wipe           # the dev-local database
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LUNICLE_PORT="${LUNICLE_PORT:-8080}"
SITE_PORT="${SITE_PORT:-8000}"
CONTAINER="lunicle-local"

# The same marker dev-local.sh uses to find its own orphans. It contains this
# checkout's absolute path, so it cannot match another clone's server, another
# project's JVM, or anything else on the machine.
SERVER_MARKER="lunicle.webDist=$REPO_ROOT/web/build"

status_only=0
for arg in "$@"; do
  case "$arg" in
    --status) status_only=1 ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "usage: $0 [--status]" >&2; exit 2 ;;
  esac
done

# What is listening on a port, as a human-readable line — or nothing.
#
# -sTCP:LISTEN matters: a bare `lsof -i :PORT` also matches CLIENTS with a
# connection to that port, so an open browser tab pointing at localhost:8080
# would count as "the port is taken". Only a listener holds it. Same reasoning
# as container-up.sh's port check.
port_holder() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {print $1" (pid "$2")"; exit}'
}

docker_running() { docker info > /dev/null 2>&1; }

container_state() {
  docker_running || return 1
  docker ps --filter "name=^${CONTAINER}$" --format '{{.Status}}' 2>/dev/null | grep -q . || return 1
}

echo "==> Looking for a local Lunicle…"

found=0

# ---- 1. The dev-local.sh server ----
if pgrep -f "$SERVER_MARKER" > /dev/null 2>&1; then
  found=1
  pids="$(pgrep -f "$SERVER_MARKER" | tr '\n' ' ')"
  if [[ "$status_only" -eq 1 ]]; then
    echo "    dev-local.sh server  — running (pid ${pids% })"
  else
    echo "    dev-local.sh server  — stopping (pid ${pids% })"
    pkill -f "$SERVER_MARKER" 2>/dev/null || true
  fi
else
  [[ "$status_only" -eq 1 ]] && echo "    dev-local.sh server  — not running"
fi

# ---- 2. The container ----
if container_state; then
  found=1
  if [[ "$status_only" -eq 1 ]]; then
    echo "    container            — running ($(docker ps --filter "name=^${CONTAINER}$" --format '{{.Status}}'))"
  else
    echo "    container            — handing off to container-down.sh"
    # Delegated rather than reimplemented as `docker rm -f`: container-down.sh
    # already owns the container's lifecycle, knows the volume's name, and says
    # the right things about keeping it. Two scripts that both know how to stop
    # the container is one that will eventually be wrong.
    "$REPO_ROOT/scripts/container-down.sh" | sed 's/^/       /'
  fi
elif docker_running; then
  [[ "$status_only" -eq 1 ]] && echo "    container            — not running"
else
  [[ "$status_only" -eq 1 ]] && echo "    container            — Docker isn't running, so neither is it"
fi

# ---- 3. The embedded site's python server ----
# Matched on the port as well as the command, because a bare `python3 -m
# http.server` is a thing people run for all sorts of reasons and this must only
# take the one dev-local.sh --embed started.
if pgrep -f "http.server $SITE_PORT" > /dev/null 2>&1; then
  found=1
  if [[ "$status_only" -eq 1 ]]; then
    echo "    lunamux-web site     — running on :$SITE_PORT"
  else
    echo "    lunamux-web site     — stopping (:$SITE_PORT)"
    pkill -f "http.server $SITE_PORT" 2>/dev/null || true
  fi
else
  [[ "$status_only" -eq 1 ]] && echo "    lunamux-web site     — not running"
fi

if [[ "$status_only" -eq 1 ]]; then
  holder="$(port_holder "$LUNICLE_PORT")"
  echo
  if [[ -n "$holder" ]]; then
    echo "==> Port $LUNICLE_PORT is held by: $holder"
  else
    echo "==> Port $LUNICLE_PORT is free."
  fi
  exit 0
fi

if [[ "$found" -eq 0 ]]; then
  echo "    nothing of ours is running."
fi

# ---- Did it actually work? ----
#
# Verify rather than assume. `pkill` returning 0 means a signal was delivered,
# not that the process is gone — a JVM takes a moment to die, and reporting
# "stopped" while it still holds the port is exactly the lie that sends you back
# to dev-local.sh only to meet the same error again.
for _ in $(seq 1 20); do
  [[ -z "$(port_holder "$LUNICLE_PORT")" ]] && break
  sleep 0.25
done

holder="$(port_holder "$LUNICLE_PORT")"
echo
if [[ -z "$holder" ]]; then
  echo "==> Port $LUNICLE_PORT is free. ./scripts/dev-local.sh will start cleanly."
  exit 0
fi

# Something is still there and it is not ours — so say what it is and stop,
# rather than escalating to something indiscriminate.
echo "==> Port $LUNICLE_PORT is STILL held by: $holder" >&2
echo "    That is not something this script started, so it has been left alone." >&2
echo "    Either stop it yourself, or run the tracker elsewhere:" >&2
echo "      LUNICLE_PORT=8081 ./scripts/dev-local.sh" >&2
exit 1
