package com.a105.zani.session.application;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.a105.zani.session.domain.InviteCodeGenerator;

@Configuration
public class SessionApplicationConfig {

    @Bean
    public InviteCodeGenerator inviteCodeGenerator() {
        return new InviteCodeGenerator();
    }

    /** presence 유예 만료 판단 등 시간 기반 로직의 기준 시계. 테스트에서 고정 시계로 대체할 수 있도록 빈으로 제공한다. */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
