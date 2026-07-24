package com.a105.zani.session.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.a105.zani.session.domain.InviteCodeGenerator;

@Configuration
public class SessionApplicationConfig {

    @Bean
    public InviteCodeGenerator inviteCodeGenerator() {
        return new InviteCodeGenerator();
    }
}
