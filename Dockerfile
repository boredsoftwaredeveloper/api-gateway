# syntax=docker/dockerfile:1.6
# ---------- Build stage ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle gradle.properties ./
RUN chmod +x gradlew

# GitHub Packages credentials are mounted as BuildKit secrets so they
# never land in the final image or its history.
RUN --mount=type=secret,id=gh_actor,env=GITHUB_ACTOR \
    --mount=type=secret,id=gh_token,env=GITHUB_TOKEN \
    ./gradlew dependencies --no-daemon

COPY src src
RUN --mount=type=secret,id=gh_actor,env=GITHUB_ACTOR \
    --mount=type=secret,id=gh_token,env=GITHUB_TOKEN \
    ./gradlew clean bootJar -x test --no-daemon

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd -r spring && useradd -r -g spring -u 1001 spring
COPY --from=build --chown=spring:spring /app/build/libs/*.jar app.jar
USER spring

# Cap heap at 75% of container memory and use SerialGC for low-memory containers.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
