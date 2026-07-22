package com.a105.zani.session.application;

import com.a105.zani.session.domain.InviteCodeGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SessionApplicationConfig {

    @Bean
    public InviteCodeGenerator inviteCodeGenerator() {
        return new InviteCodeGenerator();
    }
}
