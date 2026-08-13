package com.a105.zani.recording.application.streammedia;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.recording.application.exception.InvalidMediaAccessException;
import com.a105.zani.recording.application.exception.MediaNotReadyException;
import com.a105.zani.recording.application.port.IssuedMediaUrl;
import com.a105.zani.recording.application.port.LectureMediaPort;
import com.a105.zani.recording.application.port.MediaAccessPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamMediaServiceTest {

    private static final long SESSION_ID = 100L;
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-04T10:05:00Z");
    private static final Path FILE = Path.of("/srv/zani/recordings/100/final/lecture.mp4");

    private final StubMediaAccess mediaAccess = new StubMediaAccess();
    private final StubLectureMedia lectureMedia = new StubLectureMedia();

    private final StreamMediaService service = new StreamMediaService(mediaAccess, lectureMedia);

    private Path locate(Instant expiresAt, String token) {
        return service.locate(new StreamMediaQuery(SESSION_ID, expiresAt, token));
    }

    @Test
    @DisplayName("자격이 유효하면 파일 위치를 준다")
    void a_valid_credential_resolves_the_file() {
        assertThat(locate(EXPIRES_AT, "good")).isEqualTo(FILE);
    }

    @Test
    @DisplayName("서명이 맞지 않으면 401 이다")
    void a_forged_credential_is_rejected() {
        mediaAccess.valid = false;

        assertThatThrownBy(() -> locate(EXPIRES_AT, "forged")).isInstanceOf(InvalidMediaAccessException.class);
    }

    @Test
    @DisplayName("자격이 없으면 파일을 찾아보지도 않는다 — 존재 여부가 새지 않는다")
    void an_unauthorised_request_never_touches_the_filesystem() {
        mediaAccess.valid = false;

        assertThatThrownBy(() -> locate(EXPIRES_AT, "forged")).isInstanceOf(InvalidMediaAccessException.class);
        assertThat(lectureMedia.lookups).isZero();
    }

    @Test
    @DisplayName("만료·토큰이 비면 401 이다 — 검증을 건너뛰지 않는다")
    void missing_credential_parts_are_rejected() {
        assertThatThrownBy(() -> locate(null, "good")).isInstanceOf(InvalidMediaAccessException.class);
        assertThatThrownBy(() -> locate(EXPIRES_AT, null)).isInstanceOf(InvalidMediaAccessException.class);
        assertThat(lectureMedia.lookups).isZero();
    }

    @Test
    @DisplayName("자격은 맞는데 파일이 사라졌으면 404 다")
    void a_deleted_recording_is_not_ready() {
        lectureMedia.file = null;

        assertThatThrownBy(() -> locate(EXPIRES_AT, "good")).isInstanceOf(MediaNotReadyException.class);
    }

    private static final class StubMediaAccess implements MediaAccessPort {

        boolean valid = true;

        @Override
        public IssuedMediaUrl issue(long sessionId) {
            throw new UnsupportedOperationException("재생 경로는 발급을 부르지 않는다");
        }

        @Override
        public IssuedMediaUrl issueThumbnail(long sessionId) {
            throw new UnsupportedOperationException("재생 경로는 발급을 부르지 않는다");
        }

        @Override
        public boolean matches(long sessionId, Instant expiresAt, String token) {
            return valid;
        }
    }

    private static final class StubLectureMedia implements LectureMediaPort {

        Path file = FILE;
        int lookups;

        @Override
        public Optional<Path> findLectureRecording(long sessionId) {
            lookups++;
            return Optional.ofNullable(file);
        }

        @Override
        public Optional<Path> findLectureThumbnail(long sessionId) {
            throw new UnsupportedOperationException("녹화 재생 경로는 썸네일을 찾지 않는다");
        }

        @Override
        public Optional<Path> findLectureThumbnailForServing(long sessionId) {
            throw new UnsupportedOperationException("녹화 재생 경로는 썸네일을 찾지 않는다");
        }
    }
}
