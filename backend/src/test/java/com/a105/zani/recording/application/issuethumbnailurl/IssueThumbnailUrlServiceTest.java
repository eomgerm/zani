package com.a105.zani.recording.application.issuethumbnailurl;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.recording.application.port.IssuedMediaUrl;
import com.a105.zani.recording.application.port.LectureMediaPort;
import com.a105.zani.recording.application.port.MediaAccessPort;

import static org.assertj.core.api.Assertions.assertThat;

class IssueThumbnailUrlServiceTest {

    private static final long SESSION_ID = 100L;

    private final StubLectureMedia lectureMedia = new StubLectureMedia();
    private final StubMediaAccess mediaAccess = new StubMediaAccess();

    private final IssueThumbnailUrlService service = new IssueThumbnailUrlService(lectureMedia, mediaAccess);

    @Test
    @DisplayName("썸네일 파일이 있으면 서명 주소를 준다")
    void a_present_thumbnail_gets_a_url() {
        assertThat(service.issue(SESSION_ID)).contains("https://zani.example/thumbnail?token=t");
    }

    @Test
    @DisplayName("파일이 없으면 비어 있다 — 열리지 않는 주소를 목록에 싣지 않는다")
    void a_missing_thumbnail_issues_nothing() {
        lectureMedia.file = null;

        assertThat(service.issue(SESSION_ID)).isEmpty();
        assertThat(mediaAccess.issued).isZero();
    }

    private static final class StubLectureMedia implements LectureMediaPort {

        Path file = Path.of("/srv/zani/recordings/100/final/frames/frame-50.png");

        @Override
        public Optional<Path> findLectureRecording(long sessionId) {
            throw new UnsupportedOperationException("썸네일 발급은 녹화를 찾지 않는다");
        }

        @Override
        public Optional<Path> findLectureThumbnail(long sessionId) {
            return Optional.ofNullable(file);
        }

        /** 목록 발급은 세션 수만큼 부르는 경로다 — 존재 판정이 서빙용 변환 비용을 물면 안 된다. */
        @Override
        public Optional<Path> findLectureThumbnailForServing(long sessionId) {
            throw new UnsupportedOperationException("발급의 존재 판정은 서빙용 변환을 부르지 않는다");
        }
    }

    private static final class StubMediaAccess implements MediaAccessPort {

        int issued;

        @Override
        public IssuedMediaUrl issue(long sessionId) {
            throw new UnsupportedOperationException("썸네일 발급은 녹화 주소를 만들지 않는다");
        }

        @Override
        public IssuedMediaUrl issueThumbnail(long sessionId) {
            issued++;
            return new IssuedMediaUrl("https://zani.example/thumbnail?token=t", Instant.parse("2026-08-04T10:05:00Z"));
        }

        @Override
        public boolean matches(long sessionId, Instant expiresAt, String token) {
            throw new UnsupportedOperationException("발급 경로는 검증을 부르지 않는다");
        }
    }
}
