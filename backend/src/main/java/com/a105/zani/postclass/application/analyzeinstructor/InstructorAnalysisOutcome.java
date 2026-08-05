package com.a105.zani.postclass.application.analyzeinstructor;

/** 세션 하나의 강사 분석 결과. */
public enum InstructorAnalysisOutcome {
    /** 이번 실행이 리포트를 만들었다. */
    ANALYZED,
    /** 이미 리포트가 있어 아무것도 하지 않았다. */
    SKIPPED,
    /** LLM 호출·길이 가드·저장 중 하나가 실패했다. 리포트 행이 없으므로 다음 실행에서 자연히 다시 대상이 된다. */
    FAILED
}
