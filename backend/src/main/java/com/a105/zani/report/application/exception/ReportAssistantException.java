package com.a105.zani.report.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 리포트 질의응답 오케스트레이션 실패(S15P11A105-259).
 *
 * <p>사유마다 예외 클래스를 만들지 않고 {@link ReportAssistantErrorCode} 를 받는 하나로 둔다. 셋 다 같은 유스케이스의 같은 지점에서 나오고 잡는 쪽도 하나뿐이라, 타입을 나눠도
 * 구분할 이유가 생기지 않는다(가이드 BAD-014).
 */
public class ReportAssistantException extends BusinessException {

    public ReportAssistantException(ReportAssistantErrorCode errorCode) {
        super(errorCode);
    }
}
