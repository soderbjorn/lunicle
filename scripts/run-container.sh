#!/usr/bin/env bash
#
# Run the tracker as the DOCKER CONTAINER, on its own — no website around it.
#
#   ./scripts/run-container.sh              → http://localhost:8080/
#   ./scripts/run-container.sh --no-build   # skip the image rebuild
#   ./scripts/run-container.sh --no-open    # don't launch a browser
#
# This runs the same image Railway builds and runs, with the same entrypoint —
# so it exercises the real deploy mechanisms (PORT, the mounted volume, the
# environment). It runs the image you last BUILT, not your working tree; for the
# tree, use ./scripts/run-dev.sh.
#
# There is no Issues tab and no site chrome here — this is the tracker alone.
# To see it inside the lunamux site: run preview-lunamux-embedded.sh in the lunamux-web repo.
#
# Framing matches production exactly (only https://lunamux.dev may embed it), so
# this mode can never prove something the deployed site wouldn't allow.
#
# The container keeps running after this script exits — that is the point of it,
# and what makes "down, up, and the data is still there" testable. Stop it with
# ./scripts/container-down.sh (or ./scripts/stop-all.sh).
#
# Env:
#   LUNICLE_PORT   the tracker's port (default: 8080)
#
# See the repo's README.md ("The scripts/ directory") for how this relates to
# the other run scripts.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# OAuth credentials from .env, so container-up.sh can forward them into the
# container. Silent no-op without a .env.
#
# The embed script has always done this and this one didn't, which was harmless
# right up until the payload needed an owner: standalone mode would be a
# permanently inert app with no way to sign in — dead, for a reason nothing on
# screen explains.
# shellcheck source=lib/env.sh
source "$SCRIPT_DIR/lib/env.sh"
# shellcheck source=lib/browser.sh
source "$SCRIPT_DIR/lib/browser.sh"

LUNICLE_PORT="${LUNICLE_PORT:-8080}"

# Plain string, not an array: macOS still ships bash 3.2, where expanding an
# empty array under `set -u` is a fatal error.
build_flag=""
open_browser=1
for arg in "$@"; do
  case "$arg" in
    --no-build) build_flag="--no-build" ;;
    --no-open)  open_browser=0 ;;
    *) echo "usage: $0 [--no-build] [--no-open]" >&2; exit 2 ;;
  esac
done

# A standalone framing policy. container-up.sh replaces any running container,
# so this always gets the policy this mode intends — even if the embed script
# started a more permissive one earlier.
export ALLOWED_FRAME_ANCESTORS="https://lunamux.dev"

if [[ -n "$build_flag" ]]; then
  "$SCRIPT_DIR/container-up.sh" "$build_flag"
else
  "$SCRIPT_DIR/container-up.sh"
fi

target_url="http://localhost:$LUNICLE_PORT/"

cat <<EOF

    ─────────────────────────────────────────────────────────────
      Standalone:  $target_url

      The image you last built, no site, so no Issues tab.
      For the embed:  preview-lunamux-embedded.sh (in the lunamux-web repo)
      Stop:           ./scripts/container-down.sh
    ─────────────────────────────────────────────────────────────

EOF

maybe_open_url "$target_url" "$open_browser"
