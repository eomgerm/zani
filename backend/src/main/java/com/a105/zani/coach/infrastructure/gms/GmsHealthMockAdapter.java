package com.a105.zani.coach.infrastructure.gms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.a105.zani.coach.application.port.GmsHealthPort;

/**
 * mock 모드용 헬스체크. 실제 GMS 를 호출하지 않고 항상 가용으로 응답한다. 크레딧 소모 없이 개발·테스트하기 위한 것으로, 운영 프로파일에서는 {@link GmsMockProfileGuard} 가
 * mock 사용을 차단한다.
 */
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "true", matchIfMissing = true)
public class GmsHealthMockAdapter implements GmsHealthPort {

    private static final Logger log = LoggerFactory.getLogger(GmsHealthMockAdapter.class);

    @Override
    public boolean isGmsReachable() {
        log.info("GMS mock mode: reporting GMS reachable without a real call");
        return true;
    }
}
