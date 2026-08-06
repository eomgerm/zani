package com.a105.zani.report.application.askreportquestion;

import java.util.List;

import com.a105.zani.report.application.listsessionsections.SessionSectionView;

/**
 * 리포트에서 드래그한 지점을 전사 시간창으로 옮긴다.
 *
 * <p><b>순수 함수로 두는 것이 이 타입의 요점이다.</b> 이 계산이 한 구간 밀려도 LLM 은 여전히 그럴듯한 한국어로 답한다 — 다만 질문한 사람이 가리킨 곳이 아니라 옆 구간에 대해 답할 뿐이다. 답변
 * 텍스트만 읽어서는 잡을 수 없는 종류의 오류라, DB 도 스프링도 없이 값만으로 검증되는 자리에 둔다.
 *
 * <p>앞뒤 한 구간씩 붙이는 이유는 구간 경계가 발화 경계와 맞지 않기 때문이다. 사후 분석(248)이 주제가 바뀌는 지점에서 나눈 경계라, 바로 앞 문장이 그 주제의 실제 설명인 경우가 흔하다. GMS 본문
 * 예산의 27% 만 쓰므로 넉넉히 줘도 된다.
 *
 * <p>구간 요약을 만드는 쪽과 전사를 뽑는 쪽이 <b>같은 경계값</b>을 봐야 하므로 창의 시작·끝을 함께 내보낸다. 인용 검증이 "이 시각이 우리가 보낸 창 안인가" 를 물을 때 근거가 여기서 나온다 — 두
 * 곳에서 따로 계산하면 언젠가 갈라진다.
 *
 * @param anchor 앵커가 속한 구간. 앵커가 없거나 어느 구간에도 들어가지 않으면 {@code null}
 * @param windowFromMs 전사를 뽑을 창의 시작
 * @param windowToMs 창의 끝
 */
record GroundingWindow(SessionSectionView anchor, long windowFromMs, long windowToMs) {

    /** 앵커를 찾지 못한 상태. 창이 비어 있으므로 전사 대신 전 구간 요약으로 답하는 경로를 탄다. */
    private static final GroundingWindow NONE = new GroundingWindow(null, 0L, 0L);

    /**
     * 구간 목록에서 앵커가 속한 칸을 찾아 앞뒤 한 칸씩 넓힌다.
     *
     * <p>목록은 시작 오프셋 오름차순으로 들어온다({@code findBySessionIdOrderByStartedOffsetMsAsc}). 구간은 최대 40개라 선형 탐색으로 충분하고, 이분 탐색은 경계
     * 규칙을 읽기 어렵게 만들 뿐이다.
     */
    static GroundingWindow resolve(List<SessionSectionView> sections, Long anchorStartMs) {
        if (anchorStartMs == null || sections == null || sections.isEmpty()) {
            return NONE;
        }
        int index = indexOf(sections, anchorStartMs);
        if (index < 0) {
            return NONE;
        }
        // 양 끝에서는 없는 쪽으로 넓히지 않고 자기 자신에 머문다. 첫 구간과 마지막 구간이 특수한 경우가 아니라 같은 규칙의 자연스러운 끝이 되도록 clamp 로 적는다.
        SessionSectionView from = sections.get(Math.max(0, index - 1));
        SessionSectionView to = sections.get(Math.min(sections.size() - 1, index + 1));
        return new GroundingWindow(sections.get(index), from.startedOffsetMs(), to.endedOffsetMs());
    }

    /**
     * 앵커가 속한 구간의 위치. 어느 구간에도 들어가지 않으면 {@code -1}.
     *
     * <p>소속 판정은 반열림 구간({@code start <= 앵커 < end})이다. 구간은 빈틈없이 이어져 있어 경계 시각이 두 구간에 동시에 속할 수 있는데, 한쪽으로 못박지 않으면 같은 드래그가
     * 실행마다 다른 창을 낸다.
     *
     * <p><b>마지막 구간만 끝을 닫는다.</b> 248 이 구간을 {@code classDurationMs} 까지 채우므로 수업의 마지막 시각이 실제로 앵커가 될 수 있는데, 반열림으로만 두면 그 시각이
     * 어느 구간에도 속하지 않아 앵커 없음으로 떨어진다.
     */
    private static int indexOf(List<SessionSectionView> sections, long anchorStartMs) {
        int last = sections.size() - 1;
        for (int index = 0; index <= last; index++) {
            SessionSectionView section = sections.get(index);
            boolean inside = index == last
                    ? anchorStartMs >= section.startedOffsetMs() && anchorStartMs <= section.endedOffsetMs()
                    : anchorStartMs >= section.startedOffsetMs() && anchorStartMs < section.endedOffsetMs();
            if (inside) {
                return index;
            }
        }
        return -1;
    }

    boolean anchored() {
        return anchor != null;
    }
}
