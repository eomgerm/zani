package com.a105.zani.postclass.application.analyzecontent;

/** 세션 전사에서 수업 요약과 내용 타임라인을 만들어 적재한다(S15P11A105-248). 사후 파이프라인의 {@code ANALYZING} 단계에서 세션당 한 번 실행된다. */
public interface AnalyzeSessionContentUseCase {

    AnalyzeSessionContentResult analyze(AnalyzeSessionContentCommand command);
}
