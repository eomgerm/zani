package com.a105.zani.report.application.publishsessionreport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.report.domain.model.InstructorReport;
import com.a105.zani.report.domain.model.SessionReport;
import com.a105.zani.report.domain.model.StudentReport;
import com.a105.zani.report.domain.repository.InstructorReportRepository;
import com.a105.zani.report.domain.repository.SessionReportRepository;
import com.a105.zani.report.domain.repository.StudentReportRepository;

import static org.assertj.core.api.Assertions.assertThat;

/** 공개는 곧 메일 발송이라 되돌릴 수 없다. 리포트가 갖춰지지 않았을 때 시각이 찍히지 않는지가 이 테스트의 핵심이다. */
class SessionReportPublishServiceTest {

    private static final Long SESSION_ID = 9_304_200L;
    private static final Instant NOW = Instant.parse("2026-08-05T03:00:00Z");

    private boolean sessionReportExists;
    private boolean instructorReportExists;
    private boolean alreadyPublished;
    private Instant publishedAt;
    private Instant instructorReportPublishedAt;
    private Instant studentReportsPublishedAt;
    private int studentReportCount;

    private final SessionReportRepository sessionReportRepository = new SessionReportRepository() {

        @Override
        public void save(SessionReport report) {
            throw new UnsupportedOperationException("공개는 적재하지 않는다");
        }

        @Override
        public boolean existsBySessionId(Long sessionId) {
            return sessionReportExists;
        }

        @Override
        public boolean markPublished(Long sessionId, Instant at) {
            if (alreadyPublished) {
                return false;
            }
            publishedAt = at;
            return true;
        }
    };

    private final InstructorReportRepository instructorReportRepository = new InstructorReportRepository() {

        @Override
        public Optional<Long> saveIfAbsent(InstructorReport report) {
            throw new UnsupportedOperationException("공개는 적재하지 않는다");
        }

        @Override
        public boolean existsBySessionId(Long sessionId) {
            return instructorReportExists;
        }

        @Override
        public boolean markPublished(Long sessionId, Instant at) {
            instructorReportPublishedAt = at;
            return true;
        }
    };

    private final StudentReportRepository studentReportRepository = new StudentReportRepository() {

        @Override
        public Optional<Long> saveIfAbsent(StudentReport report) {
            throw new UnsupportedOperationException("공개는 적재하지 않는다");
        }

        @Override
        public int markPublished(Long sessionId, Instant at) {
            studentReportsPublishedAt = at;
            return studentReportCount;
        }
    };

    private SessionReportPublishService service;

    @BeforeEach
    void setUp() {
        sessionReportExists = true;
        instructorReportExists = true;
        alreadyPublished = false;
        publishedAt = null;
        instructorReportPublishedAt = null;
        studentReportsPublishedAt = null;
        studentReportCount = 3;
        service = new SessionReportPublishService(
                sessionReportRepository,
                instructorReportRepository,
                studentReportRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 세 조회가 각자 자기 테이블의 공개 시각으로 열람 가능 여부를 정한다. 공통 리포트에만 찍으면 강사도 학생도 리포트를 영구히 열 수 없다. */
    @Test
    @DisplayName("리포트가 갖춰지면 세 리포트에 같은 공개 시각을 찍는다")
    void publishes_when_the_reports_are_in_place() {
        assertThat(service.publish(SESSION_ID)).isEqualTo(PublishSessionReportOutcome.PUBLISHED);
        assertThat(publishedAt).isEqualTo(NOW);
        assertThat(instructorReportPublishedAt).isEqualTo(NOW);
        assertThat(studentReportsPublishedAt).isEqualTo(NOW);
    }

    /** 학생이 없는 수업은 갱신할 학생 리포트가 없다. 그것은 공개 실패가 아니다. */
    @Test
    @DisplayName("학생 리포트가 없어도 공개는 성공한다")
    void publishes_even_without_student_reports() {
        studentReportCount = 0;

        assertThat(service.publish(SESSION_ID)).isEqualTo(PublishSessionReportOutcome.PUBLISHED);
        assertThat(publishedAt).isEqualTo(NOW);
    }

    @Test
    @DisplayName("공통 리포트가 없으면 공개하지 않는다")
    void refuses_without_the_session_report() {
        sessionReportExists = false;

        assertThat(service.publish(SESSION_ID)).isEqualTo(PublishSessionReportOutcome.REPORTS_MISSING);
        assertThat(publishedAt).isNull();
        assertThat(instructorReportPublishedAt).isNull();
        assertThat(studentReportsPublishedAt).isNull();
    }

    @Test
    @DisplayName("강사 리포트가 없으면 공개하지 않는다")
    void refuses_without_the_instructor_report() {
        instructorReportExists = false;

        assertThat(service.publish(SESSION_ID)).isEqualTo(PublishSessionReportOutcome.REPORTS_MISSING);
        assertThat(publishedAt).isNull();
        assertThat(instructorReportPublishedAt).isNull();
        assertThat(studentReportsPublishedAt).isNull();
    }

    /** 재시도가 시각을 덮으면 알림이 발견 여부를 정하는 기준이 흔들린다. 강사·학생 리포트도 같은 이유로 손대지 않는다. */
    @Test
    @DisplayName("이미 공개된 세션은 시각을 덮지 않는다")
    void keeps_the_original_time_when_already_published() {
        alreadyPublished = true;

        assertThat(service.publish(SESSION_ID)).isEqualTo(PublishSessionReportOutcome.ALREADY_PUBLISHED);
        assertThat(publishedAt).isNull();
        assertThat(instructorReportPublishedAt).isNull();
        assertThat(studentReportsPublishedAt).isNull();
    }
}
