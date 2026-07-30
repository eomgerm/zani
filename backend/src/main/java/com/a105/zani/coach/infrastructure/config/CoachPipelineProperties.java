package com.a105.zani.coach.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 팁 생성 파이프라인의 실행 상한. (S15P11A105-204)
 *
 * <p>상한을 두는 이유: 전사 20초 + LLM 6초로 한 건이 최대 26초 스레드를 점유한다. 무제한 풀이면 동시 수업이 늘 때 스레드가 그만큼 늘고, 무제한 큐면 밀린 작업이 쌓여 강사가 쿨타임 내내 생성
 * 중만 보게 된다.
 *
 * @param threadCount 동시 실행 수. 오디오 버퍼가 동시에 들 수 있는 세션이 8개라 실행 2 + 대기 6 으로 상한을 맞춘다
 * @param queueCapacity 대기 큐 크기. 넘치면 그 트리거는 즉시 실패로 끝낸다
 * @param maxTriggerDelay 트리거 시각부터 작업 시작까지 허용하는 지연. 이보다 밀렸으면 전사·GMS 를 호출하지 않는다 — 지금 오디오를 떠도 트리거 당시가 아닌 발화가 섞이기 때문이다.
 *     10초는 attention 판정 한 주기이고 300초 창의 약 3.3% 다
 */
@ConfigurationProperties(prefix = "coach.pipeline")
public record CoachPipelineProperties(Integer threadCount, Integer queueCapacity, Duration maxTriggerDelay) {

    public CoachPipelineProperties {
        if (threadCount == null || threadCount <= 0) {
            threadCount = 2;
        }
        if (queueCapacity == null || queueCapacity <= 0) {
            queueCapacity = 6;
        }
        if (maxTriggerDelay == null || maxTriggerDelay.isNegative() || maxTriggerDelay.isZero()) {
            maxTriggerDelay = Duration.ofSeconds(10);
        }
    }
}
