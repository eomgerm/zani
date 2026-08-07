package com.a105.zani.report.application.port;

/**
 * 답변의 근거가 된 발화 한 줄.
 *
 * <p>{@code quote} 는 <b>모델이 준 문장이 아니라 우리 전사의 실제 텍스트다.</b> 모델에게 인용문까지 받으면 그럴듯하게 바꿔 쓴 문장을 걸러낼 방법이 없다 — substring 검사는 통과하는
 * 위조를 막지 못한다. 모델은 시각만 짚고 문장은 서버가 채우면 위조가 원천적으로 불가능해진다.
 *
 * @param offsetMs 실제 세그먼트의 시작 시각. 화면이 이 값으로 영상을 이동시킨다
 */
public record AnswerCitation(long offsetMs, String quote) {}
