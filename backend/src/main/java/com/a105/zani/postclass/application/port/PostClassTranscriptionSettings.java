package com.a105.zani.postclass.application.port;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 사후 전사 오케스트레이션에 적용할 값(S15P11A105-247).
 *
 * <p>값의 출처는 외부 설정이지만 application 계층이 스프링 설정 타입을 알 필요는 없다. infrastructure 가 설정을 읽어 이 타입으로 바꿔 주입한다 — coach 의
 * {@code CoachingTipSettings}, audioclip 의 {@code AudioClipCaptureSettings} 와 같은 방식이다.
 *
 * @param sourceRoot Track Egress 원본을 읽을 루트. {@code storageKey} 는 이 경로 기준 상대 경로다
 * @param workDir 청크를 만들 디렉터리. 트랙 하나를 처리한 뒤 지운다
 * @param leaseDuration 청크 선점의 유효 기간. GMS timeout 보다 넉넉해야 한다
 * @param concurrency 동시에 올릴 청크 수. 이 값씩 묶어 제출한다 — 실행기 큐 용량이 이 값이라 한 번에 다 던지면 거부된다
 * @param language 전사 요청 언어. 최종 문서에도 그대로 적는다
 * @param silencePrefilterEnabled 무음 사전 판별. <b>아직 구현이 없다</b>(S15P11A105-292 Spike). 켜져 있으면 경고만 남기고 그대로 호출한다
 */
public record PostClassTranscriptionSettings(
        Path sourceRoot,
        Path workDir,
        Duration leaseDuration,
        int concurrency,
        String language,
        boolean silencePrefilterEnabled) {

    public PostClassTranscriptionSettings {
        if (sourceRoot == null || workDir == null) {
            throw new IllegalArgumentException("원본 루트와 작업 디렉터리가 필요합니다");
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("lease 기간은 양수여야 합니다: " + leaseDuration);
        }
        if (concurrency < 1) {
            throw new IllegalArgumentException("동시성은 1 이상이어야 합니다: " + concurrency);
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("전사 언어가 필요합니다");
        }
    }
}
