package com.a105.zani.report.application.port;

/**
 * 리포트에서 드래그한 곳에 대한 질문에 답한다.
 *
 * <p>재시도하지 않는다. 사후 분석 파이프라인과 달리 사람이 화면 앞에서 기다리므로, 실패는 그대로 올려 화면이 "다시 시도" 버튼을 그리게 한다 — 안에서 한 번 더 부르면 대기 시간만 두 배가 된다.
 */
public interface ReportAnswerPort {

    ReportAnswerOutcome answer(ReportAnswerRequest request);
}
