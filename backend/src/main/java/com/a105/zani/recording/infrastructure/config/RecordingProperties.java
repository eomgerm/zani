package com.a105.zani.recording.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 녹화 저장 관련 설정. basePath는 Egress 노드(EC2)의 로컬 저장 루트다(S3 미사용, 가이드 §14).
 *
 * <p>코칭용 오디오 스트림의 수신 주소는 여기 두지 않는다. 그 경로를 소유하고 접속 자격을 만드는 쪽이 audioclip 이므로 {@code AudioStreamEndpointPort} 로 물어본다.
 *
 * @param basePath LiveKit 에 넘길 Egress <b>출력</b> 경로. 우리가 읽는 경로가 아니다.
 * @param mediaRoot 최종 MP4 를 <b>읽어서 서빙할</b> 마운트 경로. basePath 와 값이 같아도 뜻이 달라 겸용하지 않는다 — 배포에서 두 마운트가 갈린다.
 * @param mediaUrlTtl 발급한 접근 주소의 유효 기간. 짧을수록 새어 나간 주소의 수명이 짧다.
 * @param mediaUrlTemplate 발급할 주소의 형식. {@code {sessionId}} 를 치환하고 뒤에 만료·토큰 질의를 붙인다.
 */
@ConfigurationProperties(prefix = "recording")
public record RecordingProperties(
        @DefaultValue("/srv/zani/recordings") String basePath,
        @DefaultValue("/srv/zani/recordings") String mediaRoot,
        @DefaultValue("PT5M") Duration mediaUrlTtl,

        @DefaultValue("http://localhost:18080/api/v1/sessions/{sessionId}/media")
        String mediaUrlTemplate) {}
