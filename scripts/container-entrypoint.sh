#!/bin/sh
#
# Container entrypoint: make the mounted volume writable, then drop root and
# run the server.
#
# This file exists because of one specific, badly-signposted interaction, and it
# is worth stating plainly because it costs an afternoon otherwise:
#
#   A Railway volume is mounted when the container STARTS, and it comes up owned
#   by root. Our server runs as an unprivileged user (`issues`, uid 10001).
#   Therefore the server cannot write to its own database, and SQLite reports it
#   as an opaque "unable to open database file" — not "permission denied", and
#   nothing points at the volume.
#
# The obvious fix — `chown` in the Dockerfile, before `USER` — does NOT work.
# The image's /data is replaced by the mount at start, so whatever ownership was
# baked into the layer is simply not what the process sees. The chown has to
# happen at RUNTIME, after the mount, which is here.
#
# Railway documents its own answer to this: set RAILWAY_RUN_UID=0 and run the
# whole server as root. That works, and it throws away the reason the image had a
# non-root user in the first place. This script keeps both properties: root
# exists for exactly as long as it takes to chown one directory, and the JVM —
# the part that parses untrusted input from the internet — never has it.
#
# Deliberately POSIX sh (`#!/bin/sh`), not bash: the runtime image is a JRE, and
# depending on bash here would be one apt-get away from a broken deploy for no
# gain.
#
set -eu

# Where the database goes. RAILWAY_VOLUME_MOUNT_PATH is injected by Railway
# whenever a volume is attached; /data is the fallback for a local `docker run`
# and matches the mount path the instructions tell you to configure.
# See resolveDatabaseLocation() in Database.kt, which resolves the same value
# independently — this only has to make the directory writable.
DATA_DIR="${RAILWAY_VOLUME_MOUNT_PATH:-/data}"

if [ "$(id -u)" = "0" ]; then
  # The normal path: we are root, so fix the mount and drop.
  mkdir -p "$DATA_DIR"

  # Not -R: the volume is ours alone and only ever holds the database, so the
  # top directory is all that needs re-owning. A recursive chown over a volume
  # that has grown large is a slow, surprising startup cost, and it would fight
  # anything that legitimately put a root-owned file there (Railway's own
  # lost+found, on some backends).
  chown issues:issues "$DATA_DIR"

  # setpriv rather than gosu or su-exec: it ships with the base image
  # (util-linux), so this costs no apt-get and no extra layer. --init-groups
  # sets the supplementary groups the way a real login would; without it the
  # process keeps root's groups, which is most of what we are trying to shed.
  exec setpriv --reuid=issues --regid=issues --init-groups "$@"
fi

# Already unprivileged — someone set RAILWAY_RUN_UID, or this is a `docker run
# -u`. Nothing to chown (we couldn't anyway) and nothing to drop. If the volume
# is root-owned, the server is about to fail loudly on its first write, which is
# the correct outcome: better a startup error than a silent read-only server.
echo "entrypoint: already running as uid $(id -u); skipping chown of $DATA_DIR" >&2
exec "$@"
