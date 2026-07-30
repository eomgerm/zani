package com.a105.zani.recording.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 녹화 저장 관련 설정. basePath는 Egress 노드(EC2)의 로컬 저장 루트다(S3 미사용, 가이드 §14).
 *
 * <p>코칭용 오디오 스트림의 수신 주소는 여기 두지 않는다. 그 경로를 소유하고 접속 자격을 만드는 쪽이 audioclip 이므로 {@code AudioStreamEndpointPort} 로 물어본다.
 */
@ConfigurationProperties(prefix = "recording")
public record RecordingProperties(
        @DefaultValue("/srv/zani/recordings") String basePath) {}
