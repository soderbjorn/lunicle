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
# no browser. You normally don't need to call this directly — the two scripts
# that do call it are the ones to reach for:
#
#   ./scripts/run-standalone.sh            # the tracker on its own
#   ./scripts/run-embedded.sh              # the tracker inside the lunamux site
#
# To stop: ./scripts/container-down.sh
#
# Env:
#   LUNICLE_PORT       host port to publish (default: 8080)
#   SITE_PORT         the local site's port, used for the framing policy below
#                     (default: 8000)
#   FRAME_ANCESTORS   override the framing policy outright
#
# Framing: FRAME_ANCESTORS is normally set by whichever run script called this
# — run-standalone.sh passes production's policy, run-embedded.sh additionally
# permits the local site. Called directly with nothing set, the default below
# permits both, so the embed works either way. `frame-ancestors` takes a
# space-separated source list. Production sets no such variable at all and falls
# back to lunamux.dev alone.
#
set -euo pipefail

IMAGE="lunicle:local"
NAME="lunicle-local"
LUNICLE_PORT="${LUNICLE_PORT:-8080}"
SITE_PORT="${SITE_PORT:-8000}"
FRAME_ANCESTORS="${FRAME_ANCESTORS:-http://localhost:$SITE_PORT https://lunamux.dev}"
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
# `./scripts/dev-local.sh` server still running. Publishing would fail anyway,
# but with a Docker error that buries the actual cause.
#
# -sTCP:LISTEN matters: a bare `lsof -ti :PORT` also matches CLIENTS with a
# connection to that port, so an open browser tab pointing at localhost:8080 —
# or Docker's own backend proxy — counted as "the port is taken" and this
# refused to start for no reason. Only a listener actually holds the port.
if lsof -ti ":$LUNICLE_PORT" -sTCP:LISTEN > /dev/null 2>&1; then
  if [[ -z "$(docker ps -q --filter "name=^${NAME}$")" ]]; then
    echo "error: something is already using port $LUNICLE_PORT, and it isn't this container." >&2
    echo "       A dev-local.sh server, perhaps? Stop it with:" >&2
    echo "         pkill -f 'lunicle.webDist=$REPO_ROOT/web/build'" >&2
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
# Absent is normal: the container then serves with no sign-in. Names must match
# resolveOAuthConfig() in OAuthConfig.kt.
oauth_env=()
for var in GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET GITHUB_CLIENT_ID GITHUB_CLIENT_SECRET; do
  value="${!var:-}"
  [[ -n "$value" ]] || continue
  oauth_env+=(-e "$var=$value")
done

echo "==> Starting the container"
echo "    frame-ancestors: $FRAME_ANCESTORS"
if [[ "${#oauth_env[@]}" -gt 0 ]]; then
  # Names only. A secret echoed here would end up in scrollback and in any CI
  # log that ever runs this.
  echo "    oauth: $(printf '%s\n' "${oauth_env[@]}" | grep -v '^-e$' | cut -d= -f1 | tr '\n' ' ')"
fi
# PORT is how Railway tells the server where to listen, so pass it the same way
# here: this exercises the real mechanism rather than the 8080 fallback.
docker run -d --name "$NAME" \
  -e PORT="$LUNICLE_PORT" \
  -e FRAME_ANCESTORS="$FRAME_ANCESTORS" \
  ${oauth_env[@]+"${oauth_env[@]}"} \
  -p "$LUNICLE_PORT:$LUNICLE_PORT" \
  "$IMAGE" > /dev/null

echo "==> Waiting for it to answer…"
ready=0
for _ in $(seq 1 60); do
  if curl -sf -o /dev/null "http://localhost:$LUNICLE_PORT/api/counter"; then ready=1; break; fi
  if [[ -z "$(docker ps -q --filter "name=^${NAME}$")" ]]; then
    echo "error: the container exited. Its logs:" >&2
    docker logs "$NAME" 2>&1 | tail -20 >&2
    exit 1
  fi
  sleep 1
done
if [[ "$ready" -ne 1 ]]; then
  echo "error: the container never answered on port $LUNICLE_PORT. Its logs:" >&2
  docker logs "$NAME" 2>&1 | tail -20 >&2
  exit 1
fi

cat <<EOF
==> Container up on http://localhost:$LUNICLE_PORT/ (logs: docker logs -f $NAME)
EOF
