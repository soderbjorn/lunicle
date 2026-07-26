#!/usr/bin/env bash
#
# Stop and remove the local Lunicle container started by
# ./scripts/container-up.sh. Safe to run when nothing is up.
#
#   ./scripts/container-down.sh           # stop the container, KEEP the database
#   ./scripts/container-down.sh --wipe    # …and delete the database volume too
#
# The database volume deliberately survives an ordinary `down`. That is the
# whole point of it: down, up, and your count is still there is the local
# rehearsal of the production exit criterion (redeploy, sign back in, count
# intact). If `down` wiped the volume, the one thing this stage exists to prove
# would be untestable without a deploy.
#
# --wipe is for the other half of that: proving the *fresh* path still works.
# Creating a schema on an empty volume is a code path production runs exactly
# once, on the very first deploy, where getting it wrong is most expensive and
# least observable. This is how you run it as often as you like.
#
set -euo pipefail

NAME="lunicle-local"
VOLUME="${LUNICLE_VOLUME:-lunicle-data}"

wipe=0
for arg in "$@"; do
  case "$arg" in
    --wipe) wipe=1 ;;
    *) echo "usage: $0 [--wipe]" >&2; exit 2 ;;
  esac
done

if ! docker info > /dev/null 2>&1; then
  # Nothing can be running if the daemon isn't. Not an error — "down" is the
  # state you asked for and the state you're in.
  echo "==> Docker isn't running, so neither is the container. Nothing to do."
  exit 0
fi

if [[ -n "$(docker ps -aq --filter "name=^${NAME}$")" ]]; then
  echo "==> Stopping and removing '$NAME'"
  docker rm -f "$NAME" > /dev/null
else
  echo "==> No '$NAME' container."
fi

if [[ "$wipe" -eq 1 ]]; then
  # After the container is gone, not before: Docker refuses to remove a volume
  # still attached to a container, even a stopped one.
  if [[ -n "$(docker volume ls -q --filter "name=^${VOLUME}$")" ]]; then
    echo "==> Deleting the database volume '$VOLUME' — every local account and count goes with it"
    docker volume rm "$VOLUME" > /dev/null
  else
    echo "==> No '$VOLUME' volume."
  fi
else
  if [[ -n "$(docker volume ls -q --filter "name=^${VOLUME}$")" ]]; then
    echo "==> Keeping the database volume '$VOLUME' (--wipe to delete it)"
  fi
fi

echo "==> Down."
