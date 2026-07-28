package com.a105.zani.recording.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 녹화 저장 관련 설정. basePath는 Egress 노드(EC2)의 로컬 저장 루트다(S3 미사용, 가이드 §14).
 *
 * @param basePath Egress 노드의 로컬 저장 루트
 * @param audioStreamUrlTemplate 강사 오디오 실시간 전달용 WebSocket 수신 주소. Egress 노드에서 도달 가능해야 하며
 *     {@code {sessionId}}·{@code {secret}} 자리표시자를 치환해 쓴다. 시크릿이 들어가므로 로그·응답에 남기지 않는다
 * @param audioStreamSecret 수신 측(audioclip WebSocket 핸들러)과 공유하는 시크릿
 */
@ConfigurationProperties(prefix = "recording")
public record RecordingProperties(
        @DefaultValue("/srv/zani/recordings") String basePath,
        String audioStreamUrlTemplate,
        String audioStreamSecret) {

    /** 세션별 수신 주소. 설정이 비어 있으면 오디오 스트림 Egress를 시작할 수 없다. */
    public String audioStreamUrlFor(long sessionId) {
        if (audioStreamUrlTemplate == null || audioStreamUrlTemplate.isBlank()) {
            throw new IllegalStateException("recording.audio-stream-url-template is not configured");
        }
        return audioStreamUrlTemplate
                .replace("{sessionId}", String.valueOf(sessionId))
                .replace("{secret}", audioStreamSecret == null ? "" : audioStreamSecret);
    }
}
