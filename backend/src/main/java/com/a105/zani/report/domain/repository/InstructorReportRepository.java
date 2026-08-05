package com.a105.zani.report.domain.repository;

import java.util.Optional;

import com.a105.zani.report.domain.model.InstructorReport;

public interface InstructorReportRepository {

    /**
     * 리포트가 없으면 분야별 점수·수업 인사이트와 함께 넣고 그 ID 를 준다.
     *
     * <p>다른 실행이 먼저 넣었으면 빈 값을 주고 <b>점수와 인사이트도 넣지 않는다</b> — 남의 리포트에 내 분석이 섞이면 도넛이 여덟 개가 된다.
     */
    Optional<Long> saveIfAbsent(InstructorReport report);
}
