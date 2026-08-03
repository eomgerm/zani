package com.a105.zani.postclass.application.port;

import java.util.List;

import com.a105.zani.postclass.application.analyzestudents.ConceptSection;
import com.a105.zani.postclass.application.analyzestudents.SessionAnalysisContext;
import com.a105.zani.postclass.application.analyzestudents.StudentObservations;
import com.a105.zani.postclass.application.analyzestudents.StudentSectionSignal;

/**
 * GMS 로 보낼 입력. 담긴 값에 식별자가 없다 — 학생은 별칭, 구간은 1부터의 번호이고 세션 ID·참여자 ID 는 애초에 이 타입에 들어올 자리가 없다(GMS 가이드 §9).
 *
 * <p>관측은 원본이거나 구간별 집계다. 둘 중 하나만 채워지고, 어느 쪽인지는 길이 가드가 정한다.
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
                studentAlias, context.lectureTitle(), context.classSummary(), context.sections(), observations, null);
    }

    public static StudentAnalysisRequest digested(
            String studentAlias, SessionAnalysisContext context, List<StudentSectionSignal> signals) {
        return new StudentAnalysisRequest(
                studentAlias, context.lectureTitle(), context.classSummary(), context.sections(), null, signals);
    }

    /** 본문에 실을 관측 부분. 길이 가드도 같은 값을 재야 측정과 전송이 어긋나지 않는다. */
    public Object observationPayload() {
        return observations != null ? observations : signals;
    }
}
