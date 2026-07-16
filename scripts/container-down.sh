#!/usr/bin/env bash
#
# Stop and remove the local issue-tracker container started by
# ./scripts/container-up.sh. Safe to run when nothing is up.
#
#   ./scripts/container-down.sh
#
set -euo pipefail

NAME="lunamux-issues-local"

if ! docker info > /dev/null 2>&1; then
  # Nothing can be running if the daemon isn't. Not an error — "down" is the
  # state you asked for and the state you're in.
  echo "==> Docker isn't running, so neither is the container. Nothing to do."
  exit 0
fi

if [[ -z "$(docker ps -aq --filter "name=^${NAME}$")" ]]; then
  echo "==> No '$NAME' container. Nothing to do."
  exit 0
fi

echo "==> Stopping and removing '$NAME'"
docker rm -f "$NAME" > /dev/null
echo "==> Down."
