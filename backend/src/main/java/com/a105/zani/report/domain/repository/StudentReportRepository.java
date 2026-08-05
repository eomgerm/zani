package com.a105.zani.report.domain.repository;

import java.time.Instant;
import java.util.Optional;

import com.a105.zani.report.domain.model.StudentReport;

public interface StudentReportRepository {

    /** 리포트가 없으면 추천과 함께 넣고 그 ID 를 준다. 다른 실행이 먼저 넣었으면 빈 값을 주고 <b>추천도 넣지 않는다</b> — 남의 리포트에 내 추천이 섞이면 안 된다. */
    Optional<Long> saveIfAbsent(StudentReport report);

    /**
     * 이 세션에서 아직 공개되지 않은 학생 리포트 전부에 공개 시각을 찍는다.
     *
     * <p>학생별로 나누지 않는 이유: 공개는 수업 단위 사건이다. 한 학생만 공개된 상태는 없다.
     *
     * <p>공통 리포트({@code SessionReportRepository#markPublished})와 달리 갱신 행 수를 공개 여부 판정에 쓰지 않는다. 학생이 0명인 수업은 갱신할 행이 없어 0 이
     * 나오는데 그것은 실패가 아니다. 두 번 공개되는 것을 막는 판정은 공통 리포트 한 곳이 갖는다.
     *
     * @return 이번 호출로 공개된 학생 리포트 수
     */
    int markPublished(Long sessionId, Instant publishedAt);
}
