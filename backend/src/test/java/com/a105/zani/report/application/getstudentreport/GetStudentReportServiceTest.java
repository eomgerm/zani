package com.a105.zani.report.application.getstudentreport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.a105.zani.report.application.exception.NotSessionStudentReportException;
import com.a105.zani.report.application.exception.ReportNotReadyException;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionNotEndedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class GetStudentReportServiceTest {

    private static final long SESSION_ID = 42L;
    private static final long MEMBER_ID = 7L;
    private static final long PARTICIPANT_ID = 101L;
    private static final Instant STARTED_AT = Instant.parse("2026-08-04T01:00:00Z");
    private static final Instant ENDED_AT = Instant.parse("2026-08-04T02:00:00Z");

    @Mock
    private ResolveEndedSessionParticipantUseCase resolver;

    @Mock
    private StudentReportQueryPort queryPort;

    private GetStudentReportService service;

    @BeforeEach
    void setUp() {
        service = new GetStudentReportService(resolver, queryPort);
    }

    @Test
    @DisplayName("학생 본인의 활동·참여 요약·추천을 결과로 매핑한다")
    void maps_the_students_report() {
        givenStudentAccess(PARTICIPANT_ID);
        given(queryPort.findBySessionIdAndParticipantId(SESSION_ID, PARTICIPANT_ID))
                .willReturn(Optional.of(reportView()));

        GetStudentReportResult result = service.get(new GetStudentReportQuery(SESSION_ID, MEMBER_ID));

        assertThat(result)
                .isEqualTo(new GetStudentReportResult(
                        new GetStudentReportResult.Activity(4L, 1L, 0L, 2),
                        "공개 채팅으로 질문하고 놓친 구간을 복습했다.",
                        List.of(new GetStudentReportResult.Recommendation(
                                "CONFUSED", "재귀 종료 조건", "종료 조건을 다시 확인한다.", 10L, 20L, 1))));
    }

    @Test
    @DisplayName("조회 participant ID는 요청이 아니라 종료 세션 resolver 결과만 사용한다")
    void uses_the_resolved_participant_id() {
        long resolvedParticipantId = 909L;
        givenStudentAccess(resolvedParticipantId);
        given(queryPort.findBySessionIdAndParticipantId(SESSION_ID, resolvedParticipantId))
                .willReturn(Optional.of(reportView()));

        service.get(new GetStudentReportQuery(SESSION_ID, MEMBER_ID));

        then(queryPort).should().findBySessionIdAndParticipantId(SESSION_ID, resolvedParticipantId);
    }

    @Test
    @DisplayName("강사는 학생 리포트를 조회하지 못하고 projection도 읽지 않는다")
    void rejects_an_instructor_before_reading_the_projection() {
        given(resolver.resolve(new ResolveEndedSessionParticipantQuery(SESSION_ID, MEMBER_ID)))
                .willReturn(new ResolveEndedSessionParticipantResult(
                        PARTICIPANT_ID, SessionParticipantRole.INSTRUCTOR, STARTED_AT, ENDED_AT));

        assertThatThrownBy(() -> service.get(new GetStudentReportQuery(SESSION_ID, MEMBER_ID)))
                .isInstanceOf(NotSessionStudentReportException.class);
        then(queryPort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("게시된 학생 리포트가 없으면 REPORT_NOT_READY다")
    void reports_not_ready_when_the_projection_is_empty() {
        givenStudentAccess(PARTICIPANT_ID);
        given(queryPort.findBySessionIdAndParticipantId(SESSION_ID, PARTICIPANT_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(new GetStudentReportQuery(SESSION_ID, MEMBER_ID)))
                .isInstanceOf(ReportNotReadyException.class);
    }

    @Test
    @DisplayName("비멤버 403을 그대로 전파하고 projection을 읽지 않는다")
    void propagates_forbidden_access() {
        given(resolver.resolve(new ResolveEndedSessionParticipantQuery(SESSION_ID, MEMBER_ID)))
                .willThrow(new NotSessionMemberException());

        assertThatThrownBy(() -> service.get(new GetStudentReportQuery(SESSION_ID, MEMBER_ID)))
                .isInstanceOf(NotSessionMemberException.class);
        then(queryPort).should(never()).findBySessionIdAndParticipantId(SESSION_ID, PARTICIPANT_ID);
    }

    @Test
    @DisplayName("멤버의 세션 행이 없으면 404를 그대로 전파한다")
    void propagates_a_missing_session() {
        given(resolver.resolve(new ResolveEndedSessionParticipantQuery(SESSION_ID, MEMBER_ID)))
                .willThrow(new SessionNotFoundException());

        assertThatThrownBy(() -> service.get(new GetStudentReportQuery(SESSION_ID, MEMBER_ID)))
                .isInstanceOf(SessionNotFoundException.class);
        then(queryPort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("진행 중 세션이면 409를 그대로 전파한다")
    void propagates_a_live_session_conflict() {
        given(resolver.resolve(new ResolveEndedSessionParticipantQuery(SESSION_ID, MEMBER_ID)))
                .willThrow(new SessionNotEndedException());

        assertThatThrownBy(() -> service.get(new GetStudentReportQuery(SESSION_ID, MEMBER_ID)))
                .isInstanceOf(SessionNotEndedException.class);
        then(queryPort).shouldHaveNoInteractions();
    }

    private void givenStudentAccess(long participantId) {
        given(resolver.resolve(new ResolveEndedSessionParticipantQuery(SESSION_ID, MEMBER_ID)))
                .willReturn(new ResolveEndedSessionParticipantResult(
                        participantId, SessionParticipantRole.STUDENT, STARTED_AT, ENDED_AT));
    }

    private static StudentReportView reportView() {
        return new StudentReportView(
                4L,
                1L,
                0L,
                2,
                "공개 채팅으로 질문하고 놓친 구간을 복습했다.",
                List.of(new StudentReportView.Recommendation("CONFUSED", "재귀 종료 조건", "종료 조건을 다시 확인한다.", 10L, 20L, 1)));
    }
}
