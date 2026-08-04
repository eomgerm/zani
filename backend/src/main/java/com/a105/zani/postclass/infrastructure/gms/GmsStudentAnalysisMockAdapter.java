package com.a105.zani.postclass.infrastructure.gms;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.a105.zani.postclass.application.port.StudentAnalysis;
import com.a105.zani.postclass.application.port.StudentAnalysisPort;
import com.a105.zani.postclass.application.port.StudentAnalysisRequest;

/**
 * GMS 크레딧을 쓰지 않고 개발·테스트할 때 쓰는 학생별 분석 어댑터. (S15P11A105-249)
 *
 * <p>실제 어댑터와 조건이 배타적이어야 한다 — 겹치면 {@link StudentAnalysisPort} 빈이 둘이라 기동이 실패한다. 운영에서 이 어댑터가 뜨는 사고는
 * {@code GmsMockProfileGuard} 가 기동 시점에 막는다.
 *
 * <p>구간이 있으면 첫 구간에 추천 하나를 낸다. 문항 3개는 저장 경로의 하한과 같아 실제 모델 응답과 같은 형태로 검증된다.
 */
@Component
@ConditionalOnProperty(prefix = "gms", name = "mock-enabled", havingValue = "true", matchIfMissing = true)
public class GmsStudentAnalysisMockAdapter implements StudentAnalysisPort {

    private static final Logger log = LoggerFactory.getLogger(GmsStudentAnalysisMockAdapter.class);

    @Override
    public Optional<StudentAnalysis> analyze(StudentAnalysisRequest request) {
        if (request == null || request.sections() == null || request.sections().isEmpty()) {
            return Optional.empty();
        }
        List<StudentAnalysis.RecommendationDraft> recommendations =
                List.of(new StudentAnalysis.RecommendationDraft(1, "CONFUSED", "예시 복습 구간", "이 구간을 다시 확인해 보세요."));
        List<StudentAnalysis.QuestionDraft> questions = IntStream.rangeClosed(1, 3)
                .mapToObj(number -> new StudentAnalysis.QuestionDraft(
                        1,
                        "예시 문항 " + number,
                        "예시 해설",
                        List.of(
                                new StudentAnalysis.OptionDraft("정답 보기", true),
                                new StudentAnalysis.OptionDraft("오답 보기 1", false),
                                new StudentAnalysis.OptionDraft("오답 보기 2", false),
                                new StudentAnalysis.OptionDraft("오답 보기 3", false))))
                .toList();
        log.info("Mock student analysis returned for {}", request.studentAlias());
        return Optional.of(new StudentAnalysis(
                "예시 참여도 요약입니다.", 2, recommendations, new StudentAnalysis.QuizDraft("예시 복습 퀴즈", "예시 설명", questions)));
    }
}
