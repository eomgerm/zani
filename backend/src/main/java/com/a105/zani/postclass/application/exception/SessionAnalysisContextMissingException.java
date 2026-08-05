package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 공통 요약이나 내용 구간이 없어 학생별 분석을 시작할 수 없다. 세션 전체를 중단한다 — 학생마다 실패시키면 같은 사유가 30번 찍힌다. */
public class SessionAnalysisContextMissingException extends BusinessException {

    public SessionAnalysisContextMissingException() {
        super(StudentAnalysisErrorCode.SESSION_ANALYSIS_CONTEXT_MISSING);
    }
}
