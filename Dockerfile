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

# Run as a non-root user. Nothing here needs root, and a container that never
# needed it should never have it.
RUN useradd --system --create-home --uid 10001 issues
USER issues

COPY --from=build /app/server/build/libs/server-all.jar server.jar

# Documentation only — Railway injects its own PORT at runtime and the server
# reads it (see resolvePort in Application.kt). This is the local-run default.
EXPOSE 8080

CMD ["java", "-jar", "server.jar"]
