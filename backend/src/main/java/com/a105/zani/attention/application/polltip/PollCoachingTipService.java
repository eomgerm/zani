package com.a105.zani.attention.application.polltip;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.a105.zani.attention.application.getcoachingsignals.GetCoachingSignalsQuery;
import com.a105.zani.attention.application.getcoachingsignals.GetCoachingSignalsUseCase;
import com.a105.zani.attention.application.port.CoachingOutcome;
import com.a105.zani.attention.application.port.CoachingTipPipelinePort;
import com.a105.zani.attention.application.port.CoachingTipRequest;
import com.a105.zani.attention.application.port.CoachingTriggerStatePort;
import com.a105.zani.attention.domain.model.CoachingSignalSummary;
import com.a105.zani.attention.domain.model.CoachingTriggerDecision;
import com.a105.zani.attention.domain.model.CoachingTriggerPolicy;
import com.a105.zani.audioclip.application.port.InstructorAudioBufferPort;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantResult;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 강사의 팁 폴링을 처리하면서 코칭 트리거를 판정한다(확정 문서 §7).
 *
 * <p>이 유스케이스는 전사와 팁 생성을 <b>기다리지 않는다</b>. 트리거를 열고 파이프라인에 넘긴 뒤 곧바로 응답하며, 완성된 팁은 다음 폴링에서 실려 나간다. 20초 전사와 6초 LLM 을 응답 안에서
 * 기다리면 강사의 10초 주기가 26초 동안 막혀 그 사이 팁도, 다음 판정도 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PollCoachingTipService implements PollCoachingTipUseCase {

    private final ResolveSessionParticipantUseCase resolveSessionParticipantUseCase;
    private final GetCoachingSignalsUseCase getCoachingSignalsUseCase;
    private final InstructorAudioBufferPort instructorAudioBufferPort;
    private final CoachingTriggerStatePort coachingTriggerStatePort;
    private final CoachingTriggerPolicy coachingTriggerPolicy;
    private final Clock clock;

    /**
     * 팁 생성 파이프라인. 구현은 coach 오케스트레이션(티켓 204)이 갖는다.
     *
     * <p>아직 붙지 않은 동안에도 트리거 판정과 폴링 계약은 동작해야 하므로 필수 의존으로 두지 않는다. 없으면 기동이 실패하는 쪽이 더 나쁘다 — 코칭만 조용해지면 되는데 수업 전체가 뜨지 않는다.
     */
    private final ObjectProvider<CoachingTipPipelinePort> coachingTipPipeline;

    @Override
    public PollCoachingTipResult poll(PollCoachingTipCommand command) {
        ResolveSessionParticipantResult participant = resolveSessionParticipantUseCase.resolve(
                new ResolveSessionParticipantQuery(command.sessionId(), command.userId()));

        // 팁은 강사만 받는다. 학생에게 집단 통계를 보여 주면 익명 집계를 지켜 온 의미가 사라진다.
        if (participant.role() != SessionParticipantRole.INSTRUCTOR) {
            throw new NotSessionInstructorException();
        }
        long sessionId = command.sessionId();

        // 열린 트리거가 있으면 그대로 돌려준다. 키의 생존이 곧 쿨타임이라 이 한 번의 읽기가 쿨타임 확인까지 겸한다 —
        // 쿨타임 10분 동안 분모 조회(DB 명부 + presence)와 유의 표시 조회를 60번 되풀이하지 않는다.
        Optional<CoachingOutcome> open = coachingTriggerStatePort.openOutcome(sessionId);
        if (open.isPresent()) {
            return PollCoachingTipResult.of(open.get());
        }

        CoachingSignalSummary summary = getCoachingSignalsUseCase
                .get(new GetCoachingSignalsQuery(sessionId))
                .summary();
        Duration availableAudio = Duration.ofMillis(instructorAudioBufferPort.availableMs(sessionId));

        CoachingTriggerDecision decision = coachingTriggerPolicy.decide(summary, availableAudio);
        if (!decision.triggered()) {
            log.debug(
                    "코칭 트리거를 열지 않았습니다. sessionId={}, 사유={}, 분모={}, 분자={}",
                    sessionId,
                    decision,
                    summary.denominator(),
                    summary.numerator());
            return PollCoachingTipResult.none();
        }

        String triggerId = UUID.randomUUID().toString();
        if (!coachingTriggerStatePort.openTrigger(sessionId, triggerId, coachingTriggerPolicy.cooldown())) {
            // 강사가 창을 두 개 열어 둔 경우처럼 다른 요청이 먼저 열었다. 새 트리거를 만들지 않고 그쪽 결과를 따른다.
            log.debug("이미 열린 트리거가 있어 새로 만들지 않았습니다. sessionId={}", sessionId);
            return coachingTriggerStatePort
                    .openOutcome(sessionId)
                    .map(PollCoachingTipResult::of)
                    .orElseGet(PollCoachingTipResult::none);
        }

        startTipGeneration(sessionId, triggerId, summary);
        return PollCoachingTipResult.of(CoachingOutcome.pending(triggerId));
    }

    private void startTipGeneration(long sessionId, String triggerId, CoachingSignalSummary summary) {
        CoachingTipPipelinePort pipeline = coachingTipPipeline.getIfAvailable();
        if (pipeline == null) {
            // 트리거는 이미 열려 쿨타임이 시작됐다. 여기서 되돌리면 파이프라인이 없는 동안 10초마다 트리거를 새로 연다.
            log.warn("팁 생성 파이프라인이 없어 트리거만 열었습니다. sessionId={}, triggerId={}", sessionId, triggerId);
            return;
        }
        try {
            pipeline.start(CoachingTipRequest.of(
                    sessionId,
                    triggerId,
                    clock.instant(),
                    summary,
                    coachingTriggerStatePort.previousTip(sessionId).orElse(null)));
        } catch (RuntimeException exception) {
            // 포트는 즉시 반환을 요구하지만 강제하지는 못한다. 구현이 동기로 던지면 강사 폴링이 500 이 되고, 티켓 76 이 그것을
            // 연속 실패로 세어 코칭 비활성을 띄운다 — "전사·LLM 실패는 수업을 막지 않는다"에 어긋난다.
            // 트리거는 되돌리지 않는다. 쿨타임이 이미 열려 있어 재시도가 몰리지 않고, 다음 트리거가 10분 뒤에 다시 시도한다.
            log.warn("팁 생성 파이프라인을 시작하지 못했습니다. sessionId={}, triggerId={}", sessionId, triggerId, exception);
        }
    }
}
