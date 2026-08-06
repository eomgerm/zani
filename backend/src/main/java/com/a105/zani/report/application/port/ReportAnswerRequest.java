package com.a105.zani.report.application.port;

import java.util.List;

/**
 * 리포트 질의응답 한 번에 필요한 전부.
 *
 * <p><b>근거는 서버가 만든 것만 담는다.</b> 클라이언트가 보낸 값은 {@code question}, {@code selectedText}, {@code history} 셋뿐이고 나머지는 전부 우리 DB
 * 에서 나온다. 선택 텍스트를 근거로 쓰면 임의의 문장을 붙여 넣어 수업 밖 답변을 끌어낼 수 있다 — 그래서 "사용자가 이 부분을 짚었다" 는 표시로만 쓴다.
 *
 * <p>창의 경계를 함께 싣는 이유는 인용 검증이 같은 값을 봐야 하기 때문이다. 어댑터가 스스로 다시 계산하면 검색과 검증이 언젠가 갈라지고, 그때 모델이 지어낸 시각이 검증을 통과한다.
 *
 * @param anchoredSection 앵커 구간. 전체 요약 문단처럼 구간 밖을 드래그했으면 {@code null} 이다
 * @param lines 창 안의 발화. 화자는 이미 별칭이다. 앵커가 없으면 빈 목록
 * @param outline 전 구간 제목 + 시각. 앵커 유무와 무관하게 항상 싣는다
 * @param fallbackSummaries 앵커가 없을 때만 채운다. 전사 대신 전 구간 요약으로 답하는 갈래다
 * @param windowFromMs 인용이 이 값 아래를 가리키면 버린다. 앵커가 없으면 0
 * @param windowToMs 인용이 이 값 위를 가리키면 버린다. 앵커가 없으면 0
 */
public record ReportAnswerRequest(
        AnchoredSection anchoredSection,
        List<AnswerLine> lines,
        List<OutlineEntry> outline,
        List<String> fallbackSummaries,
        String question,
        String selectedText,
        List<HistoryTurn> history,
        long windowFromMs,
        long windowToMs) {

    public ReportAnswerRequest {
        lines = lines == null ? List.of() : List.copyOf(lines);
        outline = outline == null ? List.of() : List.copyOf(outline);
        fallbackSummaries = fallbackSummaries == null ? List.of() : List.copyOf(fallbackSummaries);
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** 앵커가 있으면 전사로, 없으면 전 구간 요약으로 답한다. 두 갈래가 프롬프트에서 갈리므로 판정을 한 곳에 둔다. */
    public boolean anchored() {
        return anchoredSection != null;
    }
}
