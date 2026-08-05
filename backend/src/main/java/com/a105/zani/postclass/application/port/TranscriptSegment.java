package com.a105.zani.postclass.application.port;

/**
 * 전사 세그먼트 하나. 시각은 <b>넘긴 오디오 기준 상대값</b>이다(S15P11A105-247).
 *
 * <p>청크 오프셋을 여기서 더하지 않는다. 포트는 "이 바이트를 전사하면 이렇게 나온다" 까지만 책임지고, 원본 기준 절대 시각으로 옮기는 것은 조립하는 쪽이 한다 — 그래야 포트가 "청크" 라는 개념을 몰라도
 * 된다.
 *
 * <p>단어 단위는 담지 않는다. {@code timestamp_granularities[]} 를 보내면 게이트웨이가 배열 필드를 거부해 500 을 준다(실측). 문장(세그먼트) 단위가 상한이다.
 *
 * @param startMs 넘긴 오디오 기준 시작 시각
 * @param endMs 넘긴 오디오 기준 종료 시각
 * @param text 발화 텍스트
 * @param avgLogprob 토큰당 평균 로그 확률. <b>0~1 신뢰도가 아니다</b> — 음수이고, 확률로 쓰려면 {@code exp} 를 거쳐야 한다
 * @param noSpeechProb 무음 확률. 호출 <b>결과</b>이므로 호출 수를 줄이는 데는 쓸 수 없고, 돌아온 세그먼트를 걸러내는 데만 쓴다
 */
public record TranscriptSegment(long startMs, long endMs, String text, double avgLogprob, double noSpeechProb) {

    public TranscriptSegment {
        if (startMs < 0 || endMs < startMs) {
            throw new IllegalArgumentException("세그먼트 구간이 올바르지 않습니다: " + startMs + "~" + endMs);
        }
        if (text == null) {
            throw new IllegalArgumentException("세그먼트 텍스트가 없습니다");
        }
    }

    /**
     * {@code exp(avgLogprob)} 로 만든 파생 신뢰도.
     *
     * <p>보정된 정답 확률이 아니라 휴리스틱이다. FRD §17.2 가 전사에 "신뢰도" 를 요구하므로 저장하되, 계산 방법을 함께 남겨 나중에 식을 바꿀 때 옛 값과 구분할 수 있게 한다.
     */
    public double confidenceScore() {
        return Math.exp(avgLogprob);
    }
}
