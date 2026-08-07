package com.a105.zani.report.application.exception;

import com.a105.zani.common.error.ErrorCode;
import com.a105.zani.common.error.ErrorType;

/**
 * 리포트 질의응답 오케스트레이션 실패(S15P11A105-259).
 *
 * <p>열람 자격(비참여자 403, 미종료 세션 409)과 "아직 분석 전"(404)은 여기 없다. 앞의 둘은 {@code SessionApplicationErrorCode} 가, 뒤는
 * {@link ReportErrorCode#REPORT_NOT_READY} 가 이미 소유하고 있어 같은 뜻의 코드를 하나 더 만들면 화면이 두 코드를 같은 문구로 처리해야 한다.
 */
public enum ReportAssistantErrorCode implements ErrorCode {
    /**
     * 질문 빈도 제한.
     *
     * <p>429 로 내리는 이유는 클라이언트가 상태코드만으로 재시도 간격을 늘릴 수 있어야 하기 때문이다. 409 로 뭉뚱그리면 백오프 판단이 서버 계약이 아니라 클라이언트 구현으로 흩어진다.
     */
    RATE_LIMITED(ErrorType.TOO_MANY_REQUESTS, "REPORT_ASSISTANT_001", "Too many questions in a short time"),

    /** GMS 호출이 실패했거나 응답이 계약을 벗어났다. 재시도하지 않으므로 화면이 "다시 시도" 를 그린다. */
    ANSWER_UNAVAILABLE(
            ErrorType.SERVICE_UNAVAILABLE, "REPORT_ASSISTANT_002", "Could not generate an answer for this question"),

    /** 근거를 담아도 게이트웨이 본문 상한을 넘는다. 이력을 다 버려도 안 들어가는 경우라 질문을 줄여야 한다. */
    QUESTION_TOO_LARGE(ErrorType.BAD_REQUEST, "REPORT_ASSISTANT_003", "The question and its context are too large");

    private final ErrorType type;
    private final String code;
    private final String message;

    ReportAssistantErrorCode(ErrorType type, String code, String message) {
        this.type = type;
        this.code = code;
        this.message = message;
    }

    @Override
    public ErrorType type() {
        return type;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
