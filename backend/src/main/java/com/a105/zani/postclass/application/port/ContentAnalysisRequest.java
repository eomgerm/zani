package com.a105.zani.postclass.application.port;

import java.util.List;

/**
 * 공통 분석 요청. 요청 타입 자체가 식별자를 담지 않으므로 어댑터에서 걸러 낼 것이 없다(GMS 가이드 §9).
 *
 * @param lectureTitle 수업 제목. 같은 낱말이 과목에 따라 다른 개념을 가리켜 맥락으로 쓴다
 * @param classDurationMs 수업 길이. 모델이 구간 끝을 이 범위 안으로 내도록 알려 준다
 * @param lines 수업 시간순 전사
 */
public record ContentAnalysisRequest(String lectureTitle, long classDurationMs, List<ContentAnalysisLine> lines) {}
