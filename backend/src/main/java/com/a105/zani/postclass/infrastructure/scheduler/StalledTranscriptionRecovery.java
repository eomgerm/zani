package com.a105.zani.postclass.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.a105.zani.postclass.application.recoverstalledtranscriptions.RecoverStalledTranscriptionsUseCase;

/**
 * 기동 직후 한 번, 워커 없이 남은 전사 작업을 되살린다(S15P11A105-247).
 *
 * <p><b>기동 시점이어야 하는 이유.</b> 이 인스턴스의 워커는 아직 하나도 없으므로 "{@code TRANSCRIBING} 인데 재시도 대기가 없는" 행은 모두 지난 프로세스가 남긴 것이다. 주기적으로
 * 돌리면 살아서 도는 세션까지 되살려 같은 세션이 겹쳐 돈다 — 청크 fencing 이 결과를 지켜 주긴 하지만 GMS 호출이 낭비된다.
 *
 * <p>{@code @Scheduled} 가 아니라 {@link ApplicationReadyEvent} 를 쓴다. 스케줄 주기와 무관한 일회성 작업이고, 첫 디스패치 주기(기본 10초)보다 먼저 끝나야 그
 * 주기에서 바로 집힌다.
 *
 * <p><b>다중 인스턴스에서는 좁혀야 한다.</b> 지금은 단일 인스턴스 전제다. 인스턴스가 둘이면 한쪽 재기동이 다른 쪽에서 도는 세션을 되살릴 수 있다 — 그때도 청크 선점과 fencing 이 중복 쓰기를
 * 막고 조립이 멱등이라 결과는 지켜지지만, 세션 단위 lease 를 두는 편이 낫다. 체크포인트 커밋에 적어 둔 다중 인스턴스 제한과 같은 항목이다.
 *
 * <p>디스패치 스위치를 끄면 이 복구도 하지 않는다. 배경 실행이 없는 환경(테스트)에서 남은 행을 건드릴 이유가 없다.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "postclass.transcription",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class StalledTranscriptionRecovery {

    private final RecoverStalledTranscriptionsUseCase recoverStalledTranscriptionsUseCase;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        try {
            recoverStalledTranscriptionsUseCase.recover();
        } catch (RuntimeException exception) {
            // 복구에 실패해도 기동은 계속해야 한다. 남은 작업은 8시간 마감 경보가 결국 잡는다.
            log.error("Could not requeue transcription jobs left behind by a stopped worker", exception);
        }
    }
}
