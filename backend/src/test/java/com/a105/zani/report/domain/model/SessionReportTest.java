package com.a105.zani.report.domain.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.report.domain.exception.InvalidSessionReportException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionReportTest {

    private static final long SESSION_ID = 100L;
    /** 45분 수업. 완료 조건이 이 길이를 기준으로 쓰여 있다. */
    private static final long CLASS_DURATION_MS = 45 * 60 * 1_000L;

    private static SessionSection section(long startOffsetMs, long endOffsetMs) {
        return SessionSection.of("상태 관리", "useState 와 useReducer 를 비교했다.", startOffsetMs, endOffsetMs);
    }

    @Test
    void keepsSectionsInTimeOrderWithTitleAndSummary() {
        SessionReport report = SessionReport.create(
                SESSION_ID,
                "React 상태 관리를 다뤘다.",
                List.of(section(0, 600_000), section(600_000, 1_800_000)),
                CLASS_DURATION_MS);

        assertEquals(2, report.sections().size());
        assertEquals(0, report.sections().getFirst().startOffsetMs());
        assertEquals("상태 관리", report.sections().getFirst().title());
    }

    /** 이어 붙는 구간은 겹친 것이 아니다 — 앞 구간의 끝이 다음 구간의 시작이 되는 것이 정상 분할이다. */
    @Test
    void acceptsSectionsThatTouchAtTheSameOffset() {

        SessionReport report = SessionReport.create(
                SESSION_ID, "요약", List.of(section(0, 600_000), section(600_000, 900_000)), CLASS_DURATION_MS);

        assertEquals(2, report.sections().size());
    }

    /**
     * 겹친 구간은 거절한다.
     *
     * <p>복습 추천은 구간 번호로 클립을 가리키고 집중도 그래프는 구간을 x축 경계로 쓴다. 겹친 구간이 하나 섞이면 같은 시각이 두 번호에 속해 어느 쪽을 가리키는지 알 수 없다.
     */
    @Test
    void rejectsOverlappingSections() {
        List<SessionSection> overlapping = List.of(section(0, 600_000), section(300_000, 900_000));

        assertThrows(
                InvalidSessionReportException.class,
                () -> SessionReport.create(SESSION_ID, "요약", overlapping, CLASS_DURATION_MS));
    }

    /** 시간순이 아니면 거절한다. 모델이 구간을 뒤섞어 낼 수 있어 저장 전에 막는다. */
    @Test
    void rejectsSectionsThatAreNotInTimeOrder() {
        List<SessionSection> unordered = List.of(section(600_000, 900_000), section(0, 600_000));

        assertThrows(
                InvalidSessionReportException.class,
                () -> SessionReport.create(SESSION_ID, "요약", unordered, CLASS_DURATION_MS));
    }

    /** 수업 길이를 넘는 구간은 재생할 수 없는 지점을 가리킨다(녹화 범위 검증). */
    @Test
    void rejectsASectionThatEndsAfterTheClass() {
        List<SessionSection> tooLate = List.of(section(0, CLASS_DURATION_MS + 1));

        assertThrows(
                InvalidSessionReportException.class,
                () -> SessionReport.create(SESSION_ID, "요약", tooLate, CLASS_DURATION_MS));
    }

    /** 구간이 없는 리포트는 타임라인을 그릴 수 없다. 무음 수업도 폴백 구간 하나는 있어야 한다. */
    @Test
    void rejectsAReportWithoutSections() {
        assertThrows(
                InvalidSessionReportException.class,
                () -> SessionReport.create(SESSION_ID, "요약", List.of(), CLASS_DURATION_MS));
    }

    /** 요약이 비면 학생별 분석이 읽을 공통 맥락이 사라진다(249 가 이 값을 입력으로 읽는다). */
    @Test
    void rejectsAReportWithoutASummary() {
        List<SessionSection> sections = List.of(section(0, 600_000));

        assertThrows(
                InvalidSessionReportException.class,
                () -> SessionReport.create(SESSION_ID, "   ", sections, CLASS_DURATION_MS));
    }

    @Test
    void rejectsASectionWhoseEndIsNotAfterItsStart() {
        assertThrows(InvalidSessionReportException.class, () -> SessionSection.of("제목", "요약", 600_000, 600_000));
    }

    @Test
    void rejectsASectionWithoutATitle() {
        assertThrows(InvalidSessionReportException.class, () -> SessionSection.of("  ", "요약", 0, 600_000));
    }

    @Test
    void rejectsASectionTitleLongerThanTheColumn() {
        String tooLong = "가".repeat(SessionSection.TITLE_MAX_LENGTH + 1);

        assertThrows(InvalidSessionReportException.class, () -> SessionSection.of(tooLong, "요약", 0, 600_000));
    }
}
