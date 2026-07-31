#!/usr/bin/env bash
#
# Run the tracker from your WORKING TREE, on its own — no website around it.
#
#   ./scripts/run-dev.sh              → http://localhost:8080/
#   ./scripts/run-dev.sh --no-open    # don't launch a browser
#
# This is the one to reach for while writing code: it runs what you have edited,
# with no image to rebuild. Ctrl-C stops it.
#
# There is no Issues tab and no site chrome here — this is the tracker alone.
# To see it inside the lunamux site: run preview-lunamux-embedded.sh in the lunamux-web repo.
#
# Framing matches production exactly (only https://lunamux.dev may embed it), so
# this mode can never prove something the deployed site wouldn't allow.
#
# Env:
#   LUNICLE_PORT        the tracker's port (default: 8080)
#   LUNICLE_LOCAL_DATA  where to keep the database and attachments
#   LUNICLE_BRAND_DIR   a brand directory to apply (default: unbranded)
#
# LUNICLE_BRAND_DIR is the same variable the deployed container sets and
# run-demo.sh already read. It matters for more than the look now: brand.json is
# where a deployment names its staff domain, so the Staff rung cannot be
# exercised without one.
#
# See the repo's README.md ("The scripts/ directory") for how this relates to
# the other run scripts.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# OAuth credentials from .env, if you have one. Silent no-op without.
# shellcheck source=lib/env.sh
source "$SCRIPT_DIR/lib/env.sh"
# shellcheck source=lib/dev-server.sh
source "$SCRIPT_DIR/lib/dev-server.sh"
# shellcheck source=lib/browser.sh
source "$SCRIPT_DIR/lib/browser.sh"

open_browser=1
for arg in "$@"; do
  case "$arg" in
    --no-open) open_browser=0 ;;
    *) echo "usage: $0 [--no-open]" >&2; exit 2 ;;
  esac
done

require_dev_port_free

# Production's framing policy, passed explicitly rather than left to the
# server's own fallback: this mode's whole claim is that it frames exactly like
# production, and a claim worth making is worth making visible.
frame_ancestors="https://lunamux.dev"
target_url="http://localhost:$LUNICLE_PORT/"

echo "==> Starting the tracker on :$LUNICLE_PORT (frame-ancestors: $frame_ancestors — as in production)"
start_dev_server "$frame_ancestors"

cleaned=0
cleanup() {
  # The trap fires on INT/TERM *and* then again on EXIT; without this guard the
  # whole thing runs twice and reports "Stopping…" twice.
  if [[ "$cleaned" -eq 1 ]]; then return; fi
  cleaned=1
  echo
  echo "==> Stopping…"
  stop_dev_server
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# Wait for it to actually answer before opening a browser — opening early just
# shows a connection error and teaches you nothing.
wait_for_dev_server

cat <<EOF

    ─────────────────────────────────────────────────────────────
      Standalone:  $target_url

      Your working tree, no site, so no Issues tab.
      For the embed:  preview-lunamux-embedded.sh (in the lunamux-web repo)
    ─────────────────────────────────────────────────────────────

EOF

maybe_open_url "$target_url" "$open_browser"

echo "==> Ctrl-C to stop."
wait
