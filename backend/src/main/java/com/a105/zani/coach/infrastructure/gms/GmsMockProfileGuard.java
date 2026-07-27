package com.a105.zani.coach.infrastructure.gms;

import java.util.Arrays;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 운영(prod) 프로파일에서 GMS mock 모드가 켜지지 않도록 강제한다. 위반 시 애플리케이션 기동을 실패시킨다. (S15P11A105-202) */
@Component
public class GmsMockProfileGuard {

    public GmsMockProfileGuard(Environment environment, GmsProperties properties) {
        boolean prodProfile = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (prodProfile && properties.mockEnabled()) {
            throw new IllegalStateException(
                    "gms.mock-enabled must be false in the prod profile; refusing to start with GMS mock enabled");
        }
    }
}
