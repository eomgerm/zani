package com.a105.zani.attention.application.collect;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 서버가 받아 줄 검출기 계약 버전.
 *
 * <p>브라우저가 다른 특징 추출 계약이나 다른 모델로 낸 판정은 같은 기준으로 해석할 수 없다. 배포 계약은 확정 문서 §3.1의 {@code mediapipe_98_v1} 이며, 모델을 새로 올리는 동안 두
 * 버전을 함께 받아야 할 수 있어 목록으로 둔다.
 *
 * @param supportedFeatureSchemaVersions 받아 줄 특징 추출 계약 버전
 * @param supportedEngineVersions 받아 줄 추론 엔진·모델 버전. 비어 있으면 엔진 버전은 따지지 않는다
 */
@ConfigurationProperties(prefix = "attention.detector")
public record DetectorContractProperties(
        Set<String> supportedFeatureSchemaVersions, Set<String> supportedEngineVersions) {

    public DetectorContractProperties {
        supportedFeatureSchemaVersions =
                supportedFeatureSchemaVersions == null ? Set.of() : Set.copyOf(supportedFeatureSchemaVersions);
        supportedEngineVersions = supportedEngineVersions == null ? Set.of() : Set.copyOf(supportedEngineVersions);
    }

    /** 이 특징 추출 계약을 받아 줄지. 목록이 비어 있으면 제한하지 않는다(로컬·시연 편의). */
    public boolean acceptsFeatureSchema(String version) {
        return supportedFeatureSchemaVersions.isEmpty() || supportedFeatureSchemaVersions.contains(version);
    }

    /** 이 엔진 버전을 받아 줄지. 목록이 비어 있으면 제한하지 않는다. */
    public boolean acceptsEngine(String version) {
        return supportedEngineVersions.isEmpty() || supportedEngineVersions.contains(version);
    }
}
