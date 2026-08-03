package com.a105.zani.postclass.application.port;

import java.util.Optional;

/**
 * 학생 한 명의 참여도 요약·복습 추천·퀴즈를 LLM 한 번으로 받아 온다.
 *
 * <p>timeout·401·402·429·5xx·스키마 위반을 전부 빈 값 하나로 돌려준다. 사유를 나누지 않는 이유는 대응이 같기 때문이다 — 그 학생만 실패로 남기고 다음 학생으로 넘어간다. 같은 실행
 * 안에서 다시 부르지 않는다.
 */
public interface StudentAnalysisPort {

    Optional<StudentAnalysis> analyze(StudentAnalysisRequest request);
}
