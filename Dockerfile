# Base images are pinned by digest (CLAUDE.md §6 A08). Bump the tag and digest together:
#   docker buildx imagetools inspect <image:tag>
FROM eclipse-temurin:25-jdk@sha256:2d5d5e9b533342a565907c84f47a2ec298c40cde7b13a3ca85d4950fa456a641 AS build
WORKDIR /src

# Dependencies first so a source-only change reuses the cached layer.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q -DskipTests package \
    && mv target/caderlyhr-*.jar /app.jar

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:1faed3fc8dc16d47748e62167a7783d73299a277a4b695b90e8f530ea6a2c36f
COPY --from=build /app.jar /app/app.jar
WORKDIR /app
EXPOSE 8085
# Distroless has no shell, so the JVM is the entrypoint. Container-aware heap sizing keeps the
# JVM inside the compose memory limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
