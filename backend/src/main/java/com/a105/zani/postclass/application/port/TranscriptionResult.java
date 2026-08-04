package com.a105.zani.postclass.application.port;

import java.util.List;

/**
 * 오디오 한 덩어리를 전사한 결과(S15P11A105-247).
 *
 * <p>{@code durationMs} 를 함께 받는 이유는 방어 때문이다. 크게 어긋나면 우리가 올린 바이트가 우리가 생각한 구간이 아니라는 신호이고, 그대로 두면 시간축이 조용히 밀린 전사가 저장된다.
 * 응답에 이미 들어 있는 값이라 검사에 드는 비용이 없다.
 *
 * <p><b>정확히 일치하지는 않는다.</b> 실측에서 두 파일은 소수점까지 맞았지만(326.19s / 276.40s), 잘라 낸 청크로 확인했을 때는 CSV 구간 50.718417s 에 대해 GMS 가
 * 50.709999s 를 보고했다 — 8.4ms 차이다. 그래서 비교하는 쪽은 허용 폭을 두어야 한다(오케스트레이션이 1초를 쓴다). 등호로 비교하면 멀쩡한 청크가 거절된다.
 *
 * @param durationMs GMS 가 보고한 오디오 길이
 * @param language GMS 가 판정한 언어. 요청에 {@code ko} 를 넣지만 응답 값을 그대로 보존한다
 * @param segments 넘긴 오디오 기준 상대 시각 세그먼트. 무음 구간에는 세그먼트가 없어 사이가 벌어질 수 있다 — 정상이다
 */
public record TranscriptionResult(long durationMs, String language, List<TranscriptSegment> segments) {

    public TranscriptionResult {
        if (durationMs < 0) {
            throw new IllegalArgumentException("전사 길이가 음수입니다: " + durationMs);
        }
        segments = segments == null ? List.of() : List.copyOf(segments);
    }
}
