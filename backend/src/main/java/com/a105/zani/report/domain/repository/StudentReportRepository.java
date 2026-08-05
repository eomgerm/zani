package com.a105.zani.report.domain.repository;

import java.util.Optional;

import com.a105.zani.report.domain.model.StudentReport;

public interface StudentReportRepository {

    /** 리포트가 없으면 추천과 함께 넣고 그 ID 를 준다. 다른 실행이 먼저 넣었으면 빈 값을 주고 <b>추천도 넣지 않는다</b> — 남의 리포트에 내 추천이 섞이면 안 된다. */
    Optional<Long> saveIfAbsent(StudentReport report);
}
