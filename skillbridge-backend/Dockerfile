# The backend, built and run in containers.
#
#   docker build -t skillbridge-backend skillbridge-backend/
#
# Built by deploy/docker-compose.prod.yml, which is what actually runs it.
#
# WHY THE BUILD USES ./mvnw AND NOT THE MAVEN IMAGE
#
# The wrapper pins the Maven version. CI builds with it, and an image built with
# whatever Maven a base image happens to carry is a second toolchain nobody is
# watching. The download it does is cached in its own layer.

# ---- build ------------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

# WHY THIS IS ONE STEP AND NOT TWO
#
# The obvious shape is `dependency:go-offline` in its own layer, cached on
# pom.xml, then `package`. It was written that way and it did not work here:
#
#   Failed to execute goal ...:go-offline: The following artifacts could not be
#   resolved: net.java.dev.jna:jna-platform:jar:5.17.0
#
# go-offline resolves EVERY scope, so it goes and fetches Testcontainers,
# docker-java and their JNA native bindings -- none of which an image that does
# not run tests has any use for. It is a large amount of downloading for no
# benefit, and every artifact in it is another thing that can fail the build.
#
# So: one step, with a BuildKit cache mount holding the local repository across
# builds. That gives the same "do not re-download the world on a source change"
# property without a layer that fetches more than it needs. The cache is a
# builder-side mount rather than an image layer, so a cold builder -- CI --
# still downloads, but only what compiling and packaging actually requires.
#
# -Dmaven.test.skip=true, not -DskipTests. The latter also compiles the tests.
# Neither stops Maven RESOLVING the test classpath -- it resolves every scope
# before the lifecycle runs -- so ArchUnit and Testcontainers are still
# downloaded here. Measured: this step failed on a slow link with
#
#   Could not transfer artifact com.tngtech.archunit:archunit:jar:1.4.1 ...
#   Read timed out
#
# hence the retry and timeout settings. Maven's defaults are one attempt and a
# short read timeout, which on a bad connection turns a large download into a
# failed build rather than a slow one. The cache mount means a retry resumes
# from what already arrived instead of starting again.
#
# Tests themselves run in CI, in two tiers, one of which needs Docker itself;
# Testcontainers inside a docker build is a knot not worth tying, and an image
# is built from a commit CI has already gone green on.
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -q clean package \
      -Dmaven.test.skip=true \
      -Dmaven.wagon.http.retryHandler.count=5 \
      -Dmaven.wagon.rto=180000

# Spring Boot's layertools splits the fat jar so dependencies -- the large,
# rarely-changing part -- land in a different layer from application classes.
# A code-only change then ships kilobytes rather than ~60 MB.
RUN java -Djarmode=layertools -jar target/*.jar extract --destination extracted

# ---- runtime ----------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine

# Not root. A container escape from a root process is a host compromise; from
# an unprivileged one it is much less interesting.
RUN addgroup -S app && adduser -S -G app app

# wget is busybox's, already present in alpine, and is what HEALTHCHECK uses.
WORKDIR /app

# Least- to most-frequently-changed, so the cache survives a code change.
COPY --from=build --chown=app:app /build/extracted/dependencies/          ./
COPY --from=build --chown=app:app /build/extracted/spring-boot-loader/    ./
COPY --from=build --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/application/           ./

USER app
EXPOSE 8080

# MaxRAMPercentage rather than -Xmx: the JVM reads the container's memory limit
# and takes a fraction of it, so the same image is correct on a 1 GB container
# and a 4 GB one. A hardcoded -Xmx is wrong on every host but the one it was
# written for.
#
# No -XX:+UseZGC, though the Phase 15 draft called for it. ZGC is tuned for
# large heaps and low pause times and carries native overhead that is a poor
# trade on a t3.small; this deployment's heap is a few hundred megabytes and
# has one user. G1, the default, is the right collector here. Revisit if the
# heap ever passes a couple of gigabytes.
#
# ExitOnOutOfMemoryError: a JVM that has OOMed is in an undefined state and
# will limp along serving errors. Dying lets the restart policy do its job.
ENV JAVA_OPTS="\
  -XX:MaxRAMPercentage=70 \
  -XX:InitialRAMPercentage=40 \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom"

# /actuator/health, not /actuator/health/readiness. The readiness group only
# exists when probes are enabled, which Spring Boot does automatically on
# Kubernetes and not here -- asking for it would 404 and mark the container
# unhealthy for ever. /actuator/health already includes the database check,
# which is the thing compose needs before it starts anything that depends on
# this. show-details is when-authorized, so an unauthenticated caller gets a
# bare status and no map of the internals.
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health >/dev/null || exit 1

# `exec` is not cosmetic. Without it the shell stays PID 1 and does not forward
# SIGTERM, so every stop becomes a 10-second wait and then SIGKILL -- and a
# SIGKILLed backend drops whatever the outbox relay was mid-publish.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
