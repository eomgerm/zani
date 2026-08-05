package com.a105.zani.report.application.savesessionanalysis;

import java.util.List;

/**
 * 공통 분석 결과 저장 요청(S15P11A105-248).
 *
 * @param sessionId 분석 대상 세션
 * @param classSummary 강사·학생에게 공통으로 보일 수업 요약
 * @param sections 시간순 구간. 겹치지 않아야 하며 수업 길이 안에 있어야 한다
 * @param classDurationMs 수업 길이. 구간이 녹화 범위를 벗어나지 않았는지 애그리거트가 이 값으로 검증한다
 */
public record SaveSessionAnalysisCommand(
        Long sessionId, String classSummary, List<SessionSectionDraft> sections, long classDurationMs) {}
