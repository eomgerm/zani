package com.a105.zani.report.application.savesessionanalysis;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.report.domain.exception.InvalidSessionReportException;
import com.a105.zani.report.domain.model.SessionReport;
import com.a105.zani.report.domain.repository.SessionReportRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionAnalysisSaveServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long CLASS_DURATION_MS = 45 * 60 * 1_000L;

    private final FakeSessionReportRepository repository = new FakeSessionReportRepository();
    private final SessionAnalysisSaveService service = new SessionAnalysisSaveService(repository);

    private static SaveSessionAnalysisCommand command(List<SessionSectionDraft> sections) {
        return new SaveSessionAnalysisCommand(SESSION_ID, "React 상태 관리를 다뤘다.", sections, CLASS_DURATION_MS);
    }

    private static SessionSectionDraft draft(long startOffsetMs, long endOffsetMs) {
        return new SessionSectionDraft("상태 관리", "useState 와 useReducer 를 비교했다.", startOffsetMs, endOffsetMs);
    }

    @Test
    void storesTheSummaryAndTheSectionsTogether() {
        SaveSessionAnalysisResult result = service.save(command(List.of(draft(0, 600_000), draft(600_000, 900_000))));

        assertTrue(result.saved());
        assertEquals(2, result.sectionCount());
        assertEquals(1, repository.saved.size());
        assertEquals("React 상태 관리를 다뤘다.", repository.saved.getFirst().summary());
    }

    /**
     * 이미 적재된 세션은 다시 쓰지 않는다.
     *
     * <p>재시도는 같은 단계를 다시 실행하므로(107 정책) 성공 뒤 도착한 재시도가 구간을 두 배로 늘릴 수 있다. {@code session_sections} 에는 유니크 제약이 없어 중복이 조용히
     * 쌓인다.
     */
    @Test
    void isIdempotentWhenTheReportAlreadyExists() {
        service.save(command(List.of(draft(0, 600_000))));

        SaveSessionAnalysisResult second = service.save(command(List.of(draft(0, 600_000))));

        assertFalse(second.saved());
        assertEquals(0, second.sectionCount());
        assertEquals(1, repository.saved.size());
    }

    /** 검증에 걸리는 결과는 저장하지 않는다 — 완료 조건: 스키마 위반 응답은 저장 없이 실패로 남는다. */
    @Test
    void savesNothingWhenTheSectionsAreInvalid() {
        SaveSessionAnalysisCommand overlapping = command(List.of(draft(0, 600_000), draft(300_000, 900_000)));

        assertThrows(InvalidSessionReportException.class, () -> service.save(overlapping));
        assertTrue(repository.saved.isEmpty());
    }

    private static final class FakeSessionReportRepository implements SessionReportRepository {

        private final List<SessionReport> saved = new ArrayList<>();

        @Override
        public void save(SessionReport report) {
            saved.add(report);
        }

        @Override
        public boolean existsBySessionId(Long sessionId) {
            return saved.stream().anyMatch(report -> report.sessionId().equals(sessionId));
        }
    }
}
