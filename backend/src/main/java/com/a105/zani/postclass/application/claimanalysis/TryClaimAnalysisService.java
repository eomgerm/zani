package com.a105.zani.postclass.application.claimanalysis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.application.port.PipelineJobState;
import com.a105.zani.postclass.domain.model.PipelineStatus;

/**
 * 분석 실행권 선점.
 *
 * <p>잠금 읽기와 임대 갱신이 같은 트랜잭션에 있어야 두 워커가 같은 세션을 동시에 잡지 못한다. 잠금 읽기는 스냅숏이 아니라 최신 커밋본을 보므로, 뒤에 온 쪽은 앞선 선점이 커밋한 임대 만료 시각을 보고
 * 물러난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TryClaimAnalysisService implements TryClaimAnalysisUseCase {

    private final PipelineJobPort pipelineJobPort;
    private final Clock clock;

    @Override
    @Transactional
    public boolean tryClaim(Long sessionId, Duration lease) {
        PipelineJobState state = pipelineJobPort.findForUpdate(sessionId).orElse(null);
        if (state == null) {
            // 후보 조회와 이 호출 사이에 작업이 사라졌다. 예외로 올리면 스케줄 주기가 통째로 끊기므로 조용히 넘긴다.
            log.debug("분석 대상 작업이 없습니다. sessionId={}", sessionId);
            return false;
        }
        if (state.status() != PipelineStatus.ANALYZING) {
            // 이미 다음 단계로 넘어갔거나 아직 전사 중이다.
            log.debug("분석 단계가 아닙니다. sessionId={}, status={}", sessionId, state.status());
            return false;
        }
        Instant now = clock.instant();
        if (state.nextAttemptAt() != null && state.nextAttemptAt().isAfter(now)) {
            // 다른 실행이 임대를 쥐고 있거나 재시도 대기 중이다. 둘 다 지금 건드릴 대상이 아니다.
            log.debug("분석 실행권을 얻지 못했습니다. sessionId={}, nextAttemptAt={}", sessionId, state.nextAttemptAt());
            return false;
        }
        pipelineJobPort.claimAnalysis(sessionId, now.plus(lease), now);
        log.info("분석 실행권을 잡았습니다. sessionId={}, attemptCount={}, lease={}", sessionId, state.attemptCount(), lease);
        return true;
    }
}
