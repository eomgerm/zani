package com.a105.zani.attention.application.recordpromptresponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.a105.zani.attention.application.exception.AttentionStateUnavailableException;
import com.a105.zani.attention.application.exception.InvalidPromptTimelineException;
import com.a105.zani.attention.application.exception.MismatchedPromptAnswerException;
import com.a105.zani.attention.application.exception.NotPromptStudentException;
import com.a105.zani.attention.application.exception.StalePromptException;
import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.application.port.AttentionStatePort;
import com.a105.zani.attention.domain.model.CheckPrompt;
import com.a105.zani.attention.domain.model.PromptAnswer;
import com.a105.zani.attention.domain.repository.CheckPromptRepository;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantResult;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 학생이 프롬프트에 낸 답을 기록하고, 집계가 곧바로 읽을 수 있게 현재 상태에 반영한다.
 *
 * <p>DB가 진실의 원천이다. 답은 수업 후 리포트의 근거이기도 하므로 Redis가 죽어도 저장은 끝까지 마치고, 코칭용 상태 갱신만 건너뛴다(티켓 예외 규약).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordPromptResponseService implements RecordPromptResponseUseCase {

    /** 응답이 확정한 상태를 현재 상태로 유지하는 기간. 판정 이벤트 쪽과 같은 값을 쓴다. */
    private static final Duration CURRENT_STATE_TTL = Duration.ofSeconds(30);

    /** 코칭 트리거 관찰 창(확정 문서 §4의 5분 슬라이딩 윈도우). */
    private static final Duration SIGNIFICANT_WINDOW = Duration.ofMinutes(5);

    /**
     * 답을 받아 줄 수 있는 프롬프트의 최대 나이. 이보다 오래되면 409 로 거절한다.
     *
     * <p>넉넉하게 잡는다. 늦게 도착한 답도 수업 후 리포트의 근거로는 여전히 쓸모가 있어서, 빡빡하게 막으면 기록 자체를 잃는다. 관찰 창(5분)과 우연히 같은 값이지만 목적이 다르므로 따로 정의한다.
     * 한쪽을 바꿀 때 다른 쪽이 함께 끌려가면 안 된다.
     */
    private static final Duration ANSWER_ACCEPTANCE_WINDOW = Duration.ofMinutes(5);

    /**
     * 답을 <b>살아 있는 신호</b>로 볼 수 있는 최대 나이. 프롬프트 수명 30초에 재시도·시계 오차를 더한 값이다.
     *
     * <p>이보다 오래된 답은 DB 에는 남기되 코칭 상태에는 반영하지 않는다. 4분 전 답을 지금 상태로 찍으면 5분 창이 새로 연장돼, 지나간 신호가 트리거를 계속 끌고 간다.
     */
    private static final Duration COACHING_FRESHNESS = Duration.ofSeconds(90);

    /** 클라이언트 시계가 조금 빠른 것은 받아 준다. 이보다 미래면 시간선이 깨진 것으로 본다. */
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(1);

    /** 분모 제외 표시의 최소 보관 기간. 이미 만료 시각을 지난 세션이라도 키를 심을 수 있어야 한다. */
    private static final Duration MINIMUM_EXCLUSION_TTL = Duration.ofMinutes(1);

    /** 멱등 표시 보관 기간. 답을 받아 주는 창보다 넉넉히 잡아 늦은 재시도도 걸러낸다. */
    private static final Duration PROMPT_MARKER_TTL = Duration.ofMinutes(10);

    /** 프롬프트에 답한 학생의 신호 품질. 상호작용으로 확인된 것이라 프레임 판정과 달리 최댓값이다. */
    private static final double MEASURED_BY_INTERACTION = 1.0d;

    private final ResolveSessionParticipantUseCase resolveSessionParticipantUseCase;
    private final CheckPromptRepository checkPromptRepository;
    private final AttentionStatePort attentionStatePort;
    private final Clock clock;

    @Override
    @Transactional
    public RecordPromptResponseResult record(RecordPromptResponseCommand command) {
        ResolveSessionParticipantResult participant = resolveSessionParticipantUseCase.resolve(
                new ResolveSessionParticipantQuery(command.sessionId(), command.userId()));

        // 프롬프트는 학생에게만 뜬다. 강사 응답이 섞이면 집계 분자에만 끼어 비율이 부풀어 오른다.
        if (participant.role() != SessionParticipantRole.STUDENT) {
            throw new NotPromptStudentException();
        }
        if (!command.kind().allows(command.answer())) {
            throw new MismatchedPromptAnswerException();
        }
        validateTimelineConsistency(participant.sessionStartedAt(), command);

        long sessionId = command.sessionId();
        long participantId = participant.participantId();
        long shownOffsetMs = offsetMs(participant.sessionStartedAt(), command.shownAt());

        // 중복은 만료보다 먼저 본다. 이미 기록된 답의 늦은 재시도에 409 를 주면, 성공한 요청을 실패로 알리는 셈이다.
        if (alreadyAnswered(sessionId, participantId, command, shownOffsetMs)) {
            log.debug("이미 답이 있는 프롬프트입니다. sessionId={}, promptId={}", sessionId, command.promptId());
            return RecordPromptResponseResult.alreadyRecorded();
        }
        // 표시만 남고 저장이 되돌아가면, 재시도가 "이미 처리했다"는 거짓 성공을 받고 답이 영영 사라진다.
        // 아래 만료 거절도 되돌리기 대상이라 표시를 심은 직후에 등록한다.
        clearMarkerUnlessCommitted(sessionId, participantId, command.promptId());

        try {
            rejectIfTooOldToAnswer(command);
            checkPromptRepository.save(CheckPrompt.respond(
                    sessionId,
                    participantId,
                    command.kind(),
                    command.answer(),
                    shownOffsetMs,
                    offsetMs(participant.sessionStartedAt(), command.respondedAt())));
        } catch (RuntimeException exception) {
            // 트랜잭션 동기화가 없는 경로에서는 위의 등록이 동작하지 않으므로 여기서 직접 되돌린다.
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                clearMarkerQuietly(sessionId, participantId, command.promptId());
            }
            throw exception;
        }

        reflectOnCoachingStateAfterCommit(
                sessionId, participantId, command, remainingSessionTime(participant.sessionExpiresAt()));
        return RecordPromptResponseResult.recorded();
    }

    /**
     * 이 프롬프트에 이미 답이 있는지.
     *
     * <p><b>두 겹을 매번 다 본다.</b> Redis 의 원자적 표시는 동시에 들어온 재시도를 하나만 통과시키고(서버에는 프롬프트 표시 시점의 행이 없어 DB 조회만으로는 둘 다 통과한다), DB 조회는
     * <b>표시는 없는데 행은 있는</b> 경우를 잡는다. Redis 가 잠시 죽은 동안 저장된 답이 그렇다 — 그때는 표시를 못 남기고 행만 생기므로, 복구 뒤 재시도가 표시 검사만 통과해 같은 답을 두 번
     * 기록하게 된다.
     *
     * <p>프롬프트 응답은 학생당 몇 분에 한 번이라, 조회 한 번을 아끼는 것보다 이 자가 복구가 값지다. 저장소 장애를 이유로 요청을 거절하지도 않는다 — 답은 반드시 남아야 한다.
     */
    private boolean alreadyAnswered(
            long sessionId, long participantId, RecordPromptResponseCommand command, long shownOffsetMs) {
        try {
            if (!attentionStatePort.registerEvent(
                    sessionId, participantId, promptMarker(command.promptId()), PROMPT_MARKER_TTL)) {
                return true;
            }
        } catch (AttentionStateUnavailableException unavailable) {
            log.warn("멱등 표시를 쓰지 못해 DB 조회로 중복을 판단합니다. sessionId={}", sessionId, unavailable);
        }
        return checkPromptRepository
                .findByParticipantAndKindAndShownOffset(sessionId, participantId, command.kind(), shownOffsetMs)
                .isPresent();
    }

    /** 커밋되지 않고 끝나면 멱등 표시를 지운다. 되돌리기 실패는 삼킨다 — 표시는 TTL 로도 사라진다. */
    private void clearMarkerUnlessCommitted(long sessionId, long participantId, String promptId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    clearMarkerQuietly(sessionId, participantId, promptId);
                }
            }
        });
    }

    private void clearMarkerQuietly(long sessionId, long participantId, String promptId) {
        try {
            attentionStatePort.clearEvent(sessionId, participantId, promptMarker(promptId));
        } catch (RuntimeException exception) {
            log.warn("멱등 표시를 되돌리지 못했습니다. TTL 로 사라집니다. sessionId={}, promptId={}", sessionId, promptId, exception);
        }
    }

    /**
     * 커밋이 끝난 뒤 코칭용 Redis 상태에 반영한다.
     *
     * <p>트랜잭션 안에서 Redis를 만지면 DB 커넥션을 네트워크 왕복 동안 붙들고, 커밋이 실패해도 분모 제외 표시만 남는다. presence가 같은 이유로 heartbeat 경로를 트랜잭션으로 감싸지
     * 않는다.
     */
    private void reflectOnCoachingStateAfterCommit(
            long sessionId, long participantId, RecordPromptResponseCommand command, Duration exclusionTtl) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            reflectOnCoachingState(sessionId, participantId, command, exclusionTtl);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reflectOnCoachingState(sessionId, participantId, command, exclusionTtl);
            }
        });
    }

    /** 저장소가 죽어도 예외를 밖으로 내보내지 않는다. 답은 이미 커밋됐고, 코칭만 잠시 멈춘다. */
    private void reflectOnCoachingState(
            long sessionId, long participantId, RecordPromptResponseCommand command, Duration exclusionTtl) {
        try {
            applyDenominatorChange(sessionId, participantId, command.answer(), exclusionTtl);
            if (isTooOldToSteerCoaching(command)) {
                // 지나간 답을 지금 상태로 찍으면 관찰 창이 새로 연장돼 옛 신호가 트리거를 계속 끌고 간다.
                log.debug("코칭에 반영하기에는 오래된 답입니다. sessionId={}, promptId={}", sessionId, command.promptId());
                return;
            }
            // 프롬프트가 닫히면 브라우저 카운터가 0이 된다(§5). 서버 사본만 3 이상으로 남으면
            // 다음 관측 한 건이 곧바로 상태를 다시 확정하고 분자 마커를 새로 건다.
            // 오래된 답에는 하지 않는다 — 그 사이 새로 쌓인 연속 판정까지 지운다.
            attentionStatePort.resetRuns(sessionId, participantId);
            command.kind().confirmedState(command.answer()).ifPresent(state -> {
                // 프롬프트에 답했다는 것은 학생이 화면 앞에 있다는 뜻이라, 신호 품질은 최댓값으로 본다.
                // 이 값은 "측정 가능 학생 비율"에 쓰이며 프레임 판정이 아니라 상호작용에서 나온 확신이다.
                attentionStatePort.recordCurrentState(
                        sessionId,
                        participantId,
                        new AttentionSnapshot(state, MEASURED_BY_INTERACTION, clock.instant()),
                        CURRENT_STATE_TTL);
                if (state.isSignificant()) {
                    attentionStatePort.markSignificant(sessionId, participantId, state, SIGNIFICANT_WINDOW);
                }
            });
        } catch (RuntimeException exception) {
            log.warn(
                    "프롬프트 응답을 코칭 상태에 반영하지 못했습니다. 답은 저장됐고 코칭만 멈춥니다. sessionId={}, promptId={}",
                    sessionId,
                    command.promptId(),
                    exception);
        }
    }

    /** 카메라를 켤 수 없다고 답하면 분모에서 빼고, 켤 수 있다고 답하면 되돌린다(확정 문서 §8). */
    private void applyDenominatorChange(
            long sessionId, long participantId, PromptAnswer answer, Duration exclusionTtl) {
        if (answer.excludesFromDenominator()) {
            // 카메라를 켤 수 없는 학생을 분모에 남기면, 무엇을 하든 비율이 낮아져 어려움을 겪는 학생들이 가려진다.
            attentionStatePort.excludeFromDenominator(sessionId, participantId, exclusionTtl);
        } else if (answer == PromptAnswer.CAMERA_AVAILABLE) {
            attentionStatePort.includeInDenominator(sessionId, participantId);
        }
    }

    /**
     * 세션이 자동 종료될 때까지 남은 시간.
     *
     * <p>분모 제외는 그 수업 동안만 뜻이 있다(확정 문서 §8). 수업 최대 길이를 상수로 베껴 두면 세션 도메인이 값을 바꿀 때 조용히 어긋나므로, 세션이 알려 준 만료 시각에서 그때그때 계산한다.
     */
    private Duration remainingSessionTime(Instant sessionExpiresAt) {
        Duration remaining = Duration.between(clock.instant(), sessionExpiresAt);
        return remaining.compareTo(MINIMUM_EXCLUSION_TTL) > 0 ? remaining : MINIMUM_EXCLUSION_TTL;
    }

    /** 답이 코칭 신호로 쓰기에는 너무 오래됐는지. */
    private boolean isTooOldToSteerCoaching(RecordPromptResponseCommand command) {
        return command.shownAt().isBefore(clock.instant().minus(COACHING_FRESHNESS));
    }

    /**
     * 표시·응답 시각이 수업 시간선과 맞는지 본다. 부작용이 없으므로 가장 먼저 거른다.
     *
     * <p>어긋난 값을 0으로 눌러 담으면 서로 다른 프롬프트가 같은 오프셋을 갖게 되고, 두 번째 답이 중복으로 잡혀 조용히 사라진다. 눌러 담는 대신 거절한다. 미래 쪽도 막는다 — 시계가 하루 빠른
     * 브라우저는 세 시간짜리 수업에 하루치 오프셋을 남기고, 극단적인 값은 오프셋 계산에서 넘쳐 500 이 된다.
     */
    private void validateTimelineConsistency(Instant sessionStartedAt, RecordPromptResponseCommand command) {
        Instant futureLimit = clock.instant().plus(FUTURE_TOLERANCE);
        if (command.shownAt().isBefore(sessionStartedAt)
                || command.respondedAt().isBefore(command.shownAt())
                || command.respondedAt().isAfter(futureLimit)) {
            throw new InvalidPromptTimelineException();
        }
    }

    /**
     * 답을 받아 주기에 너무 오래된 프롬프트면 거절한다.
     *
     * <p><b>중복 검사 뒤에</b> 둔다. 이미 기록된 답의 재시도가 늦게 도착했을 때 409 를 돌려주면, 실제로는 저장에 성공한 요청을 실패로 알리게 된다. 재시도는 200 duplicate 로
     * 사실대로 답하고, 처음 보는 답만 나이를 따진다.
     */
    private void rejectIfTooOldToAnswer(RecordPromptResponseCommand command) {
        if (command.shownAt().isBefore(clock.instant().minus(ANSWER_ACCEPTANCE_WINDOW))) {
            throw new StalePromptException();
        }
    }

    /** 수업 시작으로부터 흐른 밀리초. 시간선 검증을 통과했으므로 음수가 될 수 없다. */
    private long offsetMs(Instant sessionStartedAt, Instant at) {
        return Duration.between(sessionStartedAt, at).toMillis();
    }

    /** 판정 이벤트와 키 공간이 섞이지 않도록 접두사를 붙인다. */
    private String promptMarker(String promptId) {
        return "prompt:" + promptId;
    }
}
