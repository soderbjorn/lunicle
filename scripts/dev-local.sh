#!/usr/bin/env bash
#
# Run the issue tracker locally — standalone, or embedded in a local copy of
# the real lunamux-web site. Neither mode deploys anything or touches Railway,
# Cloudflare, or the live site. Both open the right URL in your browser once the
# server is actually answering.
#
#   ./scripts/dev-local.sh              # standalone → http://localhost:8080/
#   ./scripts/dev-local.sh --embed      # embedded   → http://localhost:8000/?issues=1#/issues
#
# Flags/env:
#   --no-open      don't launch a browser
#   LUNAMUX_WEB    path to the lunamux-web checkout (default: ../lunamux-web)
#   ISSUES_PORT    tracker port (default: 8080)
#   SITE_PORT      local lunamux-web port (default: 8000)
#
# Why the embed mode works without a deploy:
#
#   * The tracker only permits framing from the origins named in its
#     Content-Security-Policy `frame-ancestors` (see Application.kt). That value
#     is read from $FRAME_ANCESTORS, so pointing it at the local site's origin
#     is all it takes for the browser to allow the frame. Same header and same
#     enforcement as production — only the value differs.
#
#   * lunamux-web frames http://localhost:8080/ instead of issues.lunamux.dev
#     when the site is itself served from localhost (SITE.issues.devIframeSrc
#     in content.js). That choice keys off the site's own hostname, never off
#     anything a visitor supplies.
#
# Caveat worth knowing: this proves the framing contract, the lazy-load, the nav
# gate and the round-trip. It does NOT prove the two things that only exist in
# production — real DNS and a real certificate for issues.lunamux.dev. Those
# fail in ways localhost cannot reproduce, which is why the Stage 1 exit
# criteria are written against the deployed site. See docs/instructions.html.
#
set -euo pipefail

ISSUES_PORT="${ISSUES_PORT:-8080}"
SITE_PORT="${SITE_PORT:-8000}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# The lunamux-web checkout to serve. Override if yours lives elsewhere.
LUNAMUX_WEB="${LUNAMUX_WEB:-$REPO_ROOT/../lunamux-web}"

mode="standalone"
open_browser=1
for arg in "$@"; do
  case "$arg" in
    --embed)   mode="embed" ;;
    --no-open) open_browser=0 ;;
    *) echo "usage: $0 [--embed] [--no-open]" >&2; exit 2 ;;
  esac
done

if [[ "$mode" == "embed" && ! -f "$LUNAMUX_WEB/index.html" ]]; then
  echo "error: no lunamux-web checkout at $LUNAMUX_WEB" >&2
  echo "       set LUNAMUX_WEB=/path/to/lunamux-web" >&2
  exit 1
fi

# The server JVM is identified by this marker: `:server:run` passes it, it
# contains this checkout's absolute path, and nothing else on the machine will
# match it. Used to reap orphans (see below) without ever touching an unrelated
# JVM or whatever else might hold the port.
server_marker="issues.webDist=$REPO_ROOT/web/build"

# Refuse to start on top of something already serving the port — do NOT just
# adopt it. `:server:run` forks the server from the Gradle *daemon*, so an
# earlier run's server can outlive its script and keep the port bound; a run
# that silently talked to that stale process would report whatever the OLD
# server was configured with (its framing policy, its counter, its code) while
# looking perfectly healthy. That is a genuinely misleading failure — it cost
# real time during this stage's development — so it is now loud.
if curl -sf -o /dev/null "http://localhost:$ISSUES_PORT/api/counter" 2>/dev/null; then
  echo "error: something is already serving http://localhost:$ISSUES_PORT/" >&2
  if pgrep -f "$server_marker" > /dev/null 2>&1; then
    echo "       It looks like an orphaned tracker from an earlier run. Stop it with:" >&2
    echo "         pkill -f '$server_marker'" >&2
  else
    echo "       Stop it, or re-run with ISSUES_PORT=<other port>." >&2
  fi
  exit 1
fi

# The framing origin is always passed explicitly, in both modes — standalone
# just passes the production value. Two reasons it is a Gradle property and not
# the FRAME_ANCESTORS environment variable:
#
#   * `:server:run` is a JavaExec, so it inherits the long-lived Gradle
#     *daemon's* environment rather than this shell's. A -P property is
#     per-invocation and cannot drift. (The deployed container does use the
#     environment variable — no daemon there. See resolveFrameAncestors() in
#     Application.kt.)
#   * Passing it unconditionally keeps this an ordinary argument rather than a
#     conditionally-empty array. macOS still ships bash 3.2, where expanding an
#     empty array under `set -u` is a fatal "unbound variable" — which broke
#     standalone mode (and only standalone mode) until this was flattened.
if [[ "$mode" == "embed" ]]; then
  frame_ancestors="http://localhost:$SITE_PORT"
  target_url="http://localhost:$SITE_PORT/?issues=1#/issues"
  echo "==> Starting the tracker on :$ISSUES_PORT (frame-ancestors: $frame_ancestors)"
else
  frame_ancestors="https://lunamux.dev"
  target_url="http://localhost:$ISSUES_PORT/"
  echo "==> Starting the tracker on :$ISSUES_PORT (frame-ancestors: $frame_ancestors — as in production)"
fi

"$REPO_ROOT/gradlew" -p "$REPO_ROOT" "-PframeAncestors=$frame_ancestors" :server:run &
gradle_pid=$!

# Stop everything we started, by explicit PID, plus the server JVM by marker.
#
# The children are killed by tracked PID rather than with a `kill -- -$$`
# process-group sweep, because that sweep only works when this script is a
# process-group leader — which depends on how it was launched, and silently
# fails (leaving python holding its port) when it isn't.
#
# The server JVM needs the marker because it is not our child at all:
# `:server:run` is a JavaExec, so the Gradle *daemon* forks it. Killing the
# gradlew wrapper leaves the server running and holding the port — the orphan
# the pre-flight check above refuses to start on top of. Reaping it here is what
# keeps that check from ever needing to fire.
cleaned=0
cleanup() {
  # The trap fires on INT/TERM *and* then again on EXIT; without this guard the
  # whole thing runs twice and reports "Stopping…" twice.
  if [[ "$cleaned" -eq 1 ]]; then return; fi
  cleaned=1
  echo
  echo "==> Stopping…"
  if [[ -n "${site_pid:-}" ]]; then kill "$site_pid" 2>/dev/null || true; fi
  if [[ -n "${gradle_pid:-}" ]]; then kill "$gradle_pid" 2>/dev/null || true; fi
  pkill -f "$server_marker" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# Wait for the server to actually answer before opening a browser — opening
# early just shows a connection error and teaches you nothing. The first run
# compiles Kotlin/JS, so this can genuinely take a while.
echo "==> Waiting for the tracker to answer (first run compiles the JS bundle — this is slow)…"
ready=0
for _ in $(seq 1 180); do
  if curl -sf -o /dev/null "http://localhost:$ISSUES_PORT/api/counter"; then ready=1; break; fi
  if ! kill -0 "$gradle_pid" 2>/dev/null; then
    echo "error: the server exited before it answered; see the Gradle output above" >&2
    exit 1
  fi
  sleep 1
done
if [[ "$ready" -ne 1 ]]; then
  echo "error: the tracker never answered on :$ISSUES_PORT" >&2
  exit 1
fi

if [[ "$mode" == "embed" ]]; then
  echo "==> Serving lunamux-web from $LUNAMUX_WEB on :$SITE_PORT"
  (cd "$LUNAMUX_WEB" && exec python3 -m http.server "$SITE_PORT" > /dev/null 2>&1) &
  # exec above so $site_pid is python itself, not a subshell that would leave
  # python orphaned when killed.
  site_pid=$!
  # python's http.server binds ~immediately, but "immediately" is not "before
  # the next line runs" — a failed first paint here reads as a broken embed.
  for _ in $(seq 1 20); do
    if curl -sf -o /dev/null "http://localhost:$SITE_PORT/"; then break; fi
    sleep .25
  done
fi

cat <<EOF

    ─────────────────────────────────────────────────────────────
      Open:  $target_url
EOF
if [[ "$mode" == "embed" ]]; then
cat <<EOF

      The Issues tab appears only with ?issues=1 — that is the
      whole of the "secret". Drop the parameter and the tab is
      gone (the page itself stays routable; see content.js).
EOF
fi
cat <<EOF
    ─────────────────────────────────────────────────────────────

EOF

if [[ "$open_browser" -eq 1 ]]; then
  if command -v open > /dev/null 2>&1; then
    echo "==> Opening your browser… (--no-open to skip)"
    open "$target_url"
  else
    echo "==> No 'open' command; visit the URL above yourself."
  fi
fi

echo "==> Ctrl-C to stop."
wait
