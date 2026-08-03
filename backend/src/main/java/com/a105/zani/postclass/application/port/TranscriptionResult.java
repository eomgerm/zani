package com.a105.zani.postclass.application.port;

import java.util.List;

/**
 * 오디오 한 덩어리를 전사한 결과(S15P11A105-247).
 *
 * <p>{@code durationMs} 를 함께 받는 이유는 방어 때문이다. GMS 가 돌려주는 {@code duration} 이 FFmpeg segment CSV 의 구간 길이와 소수점까지 일치하는 것을
 * 실측으로 확인했다(326.19s / 276.40s 두 파일). 어긋나면 그것은 분할이 잘못됐다는 신호이고, 그대로 두면 시간축이 조용히 밀린 전사가 저장된다. 공짜로 얻는 검사라 버리지 않는다.
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
