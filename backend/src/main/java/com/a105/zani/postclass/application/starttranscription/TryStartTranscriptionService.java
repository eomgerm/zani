package com.a105.zani.postclass.application.starttranscription;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.postclass.application.exception.IllegalPipelineTransitionException;
import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.application.port.PipelineJobState;
import com.a105.zani.postclass.domain.model.PipelineStateMachine;
import com.a105.zani.postclass.domain.model.PipelineStatus;

/**
 * 전사 실행권 선점.
 *
 * <p>전용 서비스가 필요한 이유는 기존 두 경로를 쓸 수 없기 때문이다.
 *
 * <ul>
 *   <li>{@code AdvancePipelineJobUseCase#advance(TRANSCRIBING)} — 이미 {@code TRANSCRIBING} 이면 아무것도 쓰지 않고 반환한다. 재시도 선점에
 *       쓰면 {@code next_attempt_at} 이 남아 실행 중에도 매 주기마다 다시 발견된다
 *   <li>{@link PipelineJobPort#updateStatus} — 시도 횟수를 0 으로 되돌린다. 재시도 선점에 쓰면 실패를 반복하는 단계가 상한에 걸리지 않고 영원히 재시도된다
 * </ul>
 *
 * <p>그래서 최초 시작과 재시도 선점을 나눠 처리한다. 최초는 단계를 옮기며 재시도 예산을 새로 받고, 재시도는 단계를 그대로 두고 대기만 푼다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TryStartTranscriptionService implements TryStartTranscriptionUseCase {

    private final PipelineJobPort pipelineJobPort;
    private final Clock clock;

    @Override
    @Transactional
    public boolean tryStart(Long sessionId) {
        PipelineJobState state = pipelineJobPort.findForUpdate(sessionId).orElse(null);
        if (state == null) {
            // 후보 조회와 이 호출 사이에 작업이 사라졌다. 예외로 올리면 스케줄 주기가 통째로 끊기므로 조용히 넘긴다.
            log.debug("전사 대상 작업이 없습니다. sessionId={}", sessionId);
            return false;
        }
        if (state.status() == PipelineStatus.QUEUED) {
            if (!PipelineStateMachine.canAdvance(PipelineStatus.QUEUED, PipelineStatus.TRANSCRIBING)) {
                throw new IllegalPipelineTransitionException();
            }
            pipelineJobPort.updateStatus(sessionId, PipelineStatus.TRANSCRIBING, clock.instant());
            log.info("전사를 시작합니다. sessionId={}", sessionId);
            return true;
        }
        if (state.status() == PipelineStatus.TRANSCRIBING && isRetryDue(state)) {
            // 단계를 옮기지 않는다. 실패한 것은 이 단계뿐이고 앞 단계까지 되돌리면 8시간 예산만 줄어든다.
            pipelineJobPort.clearRetryWait(sessionId, clock.instant());
            log.info("전사를 재시도합니다. sessionId={}, attemptCount={}", sessionId, state.attemptCount());
            return true;
        }
        // 실행 중인 TRANSCRIBING(대기 시각 없음)이거나 이미 다음 단계로 넘어간 작업이다.
        log.debug(
                "전사 실행권을 얻지 못했습니다. sessionId={}, status={}, nextAttemptAt={}",
                sessionId,
                state.status(),
                state.nextAttemptAt());
        return false;
    }

    private boolean isRetryDue(PipelineJobState state) {
        return state.nextAttemptAt() != null && !state.nextAttemptAt().isAfter(clock.instant());
    }
}
