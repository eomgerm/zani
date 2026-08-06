package com.a105.zani.report.application.savesessionanalysis;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.report.application.exception.InvalidSessionAnalysisException;
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

    /** 요약이 비어 구간으로 만들 수 없는 초안. 모델이 실제로 이렇게 낸다. */
    private static SessionSectionDraft unusableDraft(long startOffsetMs, long endOffsetMs) {
        return new SessionSectionDraft("요약이 없는 구간", "", startOffsetMs, endOffsetMs);
    }

    /**
     * 초안 하나가 불변식을 못 지켰다고 공통 분석을 통째로 버리지 않는다(S15P11A105-333).
     *
     * <p>예전에는 그 예외가 그대로 올라갔고 재시도 대상도 아니어서 세션이 즉시 영구 실패했다 — 요약이 빈 구간 하나로 그 수업의 리포트·퀴즈·타임라인이 전부 없어진다.
     */
    @Test
    void keepsTheUsableSectionsWhenOneDraftIsRejected() {
        SaveSessionAnalysisResult result = service.save(
                command(List.of(draft(0, 600_000), unusableDraft(600_000, 700_000), draft(700_000, 900_000))));

        assertTrue(result.saved());
        // 버린 초안은 세지 않는다. 적재된 구간 수가 곧 화면이 그리는 구간 수다.
        assertEquals(2, result.sectionCount());
        assertEquals(2, repository.saved.getFirst().sections().size());
    }

    /** 가운데 초안이 빠져 생기는 공백은 허용된다. {@code SessionReport} 가 막는 것은 겹침이다. */
    @Test
    void allowsTheGapLeftByARejectedDraft() {
        SaveSessionAnalysisResult result = service.save(
                command(List.of(draft(0, 300_000), unusableDraft(300_000, 600_000), draft(600_000, 900_000))));

        assertTrue(result.saved());
        assertEquals(2, result.sectionCount());
    }

    /**
     * 남은 구간이 하나도 없으면 실패한다.
     *
     * <p>구간은 학생·강사 분석과 타임라인의 <b>입력</b>이라 하나도 없으면 뒤 단계가 근거를 붙일 자리가 없다. 그 판정은 {@code SessionReport.create} 가 갖고 있어 이 서비스가
     * 따로 세지 않는다.
     */
    @Test
    void failsWhenEveryDraftIsRejected() {
        assertThrows(
                InvalidSessionAnalysisException.class,
                () -> service.save(command(List.of(unusableDraft(0, 600_000), unusableDraft(600_000, 900_000)))));
        assertTrue(repository.saved.isEmpty());
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

    /**
     * 검증에 걸리는 결과는 저장하지 않는다 — 완료 조건: 스키마 위반 응답은 저장 없이 실패로 남는다.
     *
     * <p>도메인 예외가 아니라 애플리케이션 경계 예외로 올라온다. 호출하는 도메인이 report 의 domain 계층을 알면 그쪽 구조를 바꿀 때마다 같이 깨진다.
     */
    @Test
    void savesNothingWhenTheSectionsAreInvalid() {
        SaveSessionAnalysisCommand overlapping = command(List.of(draft(0, 600_000), draft(300_000, 900_000)));

        assertThrows(InvalidSessionAnalysisException.class, () -> service.save(overlapping));
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

        @Override
        public boolean markPublished(Long sessionId, java.time.Instant publishedAt) {
            throw new UnsupportedOperationException("적재는 공개하지 않는다");
        }
    }
}
