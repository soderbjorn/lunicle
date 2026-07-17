#!/usr/bin/env bash
#
# Run the tracker as the DOCKER CONTAINER, EMBEDDED in a local copy of the real
# lunamux-web site, and open the Issues tab. This is the one that shows the
# thing Stage 1 exists to prove, running the image the deploy will run.
#
#   ./scripts/run-container-embedded.sh              → http://localhost:8000/?issues=1#/issues
#   ./scripts/run-container-embedded.sh --no-build   # skip the image rebuild
#   ./scripts/run-container-embedded.sh --no-open    # don't launch a browser
#
# It runs the image you last BUILT, not your working tree; for the tree, use
# ./scripts/run-dev-embedded.sh.
#
# Ctrl-C stops the site; the CONTAINER keeps running (./scripts/container-down.sh,
# or ./scripts/stop.sh for everything).
#
# Don't confuse the two URLs:
#   :8000/?issues=1#/issues   the SITE, tracker inside its Issues tab ← this
#   :8080/                    the tracker STANDALONE, no site, no tab
#
# Env:
#   LUNICLE_PORT   the tracker's port (default: 8080)
#   LUNAMUX_WEB    path to the lunamux-web checkout (default: ../lunamux-web)
#   SITE_PORT      port to serve the site on (default: 8000)
#
# ── Why this works without deploying anything ────────────────────────────────
#
#   * The container is started allowing http://localhost:$SITE_PORT to frame it
#     (plus lunamux.dev). Same Content-Security-Policy header and same browser
#     enforcement as production — only the value differs. This is the single
#     deliberate difference from the deployed configuration.
#   * lunamux-web frames http://localhost:$LUNICLE_PORT/ instead of
#     lunicle.lunamux.dev when the site is itself served from localhost (see
#     SITE.issues.devIframeSrc in content.js). That keys off the site's own
#     hostname, never off anything a visitor supplies.
#
# It proves the framing contract, the lazy-load, the nav gate and the round-trip.
# It does NOT prove DNS or the certificate — localhost has neither. See
# docs/instructions.html.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/site.sh
source "$SCRIPT_DIR/lib/site.sh"
# shellcheck source=lib/browser.sh
source "$SCRIPT_DIR/lib/browser.sh"

LUNICLE_PORT="${LUNICLE_PORT:-8080}"

build_flag=""
open_browser=1
for arg in "$@"; do
  case "$arg" in
    --no-build) build_flag="--no-build" ;;
    --no-open)  open_browser=0 ;;
    *) echo "usage: $0 [--no-build] [--no-open]" >&2; exit 2 ;;
  esac
done

require_site_checkout
require_site_port_free

# Allow the local site to frame the tracker. frame-ancestors takes a
# space-separated source list, so lunamux.dev stays permitted too.
# container-up.sh replaces any running container, so this policy always applies
# even if the standalone script started a production-only one earlier.
export FRAME_ANCESTORS="http://localhost:$SITE_PORT https://lunamux.dev"

# OAuth credentials from .env, if present, for container-up.sh to forward into
# the container. Sourced this late so it can't affect the checks above.
# shellcheck source=lib/env.sh
source "$SCRIPT_DIR/lib/env.sh"

if [[ -n "$build_flag" ]]; then
  "$SCRIPT_DIR/container-up.sh" "$build_flag"
else
  "$SCRIPT_DIR/container-up.sh"
fi

# Belt and braces: confirm the container we just started really will allow this
# origin to frame it.
require_framing_allows_site "$LUNICLE_PORT" \
  "./scripts/container-down.sh && ./scripts/run-container-embedded.sh"

start_site_server

cleaned=0
cleanup() {
  # The trap fires on INT/TERM and then again on EXIT; without this guard it all
  # runs twice.
  if [[ "$cleaned" -eq 1 ]]; then return; fi
  cleaned=1
  echo
  echo "==> Stopping the site (the container keeps running — ./scripts/container-down.sh)"
  stop_site_server
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

print_embed_banner
maybe_open_url "$SITE_URL" "$open_browser"

echo "==> Ctrl-C to stop the site."
wait
