package com.a105.zani.attention.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 학생이 아닌 참가자가 개인 집중 흐름을 요청했다.
 *
 * <p>{@link NotSessionStudentException} 을 재사용하지 않는다. 그쪽 메시지는 "판정을 보낼 수 있는 것은 학생뿐"이라는 뜻이라, 리포트를 읽으려다 받으면 무엇을 잘못했는지 알 수
 * 없다.
 *
 * <p>강사가 이 경로를 부르면 여기로 온다. 강사에게는 익명 집단 타임라인이 따로 있고, 개인 타임라인은 본인 것만 볼 수 있다(REPORT-I-002).
 */
public class NotSessionStudentTimelineException extends BusinessException {

    public NotSessionStudentTimelineException() {
        super(AttentionTimelineErrorCode.NOT_SESSION_STUDENT_TIMELINE);
    }
}
