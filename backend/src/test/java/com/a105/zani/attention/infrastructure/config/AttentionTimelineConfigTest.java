package com.a105.zani.attention.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.a105.zani.attention.domain.model.timeline.TimelinePolicy;

import static org.assertj.core.api.Assertions.assertThat;

class AttentionTimelineConfigTest {

    /**
     * {@code application.yaml} 이 {@link TimelinePolicy#defaults()} 와 같은 값을 다시 적고 있다. 조정 가능한 값이라는 사실을 설정 파일에서 읽을 수 있게
     * 하려고 드러낸 것이므로, 두 곳이 갈라지면 설정을 건드리지 않은 배포가 코드와 다른 판단을 한다. 그 어긋남을 여기서 잡는다.
     */
    @Test
    @DisplayName("설정 파일에 적은 임계값은 도메인 기본값과 같다")
    void the_yaml_thresholds_match_the_domain_defaults() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(AttentionTimelineConfig.class)
                .run(context ->
                        assertThat(context.getBean(TimelinePolicy.class)).isEqualTo(TimelinePolicy.defaults()));
    }
}
