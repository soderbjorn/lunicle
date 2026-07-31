#!/usr/bin/env bash
#
# Stop whatever Lunicle you have running locally, whichever way you started it.
#
#   ./scripts/stop-all.sh              # stop everything local. Keeps all data.
#   ./scripts/stop-all.sh --status     # say what's running; change nothing.
#
# Env:
#   LUNICLE_PORT   the port to inspect. Unset, the running dev server is asked
#                  which port it bound, and only then does this fall back to 8080.
#
# ── Why this exists ──────────────────────────────────────────────────────────
#
# There are three ways to have a Lunicle listening on the tracker's port, and
# they are stopped in three different ways:
#
#   * ./scripts/run-dev.sh          → a JVM forked by the Gradle *daemon*. Ctrl-C
#                                     on the script does not always take it with
#                                     it, because it was never the script's child.
#   * ./scripts/run-container.sh    → a Docker container, which outlives your
#     (or container-up.sh)            terminal entirely and is still there after
#                                     a reboot if Docker starts on login.
#   * a site preview (preview-*-embedded.sh, run from a site repo)
#                                   → a python http.server for that site, on SITE_PORT.
#   * ./scripts/run-demo.sh         → a python http.server over the built JS
#                                     bundle, on LUNICLE_DEMO_PORT. No JVM and no
#                                     database: demo mode is all in the tab.
#
# Before this script, "something is already serving http://localhost:8080/" left
# you to work out which of the three it was and which incantation stopped it.
# That is a papercut every single time, and the answer is never interesting.
#
# ── Which port it reports on ─────────────────────────────────────────────────
#
# The dev server is stopped by MARKER, not by port, so the kill was always
# right — but the closing verdict named 8080 unless you happened to repeat
# LUNICLE_PORT on this invocation too. Start on 8099 in one terminal, stop from
# another, and it said "Port 8080 is free" about a port that had never been
# taken, at the very moment it had in fact just stopped your server. So an unset
# LUNICLE_PORT now asks the running server which port it holds instead of
# assuming.
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
#   ./scripts/dev-db.sh wipe           # the dev server's database
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# Read BEFORE lib/dev-server.sh is sourced, because that file defaults
# LUNICLE_PORT to 8080 for its own start_dev_server — after which "did the caller
# ask for a port?" is no longer a question this script can answer.
port_requested="${LUNICLE_PORT:-}"
# shellcheck source=lib/probe.sh
source "$SCRIPT_DIR/lib/probe.sh"
# DEV_SERVER_MARKER, so the marker this kills by is defined in exactly one place
# — the same one the run scripts start with. Two scripts with their own idea of
# which process is "ours" is one that eventually kills the wrong thing, or
# nothing at all.
# shellcheck source=lib/dev-server.sh
source "$SCRIPT_DIR/lib/dev-server.sh"

# An explicit LUNICLE_PORT wins. Failing that, ask the dev server which port it
# bound — which must happen HERE, before the kill below takes the process the
# answer is read from. 8080 is the last resort, for when nothing of ours is up
# and the number is therefore only in the closing "the port is free" line.
LUNICLE_PORT="$port_requested"
if [[ -z "$LUNICLE_PORT" ]]; then LUNICLE_PORT="$(running_dev_server_port)"; fi
LUNICLE_PORT="${LUNICLE_PORT:-8080}"
SITE_PORT="${SITE_PORT:-8000}"
# No LUNICLE_DEMO_PORT here on purpose: section 4 below finds the demo by the
# directory it serves and then reads its port off the process, so this script no
# longer needs a second copy of that default to be wrong about.
CONTAINER="lunicle-local"

status_only=0
for arg in "$@"; do
  case "$arg" in
    --status) status_only=1 ;;
    -h|--help) sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "usage: $0 [--status]" >&2; exit 2 ;;
  esac
done

docker_running() { docker info > /dev/null 2>&1; }

container_state() {
  docker_running || return 1
  docker ps --filter "name=^${CONTAINER}$" --format '{{.Status}}' 2>/dev/null | grep -q . || return 1
}

echo "==> Looking for a local Lunicle…"

found=0

# ---- 1. The dev server (run-dev.sh) ----
if pgrep -f "$DEV_SERVER_MARKER" > /dev/null 2>&1; then
  found=1
  pids="$(pgrep -f "$DEV_SERVER_MARKER" | tr '\n' ' ')"
  if [[ "$status_only" -eq 1 ]]; then
    echo "    dev server        — running (pid ${pids% })"
  else
    echo "    dev server        — stopping (pid ${pids% })"
    pkill -f "$DEV_SERVER_MARKER" 2>/dev/null || true
  fi
else
  [[ "$status_only" -eq 1 ]] && echo "    dev server        — not running"
fi

# ---- 2. The container ----
if container_state; then
  found=1
  if [[ "$status_only" -eq 1 ]]; then
    echo "    container         — running ($(docker ps --filter "name=^${CONTAINER}$" --format '{{.Status}}'))"
  else
    echo "    container         — handing off to container-down.sh"
    # Delegated rather than reimplemented as `docker rm -f`: container-down.sh
    # already owns the container's lifecycle, knows the volume's name, and says
    # the right things about keeping it. Two scripts that both know how to stop
    # the container is one that will eventually be wrong.
    "$REPO_ROOT/scripts/container-down.sh" | sed 's/^/       /'
  fi
elif docker_running; then
  [[ "$status_only" -eq 1 ]] && echo "    container         — not running"
else
  [[ "$status_only" -eq 1 ]] && echo "    container         — Docker isn't running, so neither is it"
fi

# ---- 3. The embedded site's python server ----
# Matched on the port as well as the command, because a bare `python3 -m
# http.server` is a thing people run for all sorts of reasons and this must only
# take the one a site's preview script started.
if pgrep -f "http.server $SITE_PORT" > /dev/null 2>&1; then
  found=1
  if [[ "$status_only" -eq 1 ]]; then
    echo "    lunamux-web site  — running on :$SITE_PORT"
  else
    echo "    lunamux-web site  — stopping (:$SITE_PORT)"
    pkill -f "http.server $SITE_PORT" 2>/dev/null || true
  fi
else
  [[ "$status_only" -eq 1 ]] && echo "    lunamux-web site  — not running"
fi

# ---- 4. run-demo.sh's static server ----
# Matched on the DIRECTORY it is serving rather than on a port, which is what makes
# this the one section that finds a demo wherever it was started. `python3 -m
# http.server` is something people run for all sorts of reasons, so it still needs
# attributing precisely — but `--directory <this checkout>/web/build/…` is a better
# attribution than a port is, and unlike a port it does not have to be guessed.
#
# It was matched on `http.server $LUNICLE_DEMO_PORT`, and so found nothing whenever
# the demo was on any port but 8081 — reporting "not running" about a server that
# was, which is the dev server's old lie in a second place. See
# running_dev_server_port for that one.
#
# Normally Ctrl-C in run-demo.sh has already taken it — it is that script's own
# child, not a Gradle-forked orphan — so this is for the terminal you closed rather
# than stopped.
demo_marker="http.server .*--directory $REPO_ROOT/web/build"
if pgrep -f "$demo_marker" > /dev/null 2>&1; then
  found=1
  # The port it actually bound, off its own command line, so the line below names
  # the address you would have been visiting.
  demo_port="$(
    ps -o command= -p $(pgrep -f "$demo_marker" | tr '\n' ' ') 2>/dev/null |
      sed -n 's/.*http\.server \([0-9][0-9]*\).*/\1/p' | tail -n 1
  )"
  if [[ "$status_only" -eq 1 ]]; then
    echo "    demo bundle       — running on :${demo_port:-?}"
  else
    echo "    demo bundle       — stopping (:${demo_port:-?})"
    pkill -f "$demo_marker" 2>/dev/null || true
  fi
else
  [[ "$status_only" -eq 1 ]] && echo "    demo bundle       — not running"
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
# Verify rather than assume — see wait_for_port_free in lib/probe.sh for why a
# successful `pkill` is not the same as a freed port.
wait_for_port_free "$LUNICLE_PORT" || true

holder="$(port_holder "$LUNICLE_PORT")"
echo
if [[ -z "$holder" ]]; then
  echo "==> Port $LUNICLE_PORT is free. The run scripts will start cleanly."
  exit 0
fi

# Something is still there and it is not ours — so say what it is and stop,
# rather than escalating to something indiscriminate.
echo "==> Port $LUNICLE_PORT is STILL held by: $holder" >&2
echo "    That is not something this script started, so it has been left alone." >&2
echo "    Either stop it yourself, or run the tracker elsewhere:" >&2
# 8099 rather than 8081: 8081 is run-demo.sh's own default port, so advising it
# sends you straight into the next port collision.
echo "      LUNICLE_PORT=8099 ./scripts/run-dev.sh" >&2
exit 1
