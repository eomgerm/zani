package com.a105.zani.report.application.askreportquestion;

import java.util.List;

/** 앵커 주변 시간창에 걸치는 발화만 읽는다. 세션 전체 전사를 올려 자바에서 거르지 않는 이유는 3시간 수업이 약 180KB 이고, 이 조회가 질문 한 번마다 일어나기 때문이다. */
public interface TranscriptWindowQueryPort {

    /**
     * {@code [fromMs, toMs)} 에 걸치는 발화를 시작 시각 오름차순으로 돌려준다.
     *
     * <p>전사가 아직 없는 세션이나 발화가 없는 시간대는 <b>빈 목록이며 오류가 아니다.</b> 사후 분석은 끝났는데 그 구간이 침묵이었던 수업이 실제로 있고, 그때도 질문에는 목차로 답할 수 있다.
     */
    List<TranscriptLine> findIn(long sessionId, long fromMs, long toMs);
}
