# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21.0.11_10-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon

COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21.0.11_10-jre-jammy AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl gosu \
    && groupadd --gid 10001 zani \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin zani \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/build/libs/*.jar /app/app.jar
COPY --chmod=0555 docker-entrypoint.sh /app/docker-entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
