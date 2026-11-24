# syntax=docker/dockerfile:1
# Bazaarly API: multi-stage build. Tests run in CI (they need Docker for Testcontainers), not here.

# ---------- build ----------
# The official Maven image, so the Maven wrapper doesn't have to download Maven from Maven Central.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# dependencies from Google's mirror of Maven Central (see docker/maven-settings.xml)
COPY docker/maven-settings.xml /root/.m2/settings.xml

# dependencies first, so they stay cached until pom.xml changes; retried with a growing pause if a download fails
COPY pom.xml ./
RUN for attempt in 1 2 3 4 5; do \
      mvn -q -B dependency:go-offline && break; \
      if [ "$attempt" = 5 ]; then exit 1; fi; \
      echo "Downloading dependencies failed (attempt $attempt), retrying in $((attempt * 20)) s"; sleep $((attempt * 20)); \
    done

COPY src src
RUN mvn -q -B -DskipTests package \
    && cp target/e-commerce-*.jar app.jar \
    # split the Spring Boot jar into layers: dependencies change rarely, application code often
    && java -Djarmode=layertools -jar app.jar extract --destination extracted

# ---------- run ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# run as an unprivileged user
RUN groupadd --system bazaarly && useradd --system --gid bazaarly --no-create-home bazaarly

COPY --from=build --chown=bazaarly:bazaarly /workspace/extracted/dependencies/ ./
COPY --from=build --chown=bazaarly:bazaarly /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=bazaarly:bazaarly /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=bazaarly:bazaarly /workspace/extracted/application/ ./

USER bazaarly

ENV SPRING_PROFILES_ACTIVE=prod \
    PORT=5454 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 5454

# /actuator/health returns {"status":"UP"} once the app and its database are ready
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- "http://localhost:${PORT}/actuator/health" | grep -q '"UP"' || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.JarLauncher"]
