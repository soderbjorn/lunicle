# syntax=docker/dockerfile:1

# ---------- build stage ----------
# Full JDK: the build compiles Kotlin/JVM *and* Kotlin/JS. The JS half also
# needs Node, which the Kotlin Gradle plugin downloads into the container
# itself — nothing to install here.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy the build definition before the sources so Docker can cache the (slow)
# Gradle + Node + dependency download layer, and a source-only edit does not
# re-download the world.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY clientServer/build.gradle.kts clientServer/
COPY client/build.gradle.kts client/
COPY server/build.gradle.kts server/
COPY web/build.gradle.kts web/
RUN chmod +x gradlew && ./gradlew --no-daemon help

COPY clientServer/src clientServer/src
COPY client/src client/src
COPY server/src server/src
COPY web/src web/src

# shadowJar bundles the server, its dependencies, and the staged web bundle
# (see copyWebDistToResources in server/build.gradle.kts) into one artifact.
# --no-daemon: a build daemon outliving the build is pure overhead in a
# throwaway image layer.
RUN ./gradlew --no-daemon :server:shadowJar

# ---------- runtime stage ----------
# JRE, not JDK: nothing at runtime compiles anything, and the smaller image is
# a smaller attack surface and a faster cold deploy.
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# The unprivileged user the server actually runs as. Nothing in the JVM needs
# root, and the part of this container that reads untrusted input from the
# internet should not have it.
#
# Note there is no `USER issues` here, which is a change from Stage 1 and looks
# like a regression until you read scripts/container-entrypoint.sh. The short
# version: a Railway volume mounts at container start owned by root, so
# *something* must chown it before the server runs, and that something needs
# root. The entrypoint below starts as root, chowns the mount, and drops to this
# user via setpriv before exec'ing the JVM. Root exists for one chown; the
# server still runs unprivileged.
RUN useradd --system --create-home --uid 10001 issues

COPY --from=build /app/server/build/libs/server-all.jar server.jar

# The default mount point for the database volume.
#
# Deliberately NOT declared with Docker's `VOLUME` instruction. Railway rejects
# the whole image if you do — "dockerfile invalid: docker VOLUME at Line 62 is
# not supported, use Railway Volumes" — and it fails at *upload*, before any
# build output, so a local `docker build` cannot reproduce it. Nothing is lost:
# `docker run -v lunicle-data:/data` mounts here regardless, and the volume that
# matters in production is the one attached in Railway's own dashboard.
#
# Creating the directory here does NOT make it writable once a volume is mounted
# over it — that is precisely the problem the entrypoint solves. This only
# covers the no-volume case.
RUN mkdir -p /data && chown issues:issues /data

COPY scripts/container-entrypoint.sh /usr/local/bin/container-entrypoint.sh
RUN chmod +x /usr/local/bin/container-entrypoint.sh

# Run from /data, not /app.
#
# This is about the *un*configured case. The server's last-resort database path
# is a relative "lunicle.db" — i.e. the working directory (see
# resolveDatabaseLocation). With WORKDIR /app that resolves to /app/lunicle.db,
# which is root-owned and therefore unwritable by the unprivileged JVM: a
# no-volume container would warn that data won't persist and then die anyway,
# with SQLite's uninformative "unable to open database file". /data is the one
# directory the entrypoint guarantees is writable, so the fallback lands
# somewhere it can actually work.
#
# Nothing else depends on the working directory — the jar is referenced
# absolutely in CMD, and the web bundle is read from the classpath.
WORKDIR /data

# Documentation only — Railway injects its own PORT at runtime and the server
# reads it (see resolvePort in Application.kt). This is the local-run default.
EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/container-entrypoint.sh"]
CMD ["java", "-jar", "/app/server.jar"]
