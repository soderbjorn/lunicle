#!/usr/bin/env bash
#
# Run Lunicle from your working tree, EMBEDDED in a local copy of lunamux-web.
# A one-line convenience for:
#
#   ./scripts/dev-local.sh --embed
#
#   → http://localhost:8000/?issues=1#/issues
#
# Every flag and every environment variable belongs to dev-local.sh and is
# passed straight through, so:
#
#   ./scripts/dev-local-embedded.sh --no-open
#   LUNAMUX_WEB=/path/to/lunamux-web ./scripts/dev-local-embedded.sh
#
# both do what you'd expect. Read dev-local.sh for what the flags mean, what
# embed mode does and doesn't prove, and why the framing origin is a Gradle
# property rather than an environment variable.
#
# Not to be confused with run-embedded.sh, which is the same embed but around
# the Docker CONTAINER — that runs the image you last built, this runs your
# working tree.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# exec, not a call-and-return: this process is replaced by dev-local.sh, so its
# Ctrl-C handling, its cleanup trap and its exit status are yours directly.
# A wrapper that merely forked it would sit between your terminal and that trap.
exec "$SCRIPT_DIR/dev-local.sh" --embed "$@"
