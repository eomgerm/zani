package com.a105.zani.recording.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 녹화 저장 관련 설정. basePath는 Egress 노드(EC2)의 로컬 저장 루트다(S3 미사용, 가이드 §14). */
@ConfigurationProperties(prefix = "recording")
public record RecordingProperties(
        @DefaultValue("/srv/zani/recordings") String basePath) {}
