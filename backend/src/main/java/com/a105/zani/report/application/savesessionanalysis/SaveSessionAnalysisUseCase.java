package com.a105.zani.report.application.savesessionanalysis;

/**
 * 세션 공통 분석 결과(수업 요약·내용 타임라인)를 적재한다. {@code report} 도메인이 {@code session_reports}·{@code session_sections} 를 소유하므로, 분석을
 * 수행하는 {@code postclass} 는 이 계약으로만 적재한다.
 */
public interface SaveSessionAnalysisUseCase {

    SaveSessionAnalysisResult save(SaveSessionAnalysisCommand command);
}
