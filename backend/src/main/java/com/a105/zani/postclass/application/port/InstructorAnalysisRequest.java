package com.a105.zani.postclass.application.port;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.a105.zani.postclass.application.analyzeinstructor.ConceptSection;
import com.a105.zani.postclass.application.analyzeinstructor.DeliveredTip;
import com.a105.zani.postclass.application.analyzeinstructor.GroupAlert;
import com.a105.zani.postclass.application.analyzeinstructor.InstructorAnalysisContext;
import com.a105.zani.postclass.application.analyzeinstructor.PublicChat;

/**
 * GMS 로 보낼 입력. 담긴 값에 식별자가 없다 — 구간은 1부터의 번호이고 세션 ID·참여자 ID 는 애초에 이 타입에 들어올 자리가 없다.
 *
 * <p>학생별 분석(S15P11A105-249)과 달리 학생 별칭조차 없다. 강사 리포트는 학생별 신호·응답을 노출하지 않으므로(REPORT-I-002) 입력이 익명 집계와 발신자 없는 채팅뿐이다.
 */
public record InstructorAnalysisRequest(
        String lectureTitle,
        String classSummary,
        List<ConceptSection> sections,
        List<GroupAlert> alerts,
        List<Long> handRaisedOffsetsMs,
        List<DeliveredTip> tips,
        List<PublicChat> chats,
        String instructorNote) {

    public static InstructorAnalysisRequest of(InstructorAnalysisContext context) {
        return new InstructorAnalysisRequest(
                context.lectureTitle(),
                context.classSummary(),
                context.sections(),
                context.alerts(),
                context.handRaisedOffsetsMs(),
                context.tips(),
                context.chats(),
                context.instructorNote());
    }

    /** 길이 가드가 채팅을 접었을 때 쓰는 복사. 나머지 입력은 구간 수에 비례해 접을 대상이 아니다. */
    public InstructorAnalysisRequest withChats(List<PublicChat> folded) {
        return new InstructorAnalysisRequest(
                lectureTitle, classSummary, sections, alerts, handRaisedOffsetsMs, tips, folded, instructorNote);
    }

    /**
     * 본문에 실을 데이터 부분. 길이 가드도 <b>같은 값</b>을 재야 측정과 전송이 어긋나지 않는다.
     *
     * <p>{@code Map.of} 를 쓰지 않는다 — null 값을 허용하지 않고 순서도 잃는다. 프롬프트에 실리는 순서가 안정적이어야 모델 응답이 흔들리지 않는다.
     */
    public Map<String, Object> dataPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lectureTitle", lectureTitle == null ? "" : lectureTitle);
        payload.put("classSummary", classSummary == null ? "" : classSummary);
        payload.put("sections", sections == null ? List.of() : sections);
        payload.put("groupAlerts", alerts == null ? List.of() : alerts);
        payload.put("handRaisedOffsetsMs", handRaisedOffsetsMs == null ? List.of() : handRaisedOffsetsMs);
        payload.put("deliveredTips", tips == null ? List.of() : tips);
        payload.put("publicChats", chats == null ? List.of() : chats);
        payload.put("instructorNote", instructorNote == null ? "" : instructorNote);
        return payload;
    }
}
