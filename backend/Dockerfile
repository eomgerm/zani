# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21.0.11_10-jdk-jammy AS build
WORKDIR /workspace/backend

COPY backend/gradlew backend/gradlew.bat backend/build.gradle backend/settings.gradle ./
COPY backend/gradle ./gradle
RUN bash ./gradlew dependencies --no-daemon

COPY backend/src ./src
RUN bash ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21.0.11_10-jre-jammy AS runtime
WORKDIR /app

# MP3 인코더(jump3r)가 javax.sound.sampled 를 쓰므로 런타임에 java.desktop 모듈이 있어야 한다.
# 모듈이 빠진 이미지로 바꾸면 첫 코칭 트리거에서야 NoClassDefFoundError 로 터진다. 여기서 먼저 막는다.
#
# ffmpeg 는 사후 전사(S15P11A105-95·247)가 쓴다. build.gradle 의 jump3r 주석과 Mp3TranscriptionAudioEncoder
# Javadoc 이 "네이티브 라이브러리나 ffmpeg 바이너리를 런타임 이미지에 넣지 않는다" 고 적어둔 판단을 여기서
# 뒤집는다. 그 판단은 실시간 PCM→MP3 인코딩에 한정된 것이었고 그 범위에서는 순수 Java 로 충분했다. 사후
# 전사에는 (1) 3시간 OGG/Opus 를 GMS 업로드 한도 안으로 안전하게 stream-copy 분할하는 일과 (2) GMS 호출
# 전 무음을 판별하는 일이 필요한데, 둘 다 저장소에 대체 구성요소가 없다 — jump3r 는 MP3 인코드 전용이고
# Opus 디코더는 어디에도 없다. 컨테이너 포맷을 직접 다루는 대신 검증된 바이너리를 쓴다.
# ffprobe 는 같은 ffmpeg 패키지에 들어 있다. 분할·전사는 원본 OGG 를 그대로 쓰므로 재인코딩은 하지 않는다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl gosu ffmpeg python3 util-linux \
    && groupadd --gid 10001 zani \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin zani \
    && rm -rf /var/lib/apt/lists/* \
    && { java --list-modules | grep -q '^java\.desktop' \
         || { echo 'FATAL: java.desktop is required for MP3 encoding (javax.sound.sampled)' >&2; exit 1; }; } \
    && { ffmpeg -version >/dev/null 2>&1 && ffprobe -version >/dev/null 2>&1 \
         || { echo 'FATAL: ffmpeg and ffprobe are required for post-class media processing' >&2; exit 1; }; } \
    && { python3 --version >/dev/null 2>&1 && flock --version >/dev/null 2>&1 \
         || { echo 'FATAL: python3 and flock are required for recording finalization' >&2; exit 1; }; }

COPY --from=build /workspace/backend/build/libs/*.jar /app/app.jar
COPY --chmod=0555 backend/docker-entrypoint.sh /app/docker-entrypoint.sh
COPY --chmod=0555 infrastructure/media/finalize-recording.sh /app/media/finalize-recording.sh
COPY --chmod=0444 infrastructure/media/finalize_recording.py /app/media/finalize_recording.py

EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
