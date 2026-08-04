package com.a105.zani.postclass.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 사후 전사 실행 설정(S15P11A105-247).
 *
 * @param sourceRoot Track Egress 원본을 읽을 루트. {@code recording.base-path} 와 값이 같아도 뜻이 다르다 — 그쪽은 LiveKit 에 넘길 출력 경로이고 이쪽은
 *     우리가 읽을 마운트 경로다. 겸용하면 두 마운트가 갈리는 날 조용히 깨진다. 기본값은 <b>컨테이너 안의 마운트 지점</b>({@code /out})이다 — 호스트 경로를 기본값으로 두면 환경변수를
 *     빠뜨린 배포가 컨테이너에 없는 경로를 보며 조용히 파일을 못 찾는다
 * @param workDir 청크를 만들 디렉터리. 원본 마운트가 read-only 라 산출물은 반드시 여기로 간다
 * @param ffmpegPath OGG stream-copy 분할에 쓴다. 재인코딩은 하지 않는다
 * @param ffprobePath 산출물 검증에 쓴다
 * @param processTimeout 외부 프로세스 1회 상한. 멈춘 프로세스가 오케스트레이션 스레드를 잡는 것을 막는다
 * @param pollDelay 전사 대기 작업을 훑는 주기
 * @param dispatchBatchSize 한 주기에 훑을 대기 작업 수. 오케스트레이션 실행기가 크기 1·큐 0 이므로 실제로 시작되는 것은 하나뿐이고, 나머지는 제출이 거부돼 다음 주기로 넘어간다. 1
 *     보다 크게 두는 이유는 앞선 세션이 이미 다른 실행에 넘어간 경우(선점 실패) 같은 주기에 다음 후보를 시도할 수 있게 하려는 것이다
 * @param enabled 배경 디스패치를 켤지. 테스트가 배경 폴링 없이 유스케이스를 직접 부를 수 있게 열어 둔다
 * @param chunkDuration 청크 목표 길이. 25 MiB 한도가 아니라 힙과 timeout 예측 가능성으로 정한 값이다
 * @param leaseDuration 청크 선점의 유효 기간. 이 시간이 지나면 선점한 실행이 죽은 것으로 보고 다른 실행이 회수한다. <b>GMS timeout(180초)보다 넉넉해야 한다</b> — 호출이
 *     상한까지 걸린 뒤 결과를 기록할 여유가 없으면, 살아 있는 작업의 청크를 다른 실행이 가져가고 원래 작업의 쓰기는 fencing 에 걸려 버려진다. 그러면 같은 청크를 두 번 호출하게 된다. 반대로 너무
 *     길면 실제로 죽은 실행의 청크가 그만큼 묶여 있는다
 * @param maxUploadBytes 실질 업로드 상한(24 MiB). 목표 시간으로 자른 뒤 이 값을 넘는 청크만 반으로 다시 자른다 — 파일 내부에서도 비트레이트가 변해 평균 역산을 믿을 수 없다
 * @param concurrency GMS 청크 호출 동시성. 오케스트레이션 자체는 항상 1이고 이 값은 업로드에만 적용된다
 * @param silencePrefilterEnabled 무음 사전 판별. 기본 OFF(S15P11A105-292)
 */
@ConfigurationProperties(prefix = "postclass.transcription")
public record PostClassTranscriptionProperties(
        @DefaultValue("/out") String sourceRoot,
        @DefaultValue("/tmp/zani-postclass") String workDir,
        @DefaultValue("ffmpeg") String ffmpegPath,
        @DefaultValue("ffprobe") String ffprobePath,
        @DefaultValue("PT60S") Duration processTimeout,
        @DefaultValue("PT10S") Duration pollDelay,
        @DefaultValue("5") int dispatchBatchSize,
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT10M") Duration chunkDuration,
        @DefaultValue("PT5M") Duration leaseDuration,
        @DefaultValue("25165824") long maxUploadBytes,
        @DefaultValue("2") int concurrency,
        @DefaultValue("false") boolean silencePrefilterEnabled) {

    public PostClassTranscriptionProperties {
        if (chunkDuration == null || chunkDuration.isZero() || chunkDuration.isNegative()) {
            throw new IllegalArgumentException("청크 길이는 양수여야 합니다: " + chunkDuration);
        }
        if (maxUploadBytes <= 0) {
            throw new IllegalArgumentException("업로드 상한은 양수여야 합니다: " + maxUploadBytes);
        }
        if (concurrency < 1) {
            throw new IllegalArgumentException("동시성은 1 이상이어야 합니다: " + concurrency);
        }
        if (processTimeout == null || processTimeout.isZero() || processTimeout.isNegative()) {
            throw new IllegalArgumentException("프로세스 상한은 양수여야 합니다: " + processTimeout);
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("lease 기간은 양수여야 합니다: " + leaseDuration);
        }
        if (dispatchBatchSize < 1) {
            throw new IllegalArgumentException("디스패치 배치 크기는 1 이상이어야 합니다: " + dispatchBatchSize);
        }
    }
}
