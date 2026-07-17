#!/usr/bin/env bash
#
# Source (don't run) this to serve a local copy of the lunamux-web site around
# the tracker — the "embedded" half of both embed scripts.
#
#   source "$SCRIPT_DIR/lib/site.sh"
#
# run-dev-embedded.sh and run-container-embedded.sh differ in exactly one thing:
# what is serving the tracker on LUNICLE_PORT. Everything about the *site* — the
# checkout, the python server, the URL, the banner — is identical, and used to
# exist twice, comments and all. It exists here once.
#
# Env:
#   LUNAMUX_WEB   path to the lunamux-web checkout (default: ../lunamux-web)
#   SITE_PORT     port to serve it on (default: 8000)

# shellcheck source=probe.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/probe.sh"

LUNICLE_REPO_ROOT="${LUNICLE_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
SITE_PORT="${SITE_PORT:-8000}"
LUNAMUX_WEB="${LUNAMUX_WEB:-$LUNICLE_REPO_ROOT/../lunamux-web}"

SITE_URL="http://localhost:$SITE_PORT/?issues=1#/issues"

# Fail before starting anything, not after. Both embed scripts start a server
# first and would otherwise get all the way to a browser tab before noticing
# there is no site to serve.
require_site_checkout() {
  if [[ ! -f "$LUNAMUX_WEB/index.html" ]]; then
    echo "error: no lunamux-web checkout at $LUNAMUX_WEB" >&2
    echo "       set LUNAMUX_WEB=/path/to/lunamux-web" >&2
    exit 1
  fi
}

require_site_port_free() {
  if curl -sf -o /dev/null "http://localhost:$SITE_PORT/" 2>/dev/null; then
    echo "error: something is already serving http://localhost:$SITE_PORT/" >&2
    echo "       Stop it (./scripts/stop.sh), or re-run with SITE_PORT=<other port>." >&2
    exit 1
  fi
}

# Serves the checkout and sets SITE_PID. The caller's cleanup trap is expected
# to stop_site_server.
start_site_server() {
  echo "==> Serving lunamux-web from $LUNAMUX_WEB on :$SITE_PORT"
  # exec, so $SITE_PID is python itself rather than the subshell — killing a
  # subshell would leave python orphaned and still holding the port.
  (cd "$LUNAMUX_WEB" && exec python3 -m http.server "$SITE_PORT" > /dev/null 2>&1) &
  SITE_PID=$!

  # python's http.server binds ~immediately, but "immediately" is not "before
  # the next line runs" — a failed first paint here reads as a broken embed.
  wait_for_http "http://localhost:$SITE_PORT/" 20 0.25 "$SITE_PID" || true
}

stop_site_server() {
  if [[ -n "${SITE_PID:-}" ]]; then kill "$SITE_PID" 2>/dev/null || true; fi
}

# Confirm the tracker on LUNICLE_PORT will actually allow this site to frame it.
#
# Worth a whole check because of the shape of the failure: a tab that renders,
# an iframe that exists, and a blank rectangle explained only by a console
# message you have to know to go looking for. One clear sentence beforehand is
# worth a lot.
require_framing_allows_site() {
  local lunicle_port="$1" fix_hint="$2"
  local csp
  csp="$(curl -sI "http://localhost:$lunicle_port/" | tr -d '\r' \
    | grep -i '^content-security-policy:' || true)"
  case "$csp" in
    *"http://localhost:$SITE_PORT"*) ;;
    *)
      echo "error: the tracker on :$lunicle_port won't allow this site to frame it." >&2
      echo "       Its policy:  ${csp:-<no Content-Security-Policy header at all>}" >&2
      echo "       Expected it to permit http://localhost:$SITE_PORT." >&2
      echo "       Try:  $fix_hint" >&2
      exit 1
      ;;
  esac
}

print_embed_banner() {
  cat <<EOF

    ─────────────────────────────────────────────────────────────
      Embedded:  $SITE_URL

      The Issues tab appears only with ?issues=1 — that is the
      whole of the "secret". Drop the parameter and the tab is
      gone (the page itself stays routable; see content.js).
    ─────────────────────────────────────────────────────────────

EOF
}
