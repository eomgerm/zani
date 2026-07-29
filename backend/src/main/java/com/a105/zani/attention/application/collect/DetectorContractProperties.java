package com.a105.zani.attention.application.collect;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 서버가 받아 줄 특징 추출 계약 버전.
 *
 * <p>브라우저가 다른 계약으로 뽑은 특징은 같은 기준으로 해석할 수 없다. 49차원의 구성과 순서까지 학습 계약이라 어긋나면 판정이 무의미해진다. 배포 계약은 확정 문서 §3.1의
 * {@code mediapipe_98_v1} 이며, 모델을 새로 올리는 동안 두 버전을 함께 받아야 할 수 있어 목록으로 둔다.
 *
 * <p>추론 엔진 버전은 받지 않는다. 브라우저가 보낼 값이 없고(모델 아티팩트 메타데이터에 버전 필드가 없다) 서버에도 읽는 곳이 없어, 필수로 두면 의미 없는 상수만 오간다.
 *
 * @param supportedFeatureSchemaVersions 받아 줄 특징 추출 계약 버전
 */
@ConfigurationProperties(prefix = "attention.detector")
public record DetectorContractProperties(Set<String> supportedFeatureSchemaVersions) {

    public DetectorContractProperties {
        supportedFeatureSchemaVersions =
                supportedFeatureSchemaVersions == null ? Set.of() : Set.copyOf(supportedFeatureSchemaVersions);
    }

    /** 이 특징 추출 계약을 받아 줄지. 목록이 비어 있으면 제한하지 않는다(로컬·시연 편의). */
    public boolean acceptsFeatureSchema(String version) {
        return supportedFeatureSchemaVersions.isEmpty() || supportedFeatureSchemaVersions.contains(version);
    }
}
