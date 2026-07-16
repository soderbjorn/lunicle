#!/usr/bin/env bash
#
# Run the tracker STANDALONE and open it: just the app, no website around it.
#
#   ./scripts/run-standalone.sh
#   ./scripts/run-standalone.sh --no-build   # skip the image rebuild
#   ./scripts/run-standalone.sh --no-open    # don't launch a browser
#
#   → http://localhost:8080/
#
# There is no Issues tab and no site chrome here — this is the tracker on its
# own. For the tracker inside the lunamux website, with the tab:
#
#   ./scripts/run-embedded.sh
#
# Framing matches production exactly (only https://lunamux.dev may embed it), so
# this mode can never prove something the deployed site wouldn't allow.
#
# The container keeps running after this script exits; stop it with
# ./scripts/container-down.sh.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LUNICLE_PORT="${LUNICLE_PORT:-8080}"

# Plain strings, not an array: macOS still ships bash 3.2, where expanding an
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

# Production's framing policy: no FRAME_ANCESTORS override at all, so the server
# falls back to https://lunamux.dev on its own. container-up.sh replaces any
# running container, so this always gets the policy this mode intends — even if
# run-embedded.sh started a more permissive one earlier.
export FRAME_ANCESTORS="https://lunamux.dev"

if [[ -n "$build_flag" ]]; then
  "$SCRIPT_DIR/container-up.sh" "$build_flag"
else
  "$SCRIPT_DIR/container-up.sh"
fi

target_url="http://localhost:$LUNICLE_PORT/"

cat <<EOF

    ─────────────────────────────────────────────────────────────
      Standalone:  $target_url

      This is the tracker on its own — no site, so no Issues tab.
      For the embed:  ./scripts/run-embedded.sh
      Stop:           ./scripts/container-down.sh
    ─────────────────────────────────────────────────────────────

EOF

if [[ "$open_browser" -eq 1 ]] && command -v open > /dev/null 2>&1; then
  open "$target_url"
fi
