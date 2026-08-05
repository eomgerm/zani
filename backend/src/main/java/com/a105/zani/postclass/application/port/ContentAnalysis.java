package com.a105.zani.postclass.application.port;

import java.util.List;

/**
 * 공통 분석 결과. 수업 요약과 내용 타임라인이 한 응답에서 함께 나온다.
 *
 * @param classSummary 강사·학생에게 공통으로 보일 수업 요약
 * @param sections 시간순 구간
 */
public record ContentAnalysis(String classSummary, List<AnalyzedSection> sections) {}
