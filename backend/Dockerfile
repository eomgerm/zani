# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21.0.11_10-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle
RUN bash ./gradlew dependencies --no-daemon

COPY src ./src
RUN bash ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21.0.11_10-jre-jammy AS runtime
WORKDIR /app

# MP3 인코더(jump3r)가 javax.sound.sampled 를 쓰므로 런타임에 java.desktop 모듈이 있어야 한다.
# 모듈이 빠진 이미지로 바꾸면 첫 코칭 트리거에서야 NoClassDefFoundError 로 터진다. 여기서 먼저 막는다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl gosu \
    && groupadd --gid 10001 zani \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin zani \
    && rm -rf /var/lib/apt/lists/* \
    && { java --list-modules | grep -q '^java\.desktop' \
         || { echo 'FATAL: java.desktop is required for MP3 encoding (javax.sound.sampled)' >&2; exit 1; }; }

COPY --from=build /workspace/build/libs/*.jar /app/app.jar
COPY --chmod=0555 docker-entrypoint.sh /app/docker-entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
