package com.a105.zani.report.application.getsessionsummary;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.a105.zani.report.application.exception.ReportNotReadyException;
import com.a105.zani.report.application.listsessionsections.ListSessionSectionsQuery;
import com.a105.zani.report.application.listsessionsections.ListSessionSectionsUseCase;
import com.a105.zani.report.application.listsessionsections.SessionSectionView;
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

@ExtendWith(MockitoExtension.class)
class GetSessionSummaryServiceTest {

    private static final long SESSION_ID = 42L;
    private static final long MEMBER_ID = 7L;
    private static final long PARTICIPANT_ID = 101L;
    private static final String SUMMARY = "이번 수업은 지역 상태에서 출발해 Context 리렌더링으로 이어졌습니다.";
    private static final Instant STARTED_AT = Instant.parse("2026-08-05T01:00:00Z");
    private static final Instant ENDED_AT = Instant.parse("2026-08-05T02:00:00Z");
    private static final List<SessionSectionView> SECTIONS = List.of(
            new SessionSectionView(0, 600_000, "상태 관리 개요", "지역 상태와 전역 상태를 가르는 기준을 설명했다."),
            new SessionSectionView(600_000, 1_200_000, "Context 리렌더링", "Context 값이 바뀔 때 어디까지 다시 그리는지 짚었다."));

    @Mock
    private ResolveEndedSessionParticipantUseCase resolver;

    @Mock
    private SessionSummaryQueryPort queryPort;

    @Mock
    private ListSessionSectionsUseCase listSessionSections;

    private GetSessionSummaryService service;

    @BeforeEach
    void setUp() {
        service = new GetSessionSummaryService(resolver, queryPort, listSessionSections);
    }

    @Test
    @DisplayName("학생과 강사가 같은 요약과 같은 구간을 받는다")
    void gives_the_same_summary_to_both_roles() {
        givenAccessAs(SessionParticipantRole.STUDENT);
        given(queryPort.findPublishedSummaryBySessionId(SESSION_ID)).willReturn(Optional.of(SUMMARY));
        given(listSessionSections.list(new ListSessionSectionsQuery(SESSION_ID)))
                .willReturn(SECTIONS);

        GetSessionSummaryResult asStudent = service.get(new GetSessionSummaryQuery(SESSION_ID, MEMBER_ID));

        givenAccessAs(SessionParticipantRole.INSTRUCTOR);

        GetSessionSummaryResult asInstructor = service.get(new GetSessionSummaryQuery(SESSION_ID, MEMBER_ID));

        // 역할로 갈라지면 강사가 "리포트 3번 항목" 이라고 말할 때 학생 화면의 3번이 다른 문장이 된다.
        assertThat(asStudent).isEqualTo(asInstructor).isEqualTo(new GetSessionSummaryResult(SUMMARY, SECTIONS));
    }

    @Test
    @DisplayName("구간이 없는 세션도 요약을 받는다")
    void serves_a_summary_without_any_section() {
        givenAccessAs(SessionParticipantRole.STUDENT);
        given(queryPort.findPublishedSummaryBySessionId(SESSION_ID)).willReturn(Optional.of(SUMMARY));
        given(listSessionSections.list(new ListSessionSectionsQuery(SESSION_ID)))
                .willReturn(List.of());

        GetSessionSummaryResult result = service.get(new GetSessionSummaryQuery(SESSION_ID, MEMBER_ID));

        // 내용 타임라인 없이 요약만 있는 옛 세션이 있다. 빈 목록은 오류가 아니라 "구간이 없다" 는 뜻이다.
        assertThat(result).isEqualTo(new GetSessionSummaryResult(SUMMARY, List.of()));
    }

    @Test
    @DisplayName("게시된 요약이 없으면 REPORT_NOT_READY고 구간을 읽지 않는다")
    void reports_not_ready_without_a_published_summary() {
        givenAccessAs(SessionParticipantRole.STUDENT);
        given(queryPort.findPublishedSummaryBySessionId(SESSION_ID)).willReturn(Optional.empty());

        // 빈 문자열로 내리지 않는다 — 화면이 "기다리세요" 와 "요약이 비었다" 를 구분해야 한다.
        assertThatThrownBy(() -> service.get(new GetSessionSummaryQuery(SESSION_ID, MEMBER_ID)))
                .isInstanceOf(ReportNotReadyException.class);
        // 카드가 그려지지 않을 세션에 조회를 한 번 더 보내지 않는다.
        then(listSessionSections).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("비참가자는 403이고 요약을 읽지 않는다")
    void hides_the_summary_from_an_outsider() {
        given(resolver.resolve(new ResolveEndedSessionParticipantQuery(SESSION_ID, MEMBER_ID)))
                .willThrow(new NotSessionMemberException());

        assertThatThrownBy(() -> service.get(new GetSessionSummaryQuery(SESSION_ID, MEMBER_ID)))
                .isInstanceOf(NotSessionMemberException.class);
        then(queryPort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("세션이 없으면 404를 그대로 전파한다")
    void propagates_a_missing_session() {
        given(resolver.resolve(new ResolveEndedSessionParticipantQuery(SESSION_ID, MEMBER_ID)))
                .willThrow(new SessionNotFoundException());

        assertThatThrownBy(() -> service.get(new GetSessionSummaryQuery(SESSION_ID, MEMBER_ID)))
                .isInstanceOf(SessionNotFoundException.class);
        then(queryPort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("진행 중 세션이면 409를 그대로 전파한다")
    void propagates_a_live_session_conflict() {
        given(resolver.resolve(new ResolveEndedSessionParticipantQuery(SESSION_ID, MEMBER_ID)))
                .willThrow(new SessionNotEndedException());

        assertThatThrownBy(() -> service.get(new GetSessionSummaryQuery(SESSION_ID, MEMBER_ID)))
                .isInstanceOf(SessionNotEndedException.class);
        then(queryPort).shouldHaveNoInteractions();
    }

    private void givenAccessAs(SessionParticipantRole role) {
        given(resolver.resolve(new ResolveEndedSessionParticipantQuery(SESSION_ID, MEMBER_ID)))
                .willReturn(new ResolveEndedSessionParticipantResult(PARTICIPANT_ID, role, STARTED_AT, ENDED_AT));
    }
}
