package com.a105.zani.postclass.application.analyzestudents;

import java.util.List;

/** 세션 하나에서 학생 전원이 공유하는 입력. 수업 제목과 공통 요약, 번호 매긴 구간 목록이다. */
public record SessionAnalysisContext(String lectureTitle, String classSummary, List<ConceptSection> sections) {}
