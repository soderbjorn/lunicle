#!/usr/bin/env bash
#
# Run the tracker from your WORKING TREE, EMBEDDED in a local copy of the real
# lunamux-web site, and open the Issues tab.
#
#   ./scripts/run-dev-embedded.sh              → http://localhost:8000/?issues=1#/issues
#   ./scripts/run-dev-embedded.sh --no-open    # don't launch a browser
#
# This is the one to reach for while writing code you want to see *in the site*:
# it runs what you have edited, with no image to rebuild. Ctrl-C stops both the
# tracker and the site.
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
#   * The tracker only permits framing from the origins named in its
#     Content-Security-Policy `frame-ancestors` (see Application.kt). Pointing
#     that at the local site's origin is all it takes for the browser to allow
#     the frame. Same header and same enforcement as production — only the value
#     differs. This is the single deliberate difference from the deployed
#     configuration.
#   * lunamux-web frames http://localhost:$LUNICLE_PORT/ instead of
#     lunicle.lunamux.dev when the site is itself served from localhost
#     (SITE.issues.devIframeSrc in content.js). That keys off the site's own
#     hostname, never off anything a visitor supplies.
#
# It proves the framing contract, the lazy-load, the nav gate and the round-trip.
# It does NOT prove the two things that only exist in production — real DNS and a
# real certificate for lunicle.lunamux.dev — which fail in ways localhost cannot
# reproduce. That is why the Stage 1 exit criteria are written against the
# deployed site. See docs/instructions.html.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# OAuth credentials from .env, if you have one. Silent no-op without.
# shellcheck source=lib/env.sh
source "$SCRIPT_DIR/lib/env.sh"
# shellcheck source=lib/dev-server.sh
source "$SCRIPT_DIR/lib/dev-server.sh"
# shellcheck source=lib/site.sh
source "$SCRIPT_DIR/lib/site.sh"
# shellcheck source=lib/browser.sh
source "$SCRIPT_DIR/lib/browser.sh"

open_browser=1
for arg in "$@"; do
  case "$arg" in
    --no-open) open_browser=0 ;;
    *) echo "usage: $0 [--no-open]" >&2; exit 2 ;;
  esac
done

# Every check before anything starts: there is no point compiling a JS bundle
# for two minutes only to discover there is no site to put it in.
#
# The site-port check is new to this mode (the container embed always had it).
# Without it, python fails to bind, the failure is swallowed by the >/dev/null
# it is started with, and the browser opens onto whatever was already on :8000 —
# an embed that renders, from a site that isn't yours.
require_site_checkout
require_site_port_free
require_dev_port_free

frame_ancestors="http://localhost:$SITE_PORT"

echo "==> Starting the tracker on :$LUNICLE_PORT (frame-ancestors: $frame_ancestors)"
start_dev_server "$frame_ancestors"

cleaned=0
cleanup() {
  # The trap fires on INT/TERM *and* then again on EXIT; without this guard the
  # whole thing runs twice and reports "Stopping…" twice.
  if [[ "$cleaned" -eq 1 ]]; then return; fi
  cleaned=1
  echo
  echo "==> Stopping…"
  stop_site_server
  stop_dev_server
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

wait_for_dev_server
start_site_server
print_embed_banner
maybe_open_url "$SITE_URL" "$open_browser"

echo "==> Ctrl-C to stop."
wait
