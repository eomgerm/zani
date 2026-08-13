package com.a105.zani.recording.application.issuemediaurl;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.recording.application.exception.MediaNotReadyException;
import com.a105.zani.recording.application.port.IssuedMediaUrl;
import com.a105.zani.recording.application.port.LectureMediaPort;
import com.a105.zani.recording.application.port.MediaAccessPort;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionNotEndedException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssueMediaUrlServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long MEMBER_ID = 8L;
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-04T10:05:00Z");

    private final StubParticipantAccess access = new StubParticipantAccess();
    private final StubLectureMedia lectureMedia = new StubLectureMedia();
    private final StubMediaAccess mediaAccess = new StubMediaAccess();

    private final IssueMediaUrlService service = new IssueMediaUrlService(access, lectureMedia, mediaAccess);

    private IssueMediaUrlResult issue() {
        return service.issue(new IssueMediaUrlQuery(SESSION_ID, MEMBER_ID));
    }

    @Test
    @DisplayName("비참여자는 403 이다 — 주소를 만들지도 않는다")
    void non_participants_are_rejected() {
        access.failure = new NotSessionMemberException();

        assertThatThrownBy(this::issue).isInstanceOf(NotSessionMemberException.class);
        assertThat(mediaAccess.issued).isZero();
    }

    @Test
    @DisplayName("진행 중 수업은 404 다 — 참여자에게 409 를 주지 않는다")
    void a_live_session_is_not_ready_rather_than_a_conflict() {
        access.failure = new SessionNotEndedException();

        assertThatThrownBy(this::issue).isInstanceOf(MediaNotReadyException.class);
    }

    @Test
    @DisplayName("파일이 아직 없으면 404 다 — 열리지 않는 주소를 내주지 않는다")
    void a_missing_recording_is_not_ready() {
        lectureMedia.file = null;

        assertThatThrownBy(this::issue).isInstanceOf(MediaNotReadyException.class);
        assertThat(mediaAccess.issued).isZero();
    }

    @Test
    @DisplayName("참여 학생에게 주소와 만료 시각을 준다")
    void a_participating_student_gets_a_url() {
        access.role = SessionParticipantRole.STUDENT;

        IssueMediaUrlResult result = issue();

        assertThat(result.mediaUrl()).isEqualTo("https://zani.example/media?token=t");
        assertThat(result.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    @DisplayName("강사도 공통 녹화를 본다 — 역할로 막지 않는다")
    void instructors_may_read_the_shared_recording() {
        access.role = SessionParticipantRole.INSTRUCTOR;

        assertThat(issue().mediaUrl()).isNotBlank();
    }

    private static final class StubParticipantAccess implements ResolveEndedSessionParticipantUseCase {

        SessionParticipantRole role = SessionParticipantRole.STUDENT;
        RuntimeException failure;

        @Override
        public ResolveEndedSessionParticipantResult resolve(ResolveEndedSessionParticipantQuery query) {
            if (failure != null) {
                throw failure;
            }
            return new ResolveEndedSessionParticipantResult(
                    20L, role, Instant.parse("2026-08-04T09:00:00Z"), Instant.parse("2026-08-04T10:00:00Z"));
        }
    }

    private static final class StubLectureMedia implements LectureMediaPort {

        Path file = Path.of("/srv/zani/recordings/100/final/lecture.mp4");

        @Override
        public Optional<Path> findLectureRecording(long sessionId) {
            return Optional.ofNullable(file);
        }

        @Override
        public Optional<Path> findLectureThumbnail(long sessionId) {
            throw new UnsupportedOperationException("녹화 발급 경로는 썸네일을 찾지 않는다");
        }

        @Override
        public Optional<Path> findLectureThumbnailForServing(long sessionId) {
            throw new UnsupportedOperationException("녹화 발급 경로는 썸네일을 찾지 않는다");
        }
    }

    private static final class StubMediaAccess implements MediaAccessPort {

        int issued;

        @Override
        public IssuedMediaUrl issue(long sessionId) {
            issued++;
            return new IssuedMediaUrl("https://zani.example/media?token=t", EXPIRES_AT);
        }

        @Override
        public IssuedMediaUrl issueThumbnail(long sessionId) {
            throw new UnsupportedOperationException("녹화 발급 경로는 썸네일 주소를 만들지 않는다");
        }

        @Override
        public boolean matches(long sessionId, Instant expiresAt, String token) {
            throw new UnsupportedOperationException("발급 경로는 검증을 부르지 않는다");
        }
    }
}
