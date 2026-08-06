package com.a105.zani.report.application.saveinstructoranalysis;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.report.domain.model.InstructorReport;
import com.a105.zani.report.domain.repository.InstructorReportRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 강사 분석 저장의 항목 단위 회복력 검증(S15P11A105-332).
 *
 * <p>이 파일이 지키는 것은 하나다 — <b>카드 한 장의 문제로 리포트 전체가 사라지지 않는다.</b> 예전에는 인사이트를 리스트로 만드는 {@code map} 안에서 도메인 불변식이 던지면 그대로 올라가
 * 종합 피드백·분야별 평가·정상인 나머지 카드까지 함께 버려졌고, 한 트랜잭션이라 부분 저장도 없었다. 실제로 제안이 빈 카드 한 장 때문에 강사 리포트가 0건이 됐다.
 */
class InstructorAnalysisSaveServiceTest {

    private static final long SESSION_ID = 4_101L;
    private static final String FEEDBACK = "전반적으로 흐름이 좋았습니다.";

    private InstructorReport saved;
    private InstructorAnalysisSaveService service;

    @BeforeEach
    void setUp() {
        saved = null;
        InstructorReportRepository repository = new InstructorReportRepository() {

            @Override
            public Optional<Long> saveIfAbsent(InstructorReport report) {
                saved = report;
                return Optional.of(9_001L);
            }

            @Override
            public boolean existsBySessionId(Long sessionId) {
                return false;
            }
        };
        service = new InstructorAnalysisSaveService(repository);
    }

    /**
     * 유지 인사이트는 덧붙일 행동이 없어 제안이 빈 문자열로 온다 — 프롬프트가 그렇게 시킨다(318).
     *
     * <p>예전에는 이 한 장이 {@code ClassInsight.of} 에서 던져 리포트 전체가 버려졌다.
     */
    @Test
    @DisplayName("제안이 빈 인사이트도 카드로 저장한다")
    void keeps_an_insight_without_a_suggestion() {
        service.save(command(insight("정리 구간의 마무리 안정감", "핵심 개념을 다시 요약해 마무리하셨습니다.", "")));

        assertThat(saved).isNotNull();
        assertThat(saved.insights()).hasSize(1);
        assertThat(saved.insights().getFirst().suggestion()).isEmpty();
    }

    @Test
    @DisplayName("불변식을 못 지킨 카드만 버리고 나머지 리포트는 저장한다")
    void drops_only_the_unusable_card() {
        service.save(command(
                insight("충돌 해결 방식 구분", "공개 채팅에서 같은 질문이 이어졌습니다.", "비교 표를 먼저 제시해 보세요."),
                // 제목이 비어 카드가 될 수 없다. 이 한 장 때문에 아래 값들이 함께 사라지면 안 된다.
                insight("   ", "근거는 있지만 제목이 없습니다.", "무언가 해 보세요."),
                insight("정리 구간의 마무리 안정감", "핵심 개념을 다시 요약해 마무리하셨습니다.", "")));

        assertThat(saved).isNotNull();
        assertThat(saved.insights()).hasSize(2);
        // 카드가 아니라 리포트 본문이 살아남는지가 요점이다.
        assertThat(saved.overallFeedback()).isEqualTo(FEEDBACK);
        assertThat(saved.scores()).hasSize(4);
        assertThat(saved.questionCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("카드가 전부 버려져도 리포트는 저장한다 — 인사이트 0개는 유효하다")
    void saves_a_report_without_any_usable_card() {
        service.save(command(insight("", "제목이 없습니다.", "제안은 있습니다.")));

        assertThat(saved).isNotNull();
        assertThat(saved.insights()).isEmpty();
        assertThat(saved.overallFeedback()).isEqualTo(FEEDBACK);
    }

    private SaveInstructorAnalysisCommand command(SaveInstructorAnalysisCommand.Insight... insights) {
        return new SaveInstructorAnalysisCommand(
                SESSION_ID,
                FEEDBACK,
                3,
                List.of(
                        new SaveInstructorAnalysisCommand.Score("DELIVERY", 74),
                        new SaveInstructorAnalysisCommand.Score("STRUCTURE_FLOW", 77),
                        new SaveInstructorAnalysisCommand.Score("INTERACTION", 68),
                        new SaveInstructorAnalysisCommand.Score("DIFFICULTY_CONTROL", 61)),
                List.of(insights));
    }

    private SaveInstructorAnalysisCommand.Insight insight(String title, String evidence, String suggestion) {
        return new SaveInstructorAnalysisCommand.Insight(title, evidence, suggestion, null, null);
    }
}
