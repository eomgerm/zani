package com.a105.zani.report.application.askreportquestion;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.a105.zani.report.application.exception.ReportAssistantErrorCode;
import com.a105.zani.report.application.exception.ReportAssistantException;
import com.a105.zani.report.application.exception.ReportNotReadyException;
import com.a105.zani.report.application.listsessionsections.ListSessionSectionsQuery;
import com.a105.zani.report.application.listsessionsections.ListSessionSectionsUseCase;
import com.a105.zani.report.application.listsessionsections.SessionSectionView;
import com.a105.zani.report.application.port.AnchoredSection;
import com.a105.zani.report.application.port.AnswerLine;
import com.a105.zani.report.application.port.OutlineEntry;
import com.a105.zani.report.application.port.ReportAnswerOutcome;
import com.a105.zani.report.application.port.ReportAnswerPort;
import com.a105.zani.report.application.port.ReportAnswerRequest;
import com.a105.zani.report.application.port.ReportAssistantRateLimitPort;
import com.a105.zani.session.application.getpostclasscontext.GetPostClassContextQuery;
import com.a105.zani.session.application.getpostclasscontext.GetPostClassContextUseCase;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantQuery;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantResult;
import com.a105.zani.session.application.resolveendedparticipant.ResolveEndedSessionParticipantUseCase;

/**
 * 리포트에서 드래그한 곳에 대한 질문에 답한다(S15P11A105-259).
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 바뀌는 상태는 Redis 의 질문 간격뿐이고 나머지는 조회와 원격 호출이다 — 가이드 §6.1 이 "Redis·파일·원격 API 만
 * 쓰는 상태 변경 유스케이스에 트랜잭션을 기계적으로 주지 말라" 고 하는 자리다. 실질적으로도 GMS 호출이 수 초 걸리므로 감싸면 커넥션 풀이 먼저 마른다. 구간 조회는
 * {@link ListSessionSectionsUseCase} 가 자기 readOnly 트랜잭션 안에서 이미 끝낸다.
 *
 * <p><b>대화를 저장하지 않는다.</b> 이력 위조를 막으려 저장한다는 선택지가 있었지만, {@code question} 자체가 자유 입력이라 같은 통로가 그대로 열려 있어 방어가 되지 않는다. 진짜 방어선은
 * 근거를 서버가 만든다는 것이고, 그건 아래 구간·전사 조회가 한다. 대신 학생 질문을 적재하지 않으므로 보존 기간과 강사 노출 여부를 정할 일이 없다.
 *
 * <p>별칭 치환을 어댑터가 아니라 여기서 하는 이유는 그것이 저장 관심사가 아니라 GMS 익명화 규칙(가이드 §9)이기 때문이다. 저장 계층이 미리 바꿔 두면 규칙이 인프라에 숨어, 다음 소비자가 그것을 모른
 * 채 실명 경로를 다시 만든다.
 */
@Service
@RequiredArgsConstructor
public class AskReportQuestionService implements AskReportQuestionUseCase {

    /** 별칭 표에 없는 화자. 프롬프트가 이 값의 뜻을 알고 있다. */
    private static final String UNKNOWN_SPEAKER = "unknown";

    private final ResolveEndedSessionParticipantUseCase resolveEndedSessionParticipant;
    private final ListSessionSectionsUseCase listSessionSections;
    private final GetPostClassContextUseCase getPostClassContext;
    private final TranscriptWindowQueryPort transcriptWindow;
    private final ReportAnswerPort reportAnswer;
    private final ReportAssistantRateLimitPort rateLimit;

    @Override
    public AskReportQuestionResult ask(AskReportQuestionCommand command) {
        // 판정이 빈도 제한보다 먼저다. 순서를 뒤집으면 비참여자가 남의 세션 키를 눌러 남의 한도를 깎을 수 있다.
        ResolveEndedSessionParticipantResult participant = resolveEndedSessionParticipant.resolve(
                new ResolveEndedSessionParticipantQuery(command.sessionId(), command.memberId()));

        if (!rateLimit.tryAcquire(command.sessionId(), participant.participantId())) {
            throw new ReportAssistantException(ReportAssistantErrorCode.RATE_LIMITED);
        }

        List<SessionSectionView> sections = listSessionSections.list(new ListSessionSectionsQuery(command.sessionId()));
        if (sections.isEmpty()) {
            // 구간이 없으면 근거로 삼을 것이 없다. 요약 조회와 같은 "아직 준비되지 않음" 으로 답한다.
            throw new ReportNotReadyException();
        }

        ReportAnswerOutcome outcome = reportAnswer.answer(
                requestOf(command, sections, GroundingWindow.resolve(sections, command.anchorStartMs())));
        if (outcome.failed()) {
            throw new ReportAssistantException(errorCodeOf(outcome));
        }
        return AskReportQuestionResult.from(outcome.answer());
    }

    private ReportAnswerRequest requestOf(
            AskReportQuestionCommand command, List<SessionSectionView> sections, GroundingWindow window) {
        if (!window.anchored()) {
            // 구간 밖(전체 요약 문단)을 드래그했다. 시간 앵커가 없어 좁힐 근거가 없으므로 전사 대신 전 구간 요약을 넣는다.
            return new ReportAnswerRequest(
                    null,
                    List.of(),
                    outlineOf(sections),
                    summariesOf(sections),
                    command.question(),
                    command.selectedText(),
                    command.history(),
                    0L,
                    0L);
        }
        return new ReportAnswerRequest(
                anchorOf(window.anchor()),
                aliased(
                        transcriptWindow.findIn(command.sessionId(), window.windowFromMs(), window.windowToMs()),
                        command.sessionId()),
                outlineOf(sections),
                List.of(),
                command.question(),
                command.selectedText(),
                command.history(),
                window.windowFromMs(),
                window.windowToMs());
    }

    /** 참가자 id 를 화자 별칭으로 바꾼다. 표는 {@link GetPostClassContextUseCase} 가 이미 만들어 둔 것을 그대로 쓴다 — 순번 규칙을 여기서 다시 적으면 갈라진다. */
    private List<AnswerLine> aliased(List<TranscriptLine> lines, Long sessionId) {
        if (lines.isEmpty()) {
            // 발화가 없는 시간대다. 별칭 표를 읽을 이유가 없으므로 조회를 아낀다.
            return List.of();
        }
        Map<Long, String> aliases =
                getPostClassContext.get(new GetPostClassContextQuery(sessionId)).participantAliases();
        return lines.stream()
                .map(line -> new AnswerLine(
                        aliases.getOrDefault(line.sessionParticipantId(), UNKNOWN_SPEAKER),
                        line.startOffsetMs(),
                        line.text()))
                .toList();
    }

    private AnchoredSection anchorOf(SessionSectionView section) {
        return new AnchoredSection(
                section.title(), section.summary(), section.startedOffsetMs(), section.endedOffsetMs());
    }

    private List<OutlineEntry> outlineOf(List<SessionSectionView> sections) {
        return sections.stream()
                .map(section -> new OutlineEntry(section.title(), section.startedOffsetMs(), section.endedOffsetMs()))
                .toList();
    }

    /** 요약이 아직 없는 구간(248 이 제목만 채운 경우)은 뺀다. 빈 문자열을 넣으면 모델이 "다루지 않았다" 로 읽는다. */
    private List<String> summariesOf(List<SessionSectionView> sections) {
        return sections.stream()
                .map(SessionSectionView::summary)
                .filter(summary -> summary != null && !summary.isBlank())
                .toList();
    }

    /**
     * 실패 사유를 API 에러 코드로 옮긴다.
     *
     * <p>{@code REQUEST_TOO_LARGE} 만 4xx 로 가른다. 사용자가 질문이나 선택 범위를 줄이면 통과할 수 있는 유일한 실패라, 503 으로 묶으면 화면이 "다시 시도" 를 권해 같은
     * 실패를 반복시킨다.
     */
    private ReportAssistantErrorCode errorCodeOf(ReportAnswerOutcome outcome) {
        return switch (outcome.failure()) {
            case REQUEST_TOO_LARGE -> ReportAssistantErrorCode.QUESTION_TOO_LARGE;
            case UNAVAILABLE, UNUSABLE_RESPONSE -> ReportAssistantErrorCode.ANSWER_UNAVAILABLE;
        };
    }
}
