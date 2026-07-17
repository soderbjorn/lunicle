#!/usr/bin/env bash
#
# Source (don't run) this to start the tracker from your WORKING TREE, via
# `:server:run`. The container path is lib/container.sh's business instead.
#
#   source "$SCRIPT_DIR/lib/dev-server.sh"
#
# Env:
#   LUNICLE_PORT   the tracker's port (default: 8080)
#
# ── Why everything here is a -P property and not an environment variable ─────
#
# `:server:run` is a JavaExec, so the server inherits the long-lived Gradle
# *daemon's* environment rather than the environment of the shell that started
# it. Anything exported here would therefore be whatever the daemon happened to
# start with, and would stay that way — stale, invisibly — until the daemon
# died. A -P property is per-invocation and cannot drift.
#
# This is not theoretical, and it has bitten every variable in turn:
#
#   * PORT: LUNICLE_PORT used to be honoured by the health checks and by nothing
#     else, so `LUNICLE_PORT=9000 ...` started a server on 8080 and then waited
#     two minutes for it to answer on 9000 before declaring it never came up.
#   * The OAuth secrets: a rotated secret kept resolving to the old value.
#   * FRAME_ANCESTORS: see resolveFrameAncestors() in Application.kt.
#
# The deployed container does use environment variables for all of these — no
# daemon there, and it is how Railway supplies them. Same values, two mechanisms,
# for a reason.

# shellcheck source=probe.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/probe.sh"
#
# lib/env.sh is deliberately NOT sourced here, even though start_dev_server
# needs what it defines. stop.sh sources this file too — only for
# DEV_SERVER_MARKER — and sourcing env.sh from here would make every `stop.sh`
# read your .env and warn about its permissions, for credentials it will never
# use. So the two run-dev-* scripts source env.sh themselves, symmetrically with
# the run-container-* pair, and start_dev_server checks below that they did.

LUNICLE_REPO_ROOT="${LUNICLE_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
LUNICLE_PORT="${LUNICLE_PORT:-8080}"

# The server JVM is identified by this marker: `:server:run` passes it, it
# contains this checkout's absolute path, and nothing else on the machine will
# match it. That precision is what lets us reap orphans without ever touching an
# unrelated JVM — or another clone's server. stop.sh uses the same marker.
DEV_SERVER_MARKER="lunicle.webDist=$LUNICLE_REPO_ROOT/web/build"

# Refuse to start on top of something already serving the port — do NOT just
# adopt it. `:server:run` forks the server from the Gradle *daemon*, so an
# earlier run's server can outlive its script and keep the port bound; a run
# that silently talked to that stale process would report whatever the OLD
# server was configured with (its framing policy, its code) while looking
# perfectly healthy. That is a genuinely misleading failure — it cost real time
# during this stage's development — so it is now loud.
require_dev_port_free() {
  curl -sf -o /dev/null "$(lunicle_probe_url "$LUNICLE_PORT")" 2>/dev/null || return 0

  echo "error: something is already serving http://localhost:$LUNICLE_PORT/" >&2
  # Say WHICH of the three it is. There are three ways to have a Lunicle on this
  # port — a dev server, the Docker container, or something unrelated — and they
  # are stopped three different ways. "Stop it" without saying what "it" is
  # leaves you to work that out every time, which is a papercut whose answer is
  # never interesting.
  if pgrep -f "$DEV_SERVER_MARKER" > /dev/null 2>&1; then
    echo "       It's an orphaned dev server from an earlier run." >&2
    echo "       Stop it with:  ./scripts/stop.sh" >&2
  elif docker ps --filter "name=^lunicle-local$" --format '{{.Names}}' 2>/dev/null | grep -q .; then
    echo "       It's the 'lunicle-local' Docker container." >&2
    echo "       Stop it with:  ./scripts/stop.sh          # keeps its data" >&2
    echo "       Note it runs the image you last BUILT, not your working tree." >&2
  else
    local holder
    holder="$(port_holder "$LUNICLE_PORT")"
    if [[ -n "$holder" ]]; then
      echo "       It's: $holder — not something these scripts started." >&2
    fi
    echo "       Stop it yourself, or re-run with LUNICLE_PORT=<other port>." >&2
  fi
  exit 1
}

# start_dev_server FRAME_ANCESTORS — starts `:server:run` in the background and
# sets GRADLE_PID. The caller's cleanup trap is expected to stop_dev_server.
start_dev_server() {
  local frame_ancestors="$1"

  # A caller that forgot to source lib/env.sh would otherwise start a server
  # with no sign-in at all, which looks like a broken app rather than a missing
  # source line. Loud beats mysterious.
  if [[ -z "${LUNICLE_OAUTH_VARS:-}" ]]; then
    echo "error: start_dev_server needs lib/env.sh sourced first (LUNICLE_OAUTH_VARS is unset)." >&2
    exit 1
  fi

  # Built as plain repeated arguments rather than an array with a conditional
  # expansion: macOS still ships bash 3.2, where expanding an empty array under
  # `set -u` is a fatal "unbound variable". That broke standalone mode — and only
  # standalone mode, the one with no OAuth props — until it was flattened.
  local oauth_props=()
  local var value prop
  for var in $LUNICLE_OAUTH_VARS; do
    value="${!var:-}"
    [[ -n "$value" ]] || continue
    # GOOGLE_CLIENT_ID -> googleClientId, matching build.gradle.kts.
    prop="$(echo "$var" | awk -F_ '{
      out = tolower($1)
      for (i = 2; i <= NF; i++) out = out toupper(substr($i,1,1)) tolower(substr($i,2))
      print out
    }')"
    oauth_props+=("-P$prop=$value")
  done

  if [[ "${#oauth_props[@]}" -gt 0 ]]; then
    # Names only. A secret echoed here would end up in scrollback and in any CI
    # log that ever runs this.
    echo "==> OAuth credentials loaded from .env for: $(
      for p in "${oauth_props[@]}"; do echo "${p%%=*}" | sed 's/^-P//'; done | tr '\n' ' '
    )"
  fi

  # LUNICLE_LOCAL_DATA moves the database (and the attachments beside it) — the
  # same variable local-db.sh reads to decide what to inspect. Forwarded ONLY
  # when set, so the unset case stays the databasePath default in
  # server/build.gradle.kts rather than being decided twice in two files. Same
  # rule as -Pport.
  #
  # Until this existed, the variable was honoured by local-db.sh and by nothing
  # that actually ran a server: setting it meant inspecting one database while
  # the server wrote to another, with both commands reporting success.
  local db_prop=()
  if [[ -n "${LUNICLE_LOCAL_DATA:-}" ]]; then
    db_prop=("-PdatabasePath=$LUNICLE_LOCAL_DATA/lunicle.db")
    echo "==> Database: $LUNICLE_LOCAL_DATA/lunicle.db (LUNICLE_LOCAL_DATA)"
  fi

  "$LUNICLE_REPO_ROOT/gradlew" -p "$LUNICLE_REPO_ROOT" \
    "-PframeAncestors=$frame_ancestors" "-Pport=$LUNICLE_PORT" \
    ${db_prop[@]+"${db_prop[@]}"} \
    ${oauth_props[@]+"${oauth_props[@]}"} :server:run &
  GRADLE_PID=$!
}

# Block until the server answers. The first run compiles Kotlin/JS, so this can
# genuinely take a while — hence 180 tries, and hence saying so.
wait_for_dev_server() {
  echo "==> Waiting for the tracker to answer (first run compiles the JS bundle — this is slow)…"
  wait_for_http "$(lunicle_probe_url "$LUNICLE_PORT")" 180 1 "${GRADLE_PID:-}"
  case "$?" in
    0) return 0 ;;
    2)
      echo "error: the server exited before it answered; see the Gradle output above" >&2
      exit 1
      ;;
    *)
      echo "error: the tracker never answered on :$LUNICLE_PORT" >&2
      exit 1
      ;;
  esac
}

# Stop what we started, by tracked PID, plus the server JVM by marker.
#
# The children are killed by PID rather than with a `kill -- -$$` process-group
# sweep, because that sweep only works when the script is a process-group leader
# — which depends on how it was launched, and silently fails when it isn't.
#
# The server JVM needs the marker because it is not our child at all: `:server:run`
# is a JavaExec, so the Gradle *daemon* forks it. Killing the gradlew wrapper
# leaves the server running and holding the port — the orphan require_dev_port_free
# refuses to start on top of. Reaping it here is what keeps that check from ever
# needing to fire.
stop_dev_server() {
  if [[ -n "${GRADLE_PID:-}" ]]; then kill "$GRADLE_PID" 2>/dev/null || true; fi
  pkill -f "$DEV_SERVER_MARKER" 2>/dev/null || true
}
