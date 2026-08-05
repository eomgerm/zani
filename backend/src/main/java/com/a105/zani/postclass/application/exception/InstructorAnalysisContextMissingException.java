package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/** 공통 요약이나 내용 구간이 없어 강사 분석을 시작할 수 없다. 구간이 없으면 인사이트가 짚을 자리가 없다. */
public class InstructorAnalysisContextMissingException extends BusinessException {

    public InstructorAnalysisContextMissingException() {
        super(InstructorAnalysisErrorCode.INSTRUCTOR_ANALYSIS_CONTEXT_MISSING);
    }
}
