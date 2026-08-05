package com.a105.zani.postclass.infrastructure.gms;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.analyzeinstructor.ConceptSection;
import com.a105.zani.postclass.application.analyzeinstructor.InstructorAnalysisContext;
import com.a105.zani.postclass.application.port.InstructorAnalysis;
import com.a105.zani.postclass.application.port.InstructorAnalysisRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** mock 응답이 실제 저장 경로를 통과하는 형태여야 한다 — 그러지 않으면 mock 으로 돌린 테스트가 실제 흐름을 검증하지 못한다. */
class GmsInstructorAnalysisMockAdapterTest {

    private final GmsInstructorAnalysisMockAdapter adapter = new GmsInstructorAnalysisMockAdapter();

    private static InstructorAnalysisRequest request(List<ConceptSection> sections) {
        return InstructorAnalysisRequest.of(new InstructorAnalysisContext(
                "상태 관리 수업", "수업 공통 요약", sections, List.of(), List.of(), List.of(), List.of(), null));
    }

    @Test
    @DisplayName("구간이 있으면 점수 4종과 근거 2종 이상인 인사이트를 낸다")
    void returns_scores_and_grounded_insights() {
        Optional<InstructorAnalysis> analysis =
                adapter.analyze(request(List.of(new ConceptSection(1, "상태 관리 개요", "요약", 0L, 519_000L))));

        assertThat(analysis).isPresent();
        assertThat(analysis.get().overallFeedback()).isNotBlank();
        assertThat(analysis.get().questionCount()).isNotNegative();
        assertThat(analysis.get().scores().delivery()).isBetween(0, 100);
        assertThat(analysis.get().scores().structureFlow()).isBetween(0, 100);
        assertThat(analysis.get().scores().interaction()).isBetween(0, 100);
        assertThat(analysis.get().scores().difficultyControl()).isBetween(0, 100);
        assertThat(analysis.get().insights()).isNotEmpty();
        assertThat(analysis.get().insights()).allSatisfy(insight -> {
            assertThat(insight.evidenceKinds())
                    .hasSizeGreaterThanOrEqualTo(InstructorAnalysis.MIN_EVIDENCE_KINDS)
                    .allMatch(InstructorAnalysis.EVIDENCE_KINDS::contains);
            assertThat(insight.sectionIndex()).isBetween(0, 1);
            assertThat(insight.title()).isNotBlank();
            assertThat(insight.evidence()).isNotBlank();
            assertThat(insight.suggestion()).isNotBlank();
        });
    }

    @Test
    @DisplayName("전체 수업 대상 인사이트를 함께 낸다 — 저장 경로의 구간 NULL 갈래를 검증에 태운다")
    void includes_a_whole_class_insight() {
        Optional<InstructorAnalysis> analysis =
                adapter.analyze(request(List.of(new ConceptSection(1, "상태 관리 개요", "요약", 0L, 519_000L))));

        assertThat(analysis.orElseThrow().insights())
                .extracting(InstructorAnalysis.InsightDraft::sectionIndex)
                .contains(0);
    }

    @Test
    @DisplayName("구간이 없으면 빈 값이다 — 근거를 붙일 자리가 없다")
    void returns_empty_without_sections() {
        assertThat(adapter.analyze(request(List.of()))).isEmpty();
    }

    @Test
    @DisplayName("null 요청도 빈 값이다")
    void returns_empty_for_a_null_request() {
        assertThat(adapter.analyze(null)).isEmpty();
    }
}
