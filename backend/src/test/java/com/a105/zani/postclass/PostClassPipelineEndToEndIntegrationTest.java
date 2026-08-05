package com.a105.zani.postclass;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.postclass.application.port.InstructorAnalysis;
import com.a105.zani.postclass.application.port.InstructorAnalysisPort;
import com.a105.zani.postclass.application.port.InstructorAnalysisRequest;
import com.a105.zani.postclass.application.port.StudentAnalysis;
import com.a105.zani.postclass.application.port.StudentAnalysisPort;
import com.a105.zani.postclass.application.port.StudentAnalysisRequest;
import com.a105.zani.postclass.application.port.TranscriptPort;
import com.a105.zani.postclass.application.runsessionanalysis.RunSessionAnalysisUseCase;
import com.a105.zani.postclass.domain.model.ConfidenceMethod;
import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.postclass.domain.model.TranscriptDocument;
import com.a105.zani.postclass.domain.model.TranscriptDocumentSegment;
import com.a105.zani.postclass.infrastructure.gms.GmsInstructorAnalysisMockAdapter;
import com.a105.zani.postclass.infrastructure.gms.GmsStudentAnalysisMockAdapter;
import com.a105.zani.recording.domain.model.TrackSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전사가 끝난 세션을 공개까지 통째로 태운다(S15P11A105-304 완료 조건).
 *
 * <p>전사 자체는 범위 밖이라 {@code ANALYZING} 단계부터 시작한다 — 45분 golden fixture 전체 통과는 S15P11A105-108 의 몫이다.
 *
 * <p><b>스케줄러가 아니라 오케스트레이터를 직접 부른다.</b> 이 테스트는 트랜잭션 안에서 돌고 끝에 롤백되는데, 스케줄러가 넘기는 실행기 스레드는 그 트랜잭션을 볼 수 없어 심어 둔 데이터를 찾지 못한다.
 * 디스패치와 선점 계약은 {@code PostClassAnalysisSchedulerTest} 가 따로 본다.
 *
 * <p>LLM 은 mock 어댑터를 쓰고 그 앞에 호출을 세는 대역을 끼운다. 재시도가 이미 끝난 단계를 다시 부르지 않는다는 것은 호출 수로만 확인할 수 있다. 로컬 MySQL·Redis 가 떠 있어야
 * 통과한다.
 */
@SpringBootTest
@Transactional
class PostClassPipelineEndToEndIntegrationTest {

    @Autowired
    private RunSessionAnalysisUseCase runSessionAnalysisUseCase;

    @Autowired
    private TranscriptPort transcriptPort;

    @Autowired
    private CountingStudentAnalysisPort studentPort;

    @Autowired
    private CountingInstructorAnalysisPort instructorPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long sessionId;
    private long instructorParticipantId;

    @BeforeEach
    void setUp() {
        studentPort.reset();
        instructorPort.reset();
        long hostMemberId = insertMember("담당 강사");
        sessionId = insertSession(hostMemberId);
        instructorParticipantId = insertParticipant(hostMemberId, "INSTRUCTOR");
        insertPipelineJob(PipelineStatus.ANALYZING);
    }

    @Test
    @DisplayName("전사가 끝난 세션이 공개까지 가고 리포트 3종이 적재된다")
    void runs_a_transcribed_session_through_to_published() {
        long student = insertStudent();
        givenTranscript(speech(instructorParticipantId, 2_000, 32_000, "오늘은 상태 관리를 다루겠습니다."));

        runSessionAnalysisUseCase.run(sessionId);

        assertThat(pipelineStatus()).isEqualTo(PipelineStatus.PUBLISHED.name());
        assertThat(publishedAt()).isNotNull();
        assertThat(sectionCount()).isPositive();
        assertThat(studentReportParticipantIds()).containsExactly(student);
        assertThat(instructorReportCount()).isEqualTo(1);
    }

    /** 공개 시각이 곧 발송 트리거다(S15P11A105-116). 이 조회가 세션을 담지 못하면 리포트가 만들어져도 메일이 나가지 않는다. */
    @Test
    @DisplayName("공개된 세션이 알림 발견 조건에 걸린다")
    void a_published_session_becomes_visible_to_the_notification_relay() {
        insertStudent();
        givenTranscript(speech(instructorParticipantId, 2_000, 32_000, "오늘은 상태 관리를 다루겠습니다."));

        runSessionAnalysisUseCase.run(sessionId);

        assertThat(jdbcTemplate.queryForList(
                        "SELECT sr.session_id FROM session_reports sr"
                                + " JOIN sessions s ON s.id = sr.session_id AND s.deleted_at IS NULL"
                                + " WHERE sr.published_at IS NOT NULL AND sr.session_id = ?",
                        Long.class,
                        sessionId))
                .containsExactly(sessionId);
    }

    @Test
    @DisplayName("두 번 돌려도 GMS 호출이 늘지 않고 리포트가 중복되지 않는다")
    void a_second_run_calls_no_gms_and_duplicates_nothing() {
        insertStudent();
        givenTranscript(speech(instructorParticipantId, 2_000, 32_000, "오늘은 상태 관리를 다루겠습니다."));
        runSessionAnalysisUseCase.run(sessionId);
        int studentCallsAfterFirst = studentPort.calls();
        int instructorCallsAfterFirst = instructorPort.calls();
        int sectionsAfterFirst = sectionCount();

        runSessionAnalysisUseCase.run(sessionId);

        assertThat(studentPort.calls()).isEqualTo(studentCallsAfterFirst);
        assertThat(instructorPort.calls()).isEqualTo(instructorCallsAfterFirst);
        assertThat(sectionCount()).isEqualTo(sectionsAfterFirst);
        assertThat(instructorReportCount()).isEqualTo(1);
    }

    /**
     * 티켓의 재시도 완료 조건이다.
     *
     * <p>강사 분석만 실패한 뒤 재시도하면 전사·공통·학생 분석을 다시 부르지 않고 강사 분석만 다시 시도해야 한다. 호출 수로만 확인할 수 있다.
     */
    @Test
    @DisplayName("강사 분석만 실패한 뒤 재시도하면 앞 단계 GMS 호출이 0회다")
    void a_retry_after_an_instructor_failure_only_calls_the_instructor_stage() {
        insertStudent();
        givenTranscript(speech(instructorParticipantId, 2_000, 32_000, "오늘은 상태 관리를 다루겠습니다."));
        instructorPort.failNext = true;

        runSessionAnalysisUseCase.run(sessionId);

        assertThat(pipelineStatus()).isEqualTo(PipelineStatus.ANALYZING.name());
        assertThat(publishedAt()).isNull();
        int studentCallsAfterFailure = studentPort.calls();

        instructorPort.failNext = false;
        runSessionAnalysisUseCase.run(sessionId);

        assertThat(studentPort.calls()).as("학생 분석을 다시 부르면 안 된다").isEqualTo(studentCallsAfterFailure);
        assertThat(instructorPort.calls()).as("강사 분석만 다시 시도한다").isEqualTo(2);
        assertThat(pipelineStatus()).isEqualTo(PipelineStatus.PUBLISHED.name());
        assertThat(publishedAt()).isNotNull();
    }

    /** 강사 리포트가 없는 채로 공개되면 안 된다. 그 상태의 리포트는 아무에게도 보이지 않아야 한다. */
    @Test
    @DisplayName("강사 분석이 실패하면 공개 시각이 찍히지 않는다")
    void does_not_stamp_the_publish_time_when_a_report_is_missing() {
        insertStudent();
        givenTranscript(speech(instructorParticipantId, 2_000, 32_000, "오늘은 상태 관리를 다루겠습니다."));
        instructorPort.failNext = true;

        runSessionAnalysisUseCase.run(sessionId);

        assertThat(publishedAt()).isNull();
        assertThat(instructorReportCount()).isZero();
        assertThat(pipelineStatus()).isNotEqualTo(PipelineStatus.PUBLISHED.name());
    }

    /** 발화가 없어도 폴백 구간이 만들어져 하류 단계가 정상 입력을 받는다. */
    @Test
    @DisplayName("무음 수업도 공개까지 간다")
    void publishes_a_silent_class() {
        insertStudent();
        givenTranscript();

        runSessionAnalysisUseCase.run(sessionId);

        assertThat(pipelineStatus()).isEqualTo(PipelineStatus.PUBLISHED.name());
        assertThat(publishedAt()).isNotNull();
        assertThat(sectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("학생이 0명인 세션은 학생 분석을 건너뛰고 공개까지 간다")
    void publishes_a_session_without_students() {
        givenTranscript(speech(instructorParticipantId, 2_000, 32_000, "오늘은 상태 관리를 다루겠습니다."));

        runSessionAnalysisUseCase.run(sessionId);

        assertThat(studentPort.calls()).isZero();
        assertThat(studentReportParticipantIds()).isEmpty();
        assertThat(pipelineStatus()).isEqualTo(PipelineStatus.PUBLISHED.name());
        assertThat(publishedAt()).isNotNull();
    }

    private void givenTranscript(TranscriptDocumentSegment... segments) {
        transcriptPort.save(sessionId, TranscriptDocument.complete("ko", List.of(segments)), Instant.now());
    }

    private static TranscriptDocumentSegment speech(
            long participantId, long startOffsetMs, long endOffsetMs, String text) {
        return new TranscriptDocumentSegment(
                participantId,
                TrackSource.MICROPHONE,
                "TR_pipeline",
                startOffsetMs,
                endOffsetMs,
                text,
                -0.21,
                0.811,
                ConfidenceMethod.EXP_AVG_LOGPROB,
                0.02,
                TsidGenerator.generate(),
                0);
    }

    private String pipelineStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM pipeline_jobs WHERE session_id = ?", String.class, sessionId);
    }

    private Object publishedAt() {
        return jdbcTemplate.queryForObject(
                "SELECT published_at FROM session_reports WHERE session_id = ?", Object.class, sessionId);
    }

    private int sectionCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_sections WHERE session_id = ?", Integer.class, sessionId);
    }

    private List<Long> studentReportParticipantIds() {
        return jdbcTemplate.queryForList(
                "SELECT session_participant_id FROM student_reports WHERE session_id = ?" + " ORDER BY id ASC",
                Long.class,
                sessionId);
    }

    private int instructorReportCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instructor_reports WHERE session_id = ?", Integer.class, sessionId);
    }

    private long insertMember(String displayName) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id,
                "google-" + id,
                id + "@example.com",
                displayName);
        return id;
    }

    private long insertSession(long hostMemberId) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status,"
                        + " started_at, ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, '상태 관리 수업', ?, 'ENDED', 'PROCESSING',"
                        + " DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 45 MINUTE), UTC_TIMESTAMP(6),"
                        + " DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 45 MINUTE), UTC_TIMESTAMP(6))",
                id,
                hostMemberId,
                inviteCode(id));
        return id;
    }

    private long insertParticipant(long memberId, String role) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),"
                        + " UTC_TIMESTAMP(6))",
                id,
                sessionId,
                memberId,
                role);
        return id;
    }

    private long insertStudent() {
        return insertParticipant(insertMember("학생"), "STUDENT");
    }

    private void insertPipelineJob(PipelineStatus status) {
        jdbcTemplate.update(
                "INSERT INTO pipeline_jobs (id, session_id, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                TsidGenerator.generate(),
                sessionId,
                status.name());
    }

    private static String inviteCode(long id) {
        String encoded = Long.toString(Math.abs(id), 36).toUpperCase();
        return encoded.length() <= 8 ? encoded : encoded.substring(encoded.length() - 8);
    }

    /** mock 어댑터를 감싸 호출 수를 센다. 프로덕션 어댑터에는 카운터를 넣지 않는다. */
    static final class CountingStudentAnalysisPort implements StudentAnalysisPort {

        private final StudentAnalysisPort delegate;
        private final AtomicInteger calls = new AtomicInteger();

        CountingStudentAnalysisPort(StudentAnalysisPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<StudentAnalysis> analyze(StudentAnalysisRequest request) {
            calls.incrementAndGet();
            return delegate.analyze(request);
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }

    /** 호출 수를 세고, 필요하면 한 번 실패시킨다 — 재시도 경로를 만들려면 실패를 주입해야 한다. */
    static final class CountingInstructorAnalysisPort implements InstructorAnalysisPort {

        private final InstructorAnalysisPort delegate;
        private final AtomicInteger calls = new AtomicInteger();

        boolean failNext;

        CountingInstructorAnalysisPort(InstructorAnalysisPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<InstructorAnalysis> analyze(InstructorAnalysisRequest request) {
            calls.incrementAndGet();
            if (failNext) {
                return Optional.empty();
            }
            return delegate.analyze(request);
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
            failNext = false;
        }
    }

    @TestConfiguration
    static class CountingPortConfig {

        @Bean
        @Primary
        CountingStudentAnalysisPort countingStudentAnalysisPort(GmsStudentAnalysisMockAdapter mockAdapter) {
            return new CountingStudentAnalysisPort(mockAdapter);
        }

        @Bean
        @Primary
        CountingInstructorAnalysisPort countingInstructorAnalysisPort(GmsInstructorAnalysisMockAdapter mockAdapter) {
            return new CountingInstructorAnalysisPort(mockAdapter);
        }
    }
}
