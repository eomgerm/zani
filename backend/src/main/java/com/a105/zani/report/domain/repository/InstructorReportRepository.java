package com.a105.zani.report.domain.repository;

import java.time.Instant;
import java.util.Optional;

import com.a105.zani.report.domain.model.InstructorReport;

public interface InstructorReportRepository {

    /**
     * 리포트가 없으면 분야별 점수·수업 인사이트와 함께 넣고 그 ID 를 준다.
     *
     * <p>다른 실행이 먼저 넣었으면 빈 값을 주고 <b>점수와 인사이트도 넣지 않는다</b> — 남의 리포트에 내 분석이 섞이면 도넛이 여덟 개가 된다.
     */
    Optional<Long> saveIfAbsent(InstructorReport report);

    /** 이 세션의 강사 리포트가 있는지. 공개 전 리포트가 갖춰졌는지 보는 데 쓴다(S15P11A105-304). */
    boolean existsBySessionId(Long sessionId);

    /**
     * 아직 공개되지 않은 강사 리포트에 공개 시각을 찍는다.
     *
     * <p>강사 리포트 조회가 이 값으로 열람 가능 여부를 정한다({@code GetInstructorReportService} 의 {@code publishedAt != null} 필터). 찍지 않으면
     * 분석이 끝난 리포트를 강사가 영구히 열 수 없다.
     *
     * <p>공통 리포트와 달리 갱신 행 수를 공개 여부 판정에 쓰지 않는다. 중복 공개를 막는 판정은 공통 리포트 한 곳이 갖는다.
     *
     * @return 이번 호출로 공개됐으면 {@code true}
     */
    boolean markPublished(Long sessionId, Instant publishedAt);
}
