#!/usr/bin/env bash
#
# Run the tracker EMBEDDED in the real lunamux-web site and open the Issues tab.
# This is the one that shows the thing Stage 1 exists to prove.
#
#   ./scripts/run-embedded.sh
#   ./scripts/run-embedded.sh --no-build   # skip the image rebuild
#   ./scripts/run-embedded.sh --no-open    # don't launch a browser
#
#   → http://localhost:8000/?issues=1#/issues
#
# It starts the container, serves your lunamux-web checkout around it, and opens
# the tab. Ctrl-C stops the site; the container keeps running (stop it with
# ./scripts/container-down.sh).
#
# Don't confuse the two URLs:
#   :8000/?issues=1#/issues   the SITE, tracker inside its Issues tab ← this
#   :8080/                    the tracker STANDALONE, no site, no tab
#
# Env:
#   LUNAMUX_WEB   path to the lunamux-web checkout (default: ../lunamux-web)
#   SITE_PORT     port to serve the site on (default: 8000)
#   LUNICLE_PORT   port for the tracker (default: 8080)
#
# Two things make this work without deploying anything:
#
#   * The container is started allowing http://localhost:$SITE_PORT to frame it
#     (plus lunamux.dev). Same Content-Security-Policy header and same browser
#     enforcement as production — only the value differs. This is the single
#     deliberate difference from the deployed configuration.
#   * lunamux-web frames http://localhost:$LUNICLE_PORT/ instead of
#     issues.lunamux.dev when the site is itself served from localhost (see
#     SITE.issues.devIframeSrc in content.js). That keys off the site's own
#     hostname, never off anything a visitor supplies.
#
# It proves the framing contract, the lazy-load, the nav gate and the
# round-trip. It does NOT prove DNS or the certificate — localhost has neither.
# See docs/instructions.html.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SITE_PORT="${SITE_PORT:-8000}"
LUNICLE_PORT="${LUNICLE_PORT:-8080}"
LUNAMUX_WEB="${LUNAMUX_WEB:-$REPO_ROOT/../lunamux-web}"

build_flag=""
open_browser=1
for arg in "$@"; do
  case "$arg" in
    --no-build) build_flag="--no-build" ;;
    --no-open)  open_browser=0 ;;
    *) echo "usage: $0 [--no-build] [--no-open]" >&2; exit 2 ;;
  esac
done

if [[ ! -f "$LUNAMUX_WEB/index.html" ]]; then
  echo "error: no lunamux-web checkout at $LUNAMUX_WEB" >&2
  echo "       set LUNAMUX_WEB=/path/to/lunamux-web" >&2
  exit 1
fi

if curl -sf -o /dev/null "http://localhost:$SITE_PORT/" 2>/dev/null; then
  echo "error: something is already serving http://localhost:$SITE_PORT/" >&2
  echo "       Stop it, or re-run with SITE_PORT=<other port>." >&2
  exit 1
fi

# Allow the local site to frame the tracker. frame-ancestors takes a
# space-separated source list, so lunamux.dev stays permitted too.
# container-up.sh replaces any running container, so this policy always applies
# even if run-standalone.sh started a production-only one earlier.
export FRAME_ANCESTORS="http://localhost:$SITE_PORT https://lunamux.dev"

if [[ -n "$build_flag" ]]; then
  "$SCRIPT_DIR/container-up.sh" "$build_flag"
else
  "$SCRIPT_DIR/container-up.sh"
fi

# Belt and braces: confirm the running container really will allow this origin
# to frame it. If it won't, the failure is a tab that renders, an iframe that
# exists, and a blank rectangle explained only by a console message — the most
# confusing shape this can take. One clear sentence beforehand is worth a lot.
csp="$(curl -sI "http://localhost:$LUNICLE_PORT/" | tr -d '\r' \
  | grep -i '^content-security-policy:' || true)"
case "$csp" in
  *"http://localhost:$SITE_PORT"*)
    ;;
  *)
    echo "error: the tracker on :$LUNICLE_PORT won't allow this site to frame it." >&2
    echo "       Its policy:  ${csp:-<no Content-Security-Policy header at all>}" >&2
    echo "       Expected it to permit http://localhost:$SITE_PORT." >&2
    echo "       Try:  ./scripts/container-down.sh && ./scripts/run-embedded.sh" >&2
    exit 1
    ;;
esac

echo "==> Serving lunamux-web from $LUNAMUX_WEB on :$SITE_PORT"
# exec, so $site_pid is python itself rather than a subshell that would leave
# python orphaned holding the port when killed.
(cd "$LUNAMUX_WEB" && exec python3 -m http.server "$SITE_PORT" > /dev/null 2>&1) &
site_pid=$!

cleaned=0
cleanup() {
  # The trap fires on INT/TERM and then again on EXIT; without this guard it all
  # runs twice.
  if [[ "$cleaned" -eq 1 ]]; then return; fi
  cleaned=1
  echo
  echo "==> Stopping the site (the container keeps running — ./scripts/container-down.sh)"
  if [[ -n "${site_pid:-}" ]]; then kill "$site_pid" 2>/dev/null || true; fi
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

for _ in $(seq 1 20); do
  if curl -sf -o /dev/null "http://localhost:$SITE_PORT/"; then break; fi
  sleep .25
done

target_url="http://localhost:$SITE_PORT/?issues=1#/issues"

cat <<EOF

    ─────────────────────────────────────────────────────────────
      Embedded:  $target_url

      The Issues tab appears only with ?issues=1 — that is the
      whole of the "secret". Drop the parameter and the tab is
      gone (the page itself stays routable; see content.js).
    ─────────────────────────────────────────────────────────────

EOF

if [[ "$open_browser" -eq 1 ]] && command -v open > /dev/null 2>&1; then
  echo "==> Opening your browser… (--no-open to skip)"
  open "$target_url"
fi

echo "==> Ctrl-C to stop the site."
wait
