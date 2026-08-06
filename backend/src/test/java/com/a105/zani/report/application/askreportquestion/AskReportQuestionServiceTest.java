package com.a105.zani.report.application.askreportquestion;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.a105.zani.common.error.BusinessException;
import com.a105.zani.report.application.exception.ReportAssistantErrorCode;
import com.a105.zani.report.application.exception.ReportNotReadyException;
import com.a105.zani.report.application.listsessionsections.ListSessionSectionsQuery;
import com.a105.zani.report.application.listsessionsections.ListSessionSectionsUseCase;
import com.a105.zani.report.application.listsessionsections.SessionSectionView;
import com.a105.zani.report.application.port.AnswerLine;
import com.a105.zani.report.application.port.ReportAnswer;
import com.a105.zani.report.application.port.ReportAnswerFailure;
import com.a105.zani.report.application.port.ReportAnswerOutcome;
import com.a105.zani.report.application.port.ReportAnswerPort;
import com.a105.zani.report.application.port.ReportAnswerRequest;
import com.a105.zani.report.application.port.ReportAssistantRateLimitPort;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.getpostclasscontext.GetPostClassContextQuery;
import com.a105.zani.session.application.getpostclasscontext.GetPostClassContextResult;
import com.a105.zani.session.application.getpostclasscontext.GetPostClassContextUseCase;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/** 근거를 모으는 순서와, 실패 사유가 어떤 에러 코드로 나가는지를 고정한다. */
@ExtendWith(MockitoExtension.class)
class AskReportQuestionServiceTest {

    private static final long SESSION_ID = 700L;
    private static final long MEMBER_ID = 42L;
    private static final long PARTICIPANT_ID = 9_001L;
    private static final long OTHER_PARTICIPANT_ID = 9_002L;

    private static final List<SessionSectionView> SECTIONS = List.of(
            new SessionSectionView(0L, 60_000L, "수업 시작", "인사했다."),
            new SessionSectionView(60_000L, 95_000L, "해시 테이블", "키를 인덱스로 바꾼다."),
            new SessionSectionView(95_000L, 140_000L, "해시 충돌 해결", "체이닝과 개방주소법."));

    @Mock
    private ResolveEndedSessionParticipantUseCase resolveEndedSessionParticipant;

    @Mock
    private ListSessionSectionsUseCase listSessionSections;

    @Mock
    private GetPostClassContextUseCase getPostClassContext;

    @Mock
    private TranscriptWindowQueryPort transcriptWindow;

    @Mock
    private ReportAnswerPort reportAnswer;

    @Mock
    private ReportAssistantRateLimitPort rateLimit;

    private AskReportQuestionService service;

    @BeforeEach
    void setUp() {
        service = new AskReportQuestionService(
                resolveEndedSessionParticipant,
                listSessionSections,
                getPostClassContext,
                transcriptWindow,
                reportAnswer,
                rateLimit);
    }

    private AskReportQuestionCommand command(Long anchorStartMs) {
        return new AskReportQuestionCommand(SESSION_ID, MEMBER_ID, "개방주소법이 뭐야?", "개방주소법", anchorStartMs, List.of());
    }

    private void givenParticipant() {
        given(resolveEndedSessionParticipant.resolve(new ResolveEndedSessionParticipantQuery(SESSION_ID, MEMBER_ID)))
                .willReturn(new ResolveEndedSessionParticipantResult(
                        PARTICIPANT_ID, SessionParticipantRole.STUDENT, Instant.EPOCH, Instant.EPOCH));
    }

    private void givenSections() {
        given(listSessionSections.list(new ListSessionSectionsQuery(SESSION_ID)))
                .willReturn(SECTIONS);
    }

    private void givenAnswered() {
        given(reportAnswer.answer(any()))
                .willReturn(ReportAnswerOutcome.success(new ReportAnswer("답", List.of(), true)));
    }

    private ReportAnswerRequest capturedRequest() {
        ArgumentCaptor<ReportAnswerRequest> captor = ArgumentCaptor.forClass(ReportAnswerRequest.class);
        then(reportAnswer).should().answer(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("참여자 판정이 빈도 제한보다 먼저다")
    void resolves_the_participant_before_spending_the_rate_limit() {
        given(resolveEndedSessionParticipant.resolve(any())).willThrow(new NotSessionMemberException());

        assertThatThrownBy(() -> service.ask(command(120_000L))).isInstanceOf(NotSessionMemberException.class);

        // 순서를 뒤집으면 비참여자가 남의 세션 키를 눌러 남의 한도를 깎을 수 있다.
        then(rateLimit).should(never()).tryAcquire(anyLong(), anyLong());
        then(reportAnswer).should(never()).answer(any());
    }

    @Test
    @DisplayName("빈도 제한에 걸리면 GMS 를 부르지 않는다")
    void does_not_call_gms_when_rate_limited() {
        givenParticipant();
        given(rateLimit.tryAcquire(SESSION_ID, PARTICIPANT_ID)).willReturn(false);

        assertThatThrownBy(() -> service.ask(command(120_000L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReportAssistantErrorCode.RATE_LIMITED);

        // 막으려는 것이 화면 도배가 아니라 크레딧이라, 여기서 호출이 나가면 제한이 의미가 없다.
        then(reportAnswer).should(never()).answer(any());
    }

    @Test
    @DisplayName("내용 구간이 없으면 아직 준비되지 않음으로 끊는다")
    void reports_not_ready_when_the_session_has_no_sections() {
        givenParticipant();
        given(rateLimit.tryAcquire(SESSION_ID, PARTICIPANT_ID)).willReturn(true);
        given(listSessionSections.list(any())).willReturn(List.of());

        // 근거로 삼을 것이 없다. 요약 조회와 같은 코드를 써서 화면이 문구를 하나만 갖게 한다.
        assertThatThrownBy(() -> service.ask(command(120_000L))).isInstanceOf(ReportNotReadyException.class);
        then(reportAnswer).should(never()).answer(any());
    }

    @Test
    @DisplayName("앵커가 있으면 시간창 전사를 별칭으로 바꿔 보낸다")
    void sends_the_window_transcript_with_aliases() {
        givenParticipant();
        given(rateLimit.tryAcquire(SESSION_ID, PARTICIPANT_ID)).willReturn(true);
        givenSections();
        given(transcriptWindow.findIn(SESSION_ID, 60_000L, 140_000L))
                .willReturn(List.of(
                        new TranscriptLine(63_000L, 69_000L, PARTICIPANT_ID, "해시 테이블은"),
                        new TranscriptLine(98_000L, 99_000L, OTHER_PARTICIPANT_ID, "체이닝이 있습니다")));
        given(getPostClassContext.get(new GetPostClassContextQuery(SESSION_ID)))
                .willReturn(new GetPostClassContextResult(
                        Instant.EPOCH, Instant.EPOCH, Map.of(PARTICIPANT_ID, "student-001")));
        givenAnswered();

        service.ask(command(120_000L));

        ReportAnswerRequest sent = capturedRequest();
        // 실명은 물론 참가자 id 도 나가지 않는다. 표에 없는 화자는 unknown 이며 프롬프트가 그 뜻을 안다.
        assertThat(sent.lines()).extracting(AnswerLine::speaker).containsExactly("student-001", "unknown");
        assertThat(sent.anchoredSection().title()).isEqualTo("해시 충돌 해결");
        assertThat(sent.windowFromMs()).isEqualTo(60_000L);
        assertThat(sent.windowToMs()).isEqualTo(140_000L);
    }

    @Test
    @DisplayName("앵커가 없으면 전사 대신 전 구간 요약을 보낸다")
    void falls_back_to_section_summaries_without_an_anchor() {
        givenParticipant();
        given(rateLimit.tryAcquire(SESSION_ID, PARTICIPANT_ID)).willReturn(true);
        givenSections();
        givenAnswered();

        service.ask(command(null));

        ReportAnswerRequest sent = capturedRequest();
        // 전체 요약 문단을 드래그한 경우다. 좁힐 근거가 없으므로 전사 조회 자체를 하지 않는다.
        assertThat(sent.anchored()).isFalse();
        assertThat(sent.lines()).isEmpty();
        assertThat(sent.fallbackSummaries()).containsExactly("인사했다.", "키를 인덱스로 바꾼다.", "체이닝과 개방주소법.");
        assertThat(sent.outline()).hasSize(3);
        then(transcriptWindow).should(never()).findIn(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("발화가 없는 시간창이면 별칭 표를 읽지 않는다")
    void skips_the_alias_lookup_when_the_window_is_silent() {
        givenParticipant();
        given(rateLimit.tryAcquire(SESSION_ID, PARTICIPANT_ID)).willReturn(true);
        givenSections();
        given(transcriptWindow.findIn(SESSION_ID, 60_000L, 140_000L)).willReturn(List.of());
        givenAnswered();

        service.ask(command(120_000L));

        // 침묵 구간에서 참가자 표를 통째로 읽을 이유가 없다. 목차만으로도 답할 수 있다.
        then(getPostClassContext).should(never()).get(any());
        assertThat(capturedRequest().lines()).isEmpty();
    }

    @Test
    @DisplayName("본문이 상한을 넘으면 다시 시도할 수 없는 실패로 가른다")
    void maps_an_oversized_body_to_a_client_error() {
        givenParticipant();
        given(rateLimit.tryAcquire(SESSION_ID, PARTICIPANT_ID)).willReturn(true);
        givenSections();
        given(reportAnswer.answer(any())).willReturn(ReportAnswerOutcome.failed(ReportAnswerFailure.REQUEST_TOO_LARGE));

        // 503 으로 묶으면 화면이 "다시 시도" 를 권해 같은 실패를 반복시킨다. 줄이면 통과하는 유일한 실패다.
        assertThatThrownBy(() -> service.ask(command(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReportAssistantErrorCode.QUESTION_TOO_LARGE);
    }

    @Test
    @DisplayName("GMS 실패와 계약 위반은 같은 503 으로 낸다")
    void maps_gateway_failures_to_service_unavailable() {
        givenParticipant();
        given(rateLimit.tryAcquire(SESSION_ID, PARTICIPANT_ID)).willReturn(true);
        givenSections();
        given(reportAnswer.answer(any())).willReturn(ReportAnswerOutcome.failed(ReportAnswerFailure.UNAVAILABLE));

        assertThatThrownBy(() -> service.ask(command(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReportAssistantErrorCode.ANSWER_UNAVAILABLE);
    }
}
