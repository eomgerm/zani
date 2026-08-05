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

import com.a105.zani.recording.application.exception.MediaNotReadyException;
import com.a105.zani.recording.application.issuemediaurl.IssueMediaUrlQuery;
import com.a105.zani.recording.application.issuemediaurl.IssueMediaUrlResult;
import com.a105.zani.recording.application.issuemediaurl.IssueMediaUrlUseCase;
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

    @Mock
    private IssueMediaUrlUseCase issueMediaUrlUseCase;

    private GetStudentReportService service;

    @BeforeEach
    void setUp() {
        service = new GetStudentReportService(resolver, queryPort, issueMediaUrlUseCase);
    }

    @Test
    @DisplayName("학생 본인의 활동·참여 요약·추천·전사와 재생 정보를 결과로 매핑한다")
    void maps_the_students_report() {
        givenStudentAccess(PARTICIPANT_ID);
        given(queryPort.findBySessionIdAndParticipantId(SESSION_ID, PARTICIPANT_ID))
                .willReturn(Optional.of(reportView()));
        givenIssuedMediaUrl("https://zani.test/media?token=abc");

        GetStudentReportResult result = service.get(new GetStudentReportQuery(SESSION_ID, MEMBER_ID));

        assertThat(result)
                .isEqualTo(new GetStudentReportResult(
                        new GetStudentReportResult.Activity(4L, 1L, 0L, 2),
                        "공개 채팅으로 질문하고 놓친 구간을 복습했다.",
                        List.of(new GetStudentReportResult.Recommendation(
                                77L, "CONFUSED", "재귀 종료 조건", "종료 조건을 다시 확인한다.", 10L, 20L, 1)),
                        "https://zani.test/media?token=abc",
                        // 01:00 ~ 02:00 = 3,600 초
                        3_600L,
                        List.of(new GetStudentReportResult.TranscriptSegment(5L, 12L, "김학생", "여기가 이해가 안 돼요")),
                        0L));
    }

    @Test
    @DisplayName("최종 MP4가 아직 없으면 recordingUrl만 비우고 리포트는 그대로 준다")
    void leaves_the_recording_url_empty_when_media_is_not_ready() {
        givenStudentAccess(PARTICIPANT_ID);
        given(queryPort.findBySessionIdAndParticipantId(SESSION_ID, PARTICIPANT_ID))
                .willReturn(Optional.of(reportView()));
        given(issueMediaUrlUseCase.issue(new IssueMediaUrlQuery(SESSION_ID, MEMBER_ID)))
                .willThrow(new MediaNotReadyException());

        GetStudentReportResult result = service.get(new GetStudentReportQuery(SESSION_ID, MEMBER_ID));

        assertThat(result.recordingUrl()).isNull();
        // 녹화가 없다고 리포트 전체를 잃지 않는다 — 참여 요약과 추천은 그대로 보여야 한다.
        assertThat(result.participationSummary()).isEqualTo("공개 채팅으로 질문하고 놓친 구간을 복습했다.");
        assertThat(result.recommendations()).hasSize(1);
        assertThat(result.transcript()).hasSize(1);
        assertThat(result.durationSeconds()).isEqualTo(3_600L);
    }

    @Test
    @DisplayName("종료 시각이 없는 과거 세션이면 길이는 0이다")
    void reports_zero_duration_without_an_end_time() {
        given(resolver.resolve(new ResolveEndedSessionParticipantQuery(SESSION_ID, MEMBER_ID)))
                .willReturn(new ResolveEndedSessionParticipantResult(
                        PARTICIPANT_ID, SessionParticipantRole.STUDENT, STARTED_AT, null));
        given(queryPort.findBySessionIdAndParticipantId(SESSION_ID, PARTICIPANT_ID))
                .willReturn(Optional.of(reportView()));
        given(issueMediaUrlUseCase.issue(new IssueMediaUrlQuery(SESSION_ID, MEMBER_ID)))
                .willThrow(new MediaNotReadyException());

        assertThat(service.get(new GetStudentReportQuery(SESSION_ID, MEMBER_ID)).durationSeconds())
                .isZero();
    }

    @Test
    @DisplayName("조회 participant ID는 요청이 아니라 종료 세션 resolver 결과만 사용한다")
    void uses_the_resolved_participant_id() {
        long resolvedParticipantId = 909L;
        givenStudentAccess(resolvedParticipantId);
        given(queryPort.findBySessionIdAndParticipantId(SESSION_ID, resolvedParticipantId))
                .willReturn(Optional.of(reportView()));
        givenIssuedMediaUrl("https://zani.test/media?token=abc");

        service.get(new GetStudentReportQuery(SESSION_ID, MEMBER_ID));

        then(queryPort).should().findBySessionIdAndParticipantId(SESSION_ID, resolvedParticipantId);
    }

    @Test
    @DisplayName("녹화 주소는 요청자 본인 자격으로만 발급한다")
    void issues_the_recording_url_for_the_caller() {
        givenStudentAccess(PARTICIPANT_ID);
        given(queryPort.findBySessionIdAndParticipantId(SESSION_ID, PARTICIPANT_ID))
                .willReturn(Optional.of(reportView()));
        givenIssuedMediaUrl("https://zani.test/media?token=abc");

        service.get(new GetStudentReportQuery(SESSION_ID, MEMBER_ID));

        then(issueMediaUrlUseCase).should().issue(new IssueMediaUrlQuery(SESSION_ID, MEMBER_ID));
    }

    @Test
    @DisplayName("리포트가 없으면 녹화 주소를 발급하지 않는다")
    void does_not_issue_a_recording_url_without_a_report() {
        givenStudentAccess(PARTICIPANT_ID);
        given(queryPort.findBySessionIdAndParticipantId(SESSION_ID, PARTICIPANT_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(new GetStudentReportQuery(SESSION_ID, MEMBER_ID)))
                .isInstanceOf(ReportNotReadyException.class);
        then(issueMediaUrlUseCase).shouldHaveNoInteractions();
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

    private void givenIssuedMediaUrl(String mediaUrl) {
        given(issueMediaUrlUseCase.issue(new IssueMediaUrlQuery(SESSION_ID, MEMBER_ID)))
                .willReturn(new IssueMediaUrlResult(mediaUrl, ENDED_AT));
    }

    private static StudentReportView reportView() {
        return new StudentReportView(
                4L,
                1L,
                0L,
                2,
                "공개 채팅으로 질문하고 놓친 구간을 복습했다.",
                List.of(new StudentReportView.Recommendation(
                        77L, "CONFUSED", "재귀 종료 조건", "종료 조건을 다시 확인한다.", 10L, 20L, 1)),
                List.of(new StudentReportView.TranscriptSegment(5L, 12L, "김학생", "여기가 이해가 안 돼요")));
    }
}
