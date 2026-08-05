package com.a105.zani.postclass.application.analyzeinstructor;

/** 세션당 1건이라 셀 대상이 없다 — 결과가 하나뿐이다(학생별 분석과 다른 점). */
public record AnalyzeSessionInstructorResult(InstructorAnalysisOutcome outcome) {}
