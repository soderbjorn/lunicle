#!/usr/bin/env bash
#
# Build and run Lunicle as a Docker container, locally — the same
# image Railway will build and run, with the same entrypoint. Nothing is
# deployed and nothing outside Docker is touched.
#
#   ./scripts/container-up.sh              # build + run  → http://localhost:8080/
#   ./scripts/container-up.sh --no-build   # run the existing image, skip the build
#
# Container lifecycle only: it starts the tracker and nothing else. No website,
# no browser. You normally don't need to call this directly — reach for the
# script that does call it:
#
#   ./scripts/run-container.sh   # build the image and run the tracker
#
# To stop: ./scripts/container-down.sh
#
# Env:
#   LUNICLE_PORT           host port to publish (default: 8080)
#   SITE_PORT              the local site's port, used for the framing policy below
#                          (default: 8000)
#   ALLOWED_FRAME_ANCESTORS  override the framing policy outright
#
# Framing: ALLOWED_FRAME_ANCESTORS can be overridden by the caller or the
# environment. Left unset, the default below permits the local site
# (localhost:SITE_PORT) and lunamux.dev, so a container framed in either the
# local or the real site works. `frame-ancestors` takes a space-separated source
# list. It reaches the container as LUNICLE_ALLOWED_FRAME_ANCESTORS; a deployment
# that sets nothing gets the server's own 'self'-only framing.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/probe.sh
source "$SCRIPT_DIR/lib/probe.sh"
# For LUNICLE_OAUTH_VARS, and so that calling this script directly still picks
# up .env rather than silently serving with no sign-in.
# shellcheck source=lib/env.sh
source "$SCRIPT_DIR/lib/env.sh"

IMAGE="lunicle:local"
NAME="lunicle-local"
# A named Docker volume, standing in for Railway's. Named rather than a bind
# mount on purpose: a Docker volume comes up root-owned, exactly as Railway's
# does, so this local run exercises the entrypoint's chown-and-drop for real. A
# bind mount would inherit the host directory's ownership and quietly work,
# proving nothing about the deploy.
VOLUME="${LUNICLE_VOLUME:-lunicle-data}"
LUNICLE_PORT="${LUNICLE_PORT:-8080}"
SITE_PORT="${SITE_PORT:-8000}"
ALLOWED_FRAME_ANCESTORS="${ALLOWED_FRAME_ANCESTORS:-http://localhost:$SITE_PORT https://lunamux.dev}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

do_build=1
for arg in "$@"; do
  case "$arg" in
    --no-build) do_build=0 ;;
    *) echo "usage: $0 [--no-build]" >&2; exit 2 ;;
  esac
done

# ---- docker daemon ----
if ! docker info > /dev/null 2>&1; then
  if command -v open > /dev/null 2>&1; then
    echo "==> Docker isn't running. Starting Docker Desktop…"
    open -a Docker || true
    for _ in $(seq 1 60); do
      if docker info > /dev/null 2>&1; then break; fi
      sleep 2
    done
  fi
  if ! docker info > /dev/null 2>&1; then
    echo "error: the Docker daemon isn't running (and couldn't be started)." >&2
    echo "       Start Docker Desktop, then re-run this." >&2
    exit 1
  fi
fi

# ---- port ----
# Refuse if something that ISN'T our container holds the port — most likely a
# run-dev-*.sh server still running. Publishing would fail anyway, but with a
# Docker error that buries the actual cause. (port_is_held only counts
# listeners; see lib/probe.sh for why that distinction matters here.)
if port_is_held "$LUNICLE_PORT"; then
  if [[ -z "$(docker ps -q --filter "name=^${NAME}$")" ]]; then
    echo "error: something is already using port $LUNICLE_PORT, and it isn't this container." >&2
    echo "       It's: $(port_holder "$LUNICLE_PORT")" >&2
    echo "       A run-dev-*.sh server, perhaps? Stop it with:  ./scripts/stop-all.sh" >&2
    echo "       …or re-run with LUNICLE_PORT=<other port>." >&2
    exit 1
  fi
fi

# ---- build ----
if [[ "$do_build" -eq 1 ]]; then
  echo "==> Building $IMAGE"
  echo "    (First build compiles Kotlin/JVM *and* Kotlin/JS and downloads a Node"
  echo "     toolchain inside the image — several minutes is normal, not a hang."
  echo "     Later builds reuse cached layers and are quick. --no-build to skip.)"
  docker build -t "$IMAGE" "$REPO_ROOT"
elif ! docker image inspect "$IMAGE" > /dev/null 2>&1; then
  echo "error: --no-build was passed but the image $IMAGE doesn't exist yet." >&2
  echo "       Run once without --no-build." >&2
  exit 1
fi

# ---- run ----
# Replace any previous instance, so this is idempotent: re-running after a code
# change gives you the new build rather than an error.
if [[ -n "$(docker ps -aq --filter "name=^${NAME}$")" ]]; then
  echo "==> Replacing the existing '$NAME' container"
  docker rm -f "$NAME" > /dev/null
fi

# OAuth credentials, forwarded only when the caller has them in scope (a run
# script that sourced .env). Unlike the Gradle path, the environment is the
# right mechanism here and needs no -P translation: a container is a plain
# `java -jar` with no daemon to inherit a stale environment from, which is
# exactly how Railway supplies these in production. Passing them this way means
# the local container exercises the real mechanism rather than a stand-in.
#
# Absent is normal: the container then serves with no sign-in. The provider list
# is shared with the Gradle path — see LUNICLE_OAUTH_VARS in lib/env.sh.
oauth_env=()
for var in $LUNICLE_OAUTH_VARS; do
  value="${!var:-}"
  [[ -n "$value" ]] || continue
  oauth_env+=(-e "$var=$value")
done

echo "==> Starting the container"
echo "    frame-ancestors: $ALLOWED_FRAME_ANCESTORS"
echo "    volume: $VOLUME → /data (survives --no-build restarts; wipe with container-down.sh --wipe)"
if [[ "${#oauth_env[@]}" -gt 0 ]]; then
  # Names only. A secret echoed here would end up in scrollback and in any CI
  # log that ever runs this.
  echo "    oauth: $(printf '%s\n' "${oauth_env[@]}" | grep -v '^-e$' | cut -d= -f1 | tr '\n' ' ')"
fi
# PORT is how Railway tells the server where to listen, so pass it the same way
# here: this exercises the real mechanism rather than the 8080 fallback.
#
# RAILWAY_VOLUME_MOUNT_PATH is passed for exactly the same reason. Railway
# injects it into the container whenever a volume is attached, and it is what
# the server resolves its database path from (see resolveDatabaseLocation()).
# Setting it here means the local container puts its database on the mounted
# volume by the same mechanism the deploy does, rather than via a local-only
# override that would leave the real branch untested. Without it the server
# would fall back to /app/lunicle.db — inside the container, next to the jar,
# quietly missing the volume mounted an inch away.
docker run -d --name "$NAME" \
  -e PORT="$LUNICLE_PORT" \
  -e RAILWAY_VOLUME_MOUNT_PATH=/data \
  -e LUNICLE_ALLOWED_FRAME_ANCESTORS="$ALLOWED_FRAME_ANCESTORS" \
  ${oauth_env[@]+"${oauth_env[@]}"} \
  -v "$VOLUME:/data" \
  -p "$LUNICLE_PORT:$LUNICLE_PORT" \
  "$IMAGE" > /dev/null

echo "==> Waiting for it to answer…"
# No guard PID here: the container isn't our child, so "did it die?" is a docker
# question rather than a kill -0 one. Poll in short waits and ask docker between
# them — a container that exits on startup is the common failure, and its logs
# are the whole answer.
ready=0
for _ in $(seq 1 60); do
  if wait_for_http "$(lunicle_probe_url "$LUNICLE_PORT")" 1 1; then ready=1; break; fi
  if [[ -z "$(docker ps -q --filter "name=^${NAME}$")" ]]; then
    echo "error: the container exited. Its logs:" >&2
    docker logs "$NAME" 2>&1 | tail -20 >&2
    exit 1
  fi
done
if [[ "$ready" -ne 1 ]]; then
  echo "error: the container never answered on port $LUNICLE_PORT. Its logs:" >&2
  docker logs "$NAME" 2>&1 | tail -20 >&2
  exit 1
fi

cat <<EOF
==> Container up on http://localhost:$LUNICLE_PORT/ (logs: docker logs -f $NAME)
EOF
