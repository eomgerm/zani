package com.a105.zani.postclass.application.port;

import java.util.List;

/**
 * 공통 분석 요청. 이 타입에는 식별자가 들어올 자리가 없다 — 화자는 별칭이고 세션 id 는 애초에 필드가 없다(GMS 가이드 §9).
 *
 * <p><b>수업 제목을 담지 않는다.</b> 제목은 강사가 자유롭게 쓰는 값이라 "김철수 강사의 알고리즘 수업" 처럼 실명이나 이메일이 들어올 수 있고, 그것을 그대로 실어 보내면 §9.3 의 이름·이메일 전송
 * 금지를 어긴다. 보내기 전에 걸러 내는 방법도 있지만 한국어 이름을 규칙으로 가려내는 일은 확실하지 않고, 반쯤 걸러 내는 검사는 "걸렀다"는 잘못된 믿음만 남긴다. 제목이 주던 것은 동음이의어 맥락 하나인데
 * 그 값은 80분치 전사가 이미 담고 있다.
 *
 * @param classDurationMs 수업 길이. 모델이 구간 끝을 이 범위 안으로 내도록 알려 준다
 * @param lines 수업 시간순 전사
 */
public record ContentAnalysisRequest(long classDurationMs, List<ContentAnalysisLine> lines) {}
