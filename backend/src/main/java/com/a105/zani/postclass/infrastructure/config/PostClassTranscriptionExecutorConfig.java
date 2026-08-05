package com.a105.zani.postclass.infrastructure.config;

import java.util.concurrent.Executor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 사후 전사 전용 실행기 두 개(S15P11A105-247).
 *
 * <p><b>왜 공유 {@code TaskScheduler} 를 쓰지 않는가.</b> {@code spring.task.scheduling.pool.size} 는 7 이고 이미 6개 작업이 그 풀을 나눠
 * 쓴다(무음 패딩·outbox 릴레이·코칭 이력 재시도·세션 만료·메모 비활성 확정·알림 릴레이). 전사는 트랙 하나당 청크 여러 개를 GMS 에 올리며 한 세션이 수십 분을 점유할 수 있다. 그 작업을 공유
 * 풀에서 돌리면 100ms 주기인 강사 오디오 무음 패딩이 굶는다 — 라이브 수업이 사후 처리 때문에 흔들린다. 그래서 {@code @Scheduled} 는 디스패치만 하고 즉시 반환하고, 실제 작업은 여기 두
 * 실행기에서 돈다.
 *
 * <p><b>왜 실행기를 둘로 나누는가.</b> 하나로 합치면 교착이 생긴다. 오케스트레이션 작업은 청크 결과를 기다리는데, 기다리는 동안 스레드를 점유한다. 크기 2 인 한 실행기에 오케스트레이션 둘이 올라가면
 * 두 스레드가 모두 대기 상태가 되고 정작 청크 작업은 큐에서 실행될 기회를 얻지 못한다. 기다리는 쪽과 일하는 쪽을 분리해야 한다.
 *
 * <pre>
 * 공유 TaskScheduler(7)  → 짧은 디스패치만
 * orchestration(1)       → 세션 하나씩. 분할·체크포인트·조립·임시파일 정리
 * gms(concurrency)       → 청크 업로드만
 * </pre>
 *
 * <p><b>왜 오케스트레이션이 1 인가.</b> 세션 간에도 직렬로 처리한다는 뜻이다. 티켓의 "세션당 직렬" 보다 강한 제약이지만 의도한 것이다 — 트랙 하나를 분할·처리·정리한 뒤 다음으로 넘어가므로
 * {@code /tmp} 피크가 트랙 하나 크기(3시간이면 약 170MB)로 묶인다. 여러 세션을 동시에 열면 그 상한이 세션 수만큼 곱해진다.
 *
 * <p>큐 용량을 0 으로 두고 core·max 를 함께 1 로 고정한다. core 만 1 로 두면 max 까지 스레드가 늘어나 오케스트레이션이 둘 동시에 돈다. 실행 중 제출은
 * {@code RejectedExecutionException} 이 되고, 호출자가 그것을 잡아 이번 주기를 조용히 건너뛴다 — 포화는 파이프라인 실패가 아니다.
 */
@Configuration
@EnableConfigurationProperties(PostClassTranscriptionProperties.class)
public class PostClassTranscriptionExecutorConfig {

    public static final String ORCHESTRATION_EXECUTOR = "postclassOrchestrationExecutor";
    public static final String GMS_EXECUTOR = "postclassGmsExecutor";

    @Bean(ORCHESTRATION_EXECUTOR)
    public Executor postclassOrchestrationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        // 0 이어야 실행 중 제출이 큐에 쌓이지 않고 즉시 거부된다. 쌓이면 같은 세션 작업이 줄을 서고
        // 스케줄 주기마다 하나씩 더해져 끝없이 늘어난다.
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("postclass-orch-");
        // 종료 시 진행 중인 전사를 중간에 끊지 않는다. 청크 체크포인트가 있어 재기동 후 이어갈 수 있지만,
        // 완료 직전에 끊기면 그 청크의 GMS 호출이 낭비된다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(GMS_EXECUTOR)
    public Executor postclassGmsExecutor(PostClassTranscriptionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.concurrency());
        executor.setMaxPoolSize(properties.concurrency());
        // 오케스트레이션이 in-flight 를 concurrency 개로 직접 제한하므로 큐는 그 여유분만 받는다.
        executor.setQueueCapacity(properties.concurrency());
        executor.setThreadNamePrefix("postclass-gms-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
