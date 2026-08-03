package com.a105.zani.coach.infrastructure.gms;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.a105.zani.coach.application.port.TipConcept;
import com.a105.zani.coach.application.port.TipConceptPort;
import com.a105.zani.coach.application.port.TipConceptRequest;

/**
 * GMS 크레딧을 쓰지 않고 개발·테스트할 때 쓰는 팁 개념 어댑터. (S15P11A105-204)
 *
 * <p>203의 전사 스위치와 같은 {@code gms.mock-enabled} 로 갈라진다. 실제 어댑터와 조건이 겹치면 {@link TipConceptPort} 빈이 둘이라 기동이 실패하므로 조건이 서로
 * 배타적이어야 한다. 운영에서 이 어댑터가 뜨는 사고는 {@code GmsMockProfileGuard} 가 기동 시점에 막는다.
 */
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "true", matchIfMissing = true)
public class GmsTipConceptMockAdapter implements TipConceptPort {

    private static final Logger log = LoggerFactory.getLogger(GmsTipConceptMockAdapter.class);

    /** 조사를 붙이지 않은 명사구다 — 실제 모델에 요구하는 형식과 같게 두어 조사 처리까지 함께 검증된다. */
    static final String MOCK_CONCEPT = "예시로 든 핵심 개념";

    static final double MOCK_CONFIDENCE = 0.9;

    @Override
    public Optional<TipConcept> extract(TipConceptRequest request) {
        if (request == null
                || request.transcriptTail() == null
                || request.transcriptTail().isBlank()) {
            return Optional.empty();
        }
        log.info("Mock tip concept returned for {}", request.tipType());
        return Optional.of(new TipConcept(MOCK_CONCEPT, MOCK_CONFIDENCE));
    }
}
