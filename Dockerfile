# syntax=docker/dockerfile:1
# Bazaarly API: multi-stage build. Tests run in CI (they need Docker for Testcontainers), not here.

# ---------- build ----------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# dependencies first, so they stay cached until pom.xml changes
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src src
RUN ./mvnw -q -B -DskipTests package \
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
