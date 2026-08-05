package com.a105.zani.postclass.application.port;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.a105.zani.postclass.application.analyzestudents.ConceptSection;
import com.a105.zani.postclass.application.analyzestudents.SessionAnalysisContext;
import com.a105.zani.postclass.application.analyzestudents.StudentObservations;
import com.a105.zani.postclass.application.analyzestudents.StudentSectionSignal;
import com.a105.zani.postclass.application.analyzestudents.StudentSignalDigest;

/**
 * GMS 로 보낼 입력. 담긴 값에 식별자가 없다 — 학생은 별칭, 구간은 1부터의 번호이고 세션 ID·참여자 ID 는 애초에 이 타입에 들어올 자리가 없다(GMS 가이드 §9).
 *
 * <p><b>구간별 집계는 항상 함께 보낸다.</b> 관측이 어느 구간에 드는지는 시각을 구간 경계와 비교하는 산술인데, 실측에서 모델이 이것을 한 칸씩 틀렸다(2026-08-04 — 미응답이 있던 구간을
 * "놓침" 으로, 저참여 구간을 "미응답" 으로 붙였다). 서버는 그 계산을 정확히 할 수 있고 {@link StudentSignalDigest} 가 이미 그 함수다. 모델에게 암산을 시키지 않는다.
 *
 * <p>원본 관측은 집계가 담지 못하는 것에 쓴다 — 채팅 문장을 읽어야 질문인지 가릴 수 있고, 요약도 문장을 봐야 쓸 수 있다. 길이 가드가 발동하면 원본만 떨어지고 집계는 남는다.
 */
public record StudentAnalysisRequest(
        String studentAlias,
        String lectureTitle,
        String classSummary,
        List<ConceptSection> sections,
        StudentObservations observations,
        List<StudentSectionSignal> signals) {

    public static StudentAnalysisRequest raw(
            String studentAlias, SessionAnalysisContext context, StudentObservations observations) {
        return new StudentAnalysisRequest(
                studentAlias,
                context.lectureTitle(),
                context.classSummary(),
                context.sections(),
                observations,
                StudentSignalDigest.fold(observations, context.sections()));
    }

    public static StudentAnalysisRequest digested(
            String studentAlias, SessionAnalysisContext context, List<StudentSectionSignal> signals) {
        return new StudentAnalysisRequest(
                studentAlias, context.lectureTitle(), context.classSummary(), context.sections(), null, signals);
    }

    /**
     * GMS 사용자 메시지에 실릴 본문 그대로.
     *
     * <p><b>재는 쪽과 보내는 쪽이 같은 값을 써야 한다.</b> 길이 가드는 이 맵을 직렬화해 크기를 재고 어댑터는 이 맵을 그대로 보낸다. 예전에는 둘이 각자 맵을 만들었고, 그때 가드가
     * {@code lectureTitle}·{@code classSummary}·{@code student} 를 빼먹어 실제 전송량보다 작게 쟀다. 한쪽에만 필드를 더하면 다시 벌어지므로 여기 하나만 둔다.
     *
     * <p>{@code observations} 는 길이 가드가 발동하면 빠진다. {@code sectionSignals} 는 항상 실린다.
     */
    public Map<String, Object> promptPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("student", studentAlias);
        payload.put("lectureTitle", lectureTitle == null ? "" : lectureTitle);
        payload.put("classSummary", classSummary == null ? "" : classSummary);
        payload.put("sections", sections);
        payload.put("sectionSignals", signals);
        if (observations != null) {
            payload.put("observations", observations);
        }
        return payload;
    }
}
