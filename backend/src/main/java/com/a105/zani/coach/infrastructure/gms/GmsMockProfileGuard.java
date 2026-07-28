package com.a105.zani.coach.infrastructure.gms;

import java.util.Arrays;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 운영(prod) 프로파일의 GMS 설정을 기동 시 검증한다. 위반하면 애플리케이션 기동을 실패시킨다. (S15P11A105-202)
 *
 * <ul>
 *   <li>mock 모드가 켜져 있으면 실패 — 운영에서 가짜 응답을 쓰면 안 된다.
 *   <li>실 호출 모드인데 API key 가 비어 있으면 실패 — 그대로 두면 헬스체크가 매번 401 로 조용히 실패해 코칭이 영구 비활성이 되고, 원인을 알아채기 어렵다.
 * </ul>
 */
@Component
public class GmsMockProfileGuard {

    public GmsMockProfileGuard(Environment environment, GmsProperties properties) {
        boolean prodProfile = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (!prodProfile) {
            return;
        }
        if (properties.mockEnabled()) {
            throw new IllegalStateException(
                    "gms.mock-enabled must be false in the prod profile; refusing to start with GMS mock enabled");
        }
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "gms.api-key must be set in the prod profile; refusing to start without a GMS API key");
        }
    }
}
