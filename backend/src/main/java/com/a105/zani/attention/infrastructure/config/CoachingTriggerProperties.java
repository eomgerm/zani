package com.a105.zani.attention.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 코칭 트리거 임계값 설정(확정 문서 §7).
 *
 * @param threshold 유의 학생 비율의 하한. 이 값 이상이면 트리거한다
 * @param cooldown 트리거를 연 시각부터 다음 트리거를 막는 기간. 팁 유형과 무관한 단일 글로벌 쿨타임이다
 * @param minimumAudio 확보된 강사 오디오가 이보다 짧으면 트리거하지 않는다. 이 경우에만 쿨타임을 시작하지 않는다
 */
@ConfigurationProperties(prefix = "coaching-trigger")
public record CoachingTriggerProperties(Double threshold, Duration cooldown, Duration minimumAudio) {

    public CoachingTriggerProperties {
        if (threshold == null) {
            threshold = 0.30;
        }
        if (cooldown == null) {
            cooldown = Duration.ofMinutes(10);
        }
        if (minimumAudio == null) {
            minimumAudio = Duration.ofMinutes(1);
        }
    }
}
