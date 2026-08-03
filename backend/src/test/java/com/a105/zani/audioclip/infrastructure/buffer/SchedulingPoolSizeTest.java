package com.a105.zani.audioclip.infrastructure.buffer;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 무음 패딩 틱이 다른 스케줄 작업에 굶지 않는지 지키는 회귀 테스트.
 *
 * <p>{@code spring.task.scheduling.pool.size} 기본값은 1이라, 설정을 지우면 모든 {@code @Scheduled} 가 스레드 하나를 공유한다. 그러면 녹화 outbox
 * 릴레이(한 번에 최대 20건, 건마다 LiveKit 호출이 최대 60초)가 도는 동안 100ms 주기인 {@link InstructorAudioSilenceScheduler} 가 수 분간 멈춘다. 패딩이 밀리면
 * 버퍼의 "최근 N초"가 벽시계와 어긋나 조용히 틀린 구간을 전사하게 된다.
 */
class SchedulingPoolSizeTest {

    /** 무음 패딩·outbox 릴레이·코칭 이력 재시도·세션 만료·메모 비활성 확정. 작업이 늘면 이 값과 설정을 함께 올려야 한다. */
    private static final int SCHEDULED_TASK_COUNT = 5;

    @Test
    @DisplayName("스케줄러 풀이 스케줄 작업 수 이상이라 무음 패딩이 블로킹 작업에 밀리지 않는다")
    void schedulingPoolHasAThreadPerScheduledTask() throws IOException {
        assertThat(poolSize())
                .as("spring.task.scheduling.pool.size in application.yaml")
                .isNotNull()
                .isGreaterThanOrEqualTo(SCHEDULED_TASK_COUNT);
    }

    private Integer poolSize() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        return sources.stream()
                .map(source -> source.getProperty("spring.task.scheduling.pool.size"))
                .filter(java.util.Objects::nonNull)
                .map(value -> Integer.valueOf(value.toString()))
                .findFirst()
                .orElse(null);
    }
}
