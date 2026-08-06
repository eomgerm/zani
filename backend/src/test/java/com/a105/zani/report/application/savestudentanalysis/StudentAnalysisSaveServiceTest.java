package com.a105.zani.report.application.savestudentanalysis;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.report.domain.model.StudentReport;
import com.a105.zani.report.domain.repository.StudentReportRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개인 리포트 저장의 항목 단위 회복력 검증(S15P11A105-333).
 *
 * <p>이 파일이 지키는 것은 하나다 — <b>추천 한 줄의 문제로 리포트 전체가 사라지지 않는다.</b> 예전에는 추천을 리스트로 만드는 {@code map} 안에서 도메인 불변식이 던지면 그대로 올라가 참여
 * 요약·질문 수·정상인 나머지 추천과 퀴즈까지 함께 버려졌고, 그 학생이 {@code FAILED} 로 집계되면 세션 공개까지 막혔다.
 *
 * <p>호출부의 {@code ground()} 는 구간 번호·유형·구간 중복만 본다. 제목이나 설명이 비었는지는 보지 않으므로 그 값이 여기까지 온다.
 */
class StudentAnalysisSaveServiceTest {

    private static final long SESSION_ID = 5_201L;
    private static final long PARTICIPANT_ID = 5_202L;
    private static final String SUMMARY = "공개 채팅으로 질문하고 놓친 구간을 복습했다.";

    private StudentReport saved;
    private StudentAnalysisSaveService service;

    @BeforeEach
    void setUp() {
        saved = null;
        StudentReportRepository repository = report -> {
            saved = report;
            return Optional.of(7_001L);
        };
        service = new StudentAnalysisSaveService(repository);
    }

    @Test
    @DisplayName("불변식을 못 지킨 추천만 버리고 나머지 리포트는 저장한다")
    void dropsOnlyTheUnusableRecommendation() {
        service.save(command(
                recommendation("CONFUSED", "해시 충돌 다시 보기", "체이닝 설명 구간을 다시 보세요.", 0L, 60_000L),
                // 제목이 비어 추천이 될 수 없다. 이 한 줄 때문에 아래 값들이 함께 사라지면 안 된다.
                recommendation("MISSED", "   ", "설명은 있지만 제목이 없습니다.", 60_000L, 120_000L),
                recommendation("QUESTION", "적재율 정리", "리해싱 절차를 다시 보세요.", 120_000L, 180_000L)));

        assertThat(saved).isNotNull();
        assertThat(saved.recommendations()).hasSize(2);
        // 추천이 아니라 리포트 본문이 살아남는지가 요점이다.
        assertThat(saved.participationSummary()).isEqualTo(SUMMARY);
        assertThat(saved.questionCount()).isEqualTo(3);
    }

    /** 미정의 유형도 같은 자리에서 던진다 — {@code RecommendationType.from} 이 같은 예외를 쓴다. */
    @Test
    @DisplayName("모르는 추천 유형도 그 한 줄만 버린다")
    void dropsAnUnknownRecommendationType() {
        service.save(command(
                recommendation("CONFUSED", "해시 충돌 다시 보기", "체이닝 설명 구간을 다시 보세요.", 0L, 60_000L),
                recommendation("NOT_A_TYPE", "유형이 이상함", "설명은 정상입니다.", 60_000L, 120_000L)));

        assertThat(saved.recommendations()).hasSize(1);
    }

    /** 추천 0개인 리포트는 유효하다 — 화면도 "복습 추천이 없어요" 를 그린다(REPORT-S-005). */
    @Test
    @DisplayName("추천이 전부 버려져도 리포트는 저장한다")
    void savesAReportWithoutAnyUsableRecommendation() {
        service.save(command(recommendation("CONFUSED", "", "제목이 없습니다.", 0L, 60_000L)));

        assertThat(saved).isNotNull();
        assertThat(saved.recommendations()).isEmpty();
        assertThat(saved.participationSummary()).isEqualTo(SUMMARY);
    }

    private SaveStudentAnalysisCommand command(SaveStudentAnalysisCommand.Recommendation... recommendations) {
        return new SaveStudentAnalysisCommand(SESSION_ID, PARTICIPANT_ID, SUMMARY, 3, List.of(recommendations));
    }

    private SaveStudentAnalysisCommand.Recommendation recommendation(
            String type, String title, String description, long startedOffsetMs, long endedOffsetMs) {
        return new SaveStudentAnalysisCommand.Recommendation(type, title, description, startedOffsetMs, endedOffsetMs);
    }
}
