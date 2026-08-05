package com.a105.zani.postclass.infrastructure.gms;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.a105.zani.postclass.application.port.InstructorAnalysis;
import com.a105.zani.postclass.application.port.InstructorAnalysisPort;
import com.a105.zani.postclass.application.port.InstructorAnalysisRequest;

/**
 * GMS 크레딧을 쓰지 않고 개발·테스트할 때 쓰는 강사 분석 어댑터. (S15P11A105-250)
 *
 * <p>실제 어댑터와 조건이 배타적이어야 한다 — 겹치면 {@link InstructorAnalysisPort} 빈이 둘이라 기동이 실패한다. 운영에서 이 어댑터가 뜨는 사고는
 * {@code GmsMockProfileGuard} 가 기동 시점에 막는다.
 *
 * <p>인사이트 둘을 낸다. 하나는 첫 구간을 짚고 하나는 전체 수업 대상({@code sectionIndex 0})이라 저장 경로의 두 갈래(구간 시각 치환·NULL)를 함께 지난다. 근거는 실제 규칙과 같이
 * 두 종류씩 넣는다.
 */
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "true", matchIfMissing = true)
public class GmsInstructorAnalysisMockAdapter implements InstructorAnalysisPort {

    private static final Logger log = LoggerFactory.getLogger(GmsInstructorAnalysisMockAdapter.class);

    @Override
    public Optional<InstructorAnalysis> analyze(InstructorAnalysisRequest request) {
        if (request == null || request.sections() == null || request.sections().isEmpty()) {
            return Optional.empty();
        }
        List<InstructorAnalysis.InsightDraft> insights = List.of(
                new InstructorAnalysis.InsightDraft(
                        1,
                        "예시 어려운 구간 보강",
                        "확인 필요 신호와 공개 질문이 같은 구간에 모였습니다.",
                        "예시 코드와 실습 시간을 늘려보세요.",
                        List.of("ATTENTION_FLOW", "CHAT")),
                new InstructorAnalysis.InsightDraft(
                        0,
                        "예시 후반 흐름 회복",
                        "후반부로 갈수록 확인 필요 비율이 낮아지고 메모에도 같은 관찰이 남았습니다.",
                        "같은 전개 순서를 유지해보세요.",
                        List.of("CHECK_RESPONSE", "INSTRUCTOR_NOTE")));
        log.info("Mock instructor analysis returned for lecture={}", request.lectureTitle());
        return Optional.of(
                new InstructorAnalysis(2, "예시 종합 피드백입니다.", new InstructorAnalysis.Scores(88, 84, 71, 76), insights));
    }
}
