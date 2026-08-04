package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 공통 분석을 끝내지 못했다. 재시도할지는 파이프라인 재시도 정책이 정한다(S15P11A105-107) — 그 판단에 쓰이도록 사유를 {@link ContentAnalysisErrorCode} 로 나눠 담는다.
 */
public class ContentAnalysisFailedException extends BusinessException {

    public ContentAnalysisFailedException(ContentAnalysisErrorCode errorCode) {
        super(errorCode);
    }
}
