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
 * @param maxUploadBytes 실질 업로드 상한(24 MiB). <b>업로드 직전 가드이고 자동 반분은 하지 않는다</b> — 넘는 청크는 GMS 를 호출하지 않고 비재시도 실패로 끊는다. 실측
 *     비트레이트(104.8~127 kbps)로 10분이면 7.9~9.5 MB 라 한도에 세 배 넘는 여유가 있어, 걸린다면 {@code chunk-duration} 을 낮춰 대응한다
 * @param concurrency GMS 청크 호출 동시성. 오케스트레이션 자체는 항상 1이고 이 값은 업로드에만 적용된다
 * @param silencePrefilterEnabled 무음 사전 판별. 기본 OFF(S15P11A105-292). <b>호출 <i>전</i></b> 에 무음 청크를 골라 GMS 호출 수를 줄이는 최적화다 —
 *     아래 {@code hallucinationFilterEnabled} 와 다른 단계의 다른 목적이다
 * @param hallucinationFilterEnabled 무음 환각 세그먼트 필터. <b>기본 OFF</b>(S15P11A105-306 에서 ON 으로 냈다가 316 에서 되돌렸다). 0.8 로 켜 둔
 *     상태에서 실제 세션의 강사 트랙 10개 중 4개가 삭제됐다 — 핵심 설명과 마무리 정리 63초였다. 근거는 {@code noSpeechThreshold} 에 적었다
 * @param noSpeechThreshold 이 값 <b>이상</b> 인 {@code no_speech_prob} 세그먼트를 최종 전사에서 뺀다. 범위는 {@code 0.0}~{@code 1.0} 이고 벗어나면
 *     기동하지 않는다.
 *     <p><b>이 값으로는 환각과 실제 발화를 가를 수 없다.</b> {@code no_speech_prob} 는 세그먼트가 아니라 30초 디코딩 창의 값이라, 침묵이 섞인 창의 실제 발화는 높은 값을
 *     물려받고 실제 발화와 같은 창의 환각은 낮은 값을 물려받는다. 실측 분포가 겹친다 — 실제 발화가 {@code 0.515·0.698·0.745·0.811·0.864·0.921·0.964}, 환각이
 *     {@code 0.622·0.790·0.895·0.906·0.924·0.953·0.965} 로 번갈아 나온다.
 *     <p>{@code 0.98} 은 관측된 실제 발화 최댓값({@code 0.964})보다 확실히 위라 <b>켜더라도 강의를 잃지 않는</b> 값이다. 대신 잡는 범위는 완전 무음 구간의 극단값뿐이다
 * @param repeatedPhraseFilterEnabled 반복 문구 환각 필터. 기본 ON(S15P11A105-316). <b>환각 제거의 주 수단이다</b> — 무음 확률과 달리 텍스트를 보므로 실제
 *     발화와 섞이지 않는다
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
        @DefaultValue("false") boolean silencePrefilterEnabled,
        @DefaultValue("false") boolean hallucinationFilterEnabled,
        @DefaultValue("0.98") double noSpeechThreshold,
        @DefaultValue("true") boolean repeatedPhraseFilterEnabled) {

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
        // 확률이므로 0~1 을 벗어날 수 없다. 필터가 꺼져 있어도 검사한다 — 켜는 날 처음 터지면 그때는
        // 이 값을 누가 왜 넣었는지 아는 사람이 없다.
        if (!Double.isFinite(noSpeechThreshold) || noSpeechThreshold < 0.0 || noSpeechThreshold > 1.0) {
            throw new IllegalArgumentException("무음 확률 임곗값은 0.0 이상 1.0 이하여야 합니다: " + noSpeechThreshold);
        }
    }
}
