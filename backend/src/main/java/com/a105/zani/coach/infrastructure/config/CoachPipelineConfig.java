package com.a105.zani.coach.infrastructure.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.a105.zani.coach.application.port.CoachingTipSettings;

/**
 * 팁 생성 전용 executor 와 코칭 설정 등록. (S15P11A105-204)
 *
 * <p>{@link CoachTipProperties} 를 여기서 등록한다. 실제 GMS 어댑터에 붙여 두면 그 빈이 {@code gms.mock-enabled=false} 조건부라 mock 모드에서는 설정이
 * 바인딩되지 않고, 파이프라인이 신뢰도 하한을 읽지 못한다.
 *
 * <p>설정 두 개를 읽어 {@link CoachingTipSettings} 로 바꿔 주입한다. application 계층이 스프링 설정 타입을 알 필요가 없다 — audioclip 의
 * {@code AudioClipCaptureSettings}, session 의 {@code MediaServerCredentials} 와 같은 방식이다.
 *
 * <p>{@code @Async} 로 감추지 않고 executor 를 직접 주입해 쓴다. 거절을 호출 지점에서 잡아 그 트리거를 실패로 끝내야 하는데, 애노테이션 뒤로 숨기면 예외가 프록시 안에서 사라져 강사가
 * 쿨타임 내내 "생성 중" 만 보게 된다.
 *
 * <p>{@code @Scheduled} 풀(무음 패딩·outbox 릴레이·세션 만료)과 섞지 않는다. 한 건이 최대 26초 스레드를 점유해 100ms 주기인 무음 패딩을 굶긴다.
 */
@Configuration
@EnableConfigurationProperties({CoachPipelineProperties.class, CoachTipProperties.class})
public class CoachPipelineConfig {

    @Bean
    public CoachingTipSettings coachingTipSettings(
            CoachTipProperties tipProperties, CoachPipelineProperties pipelineProperties) {
        return new CoachingTipSettings(
                tipProperties.minConfidence(),
                tipProperties.transcriptTailChars(),
                pipelineProperties.maxTriggerDelay());
    }

    @Bean
    public Executor coachingTipExecutor(CoachPipelineProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.threadCount());
        executor.setMaxPoolSize(properties.threadCount());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("coaching-tip-");
        // 큐가 차면 호출 스레드에서 실행(CallerRuns)하지 않는다. 그러면 강사 폴링 응답이 26초 막힌다.
        // 거절을 예외로 받아 그 트리거를 즉시 실패로 끝내는 편이 낫다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 종료 시 진행 중인 전사를 기다린다. 끊으면 열린 트리거가 결과 없이 남아 쿨타임 10분을 태운다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
