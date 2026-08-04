package com.a105.zani.postclass.application.analyzeinstructor;

import java.util.List;

/**
 * 강사 분석 한 번의 입력 전부.
 *
 * <p>식별자가 없다. 세션 ID·참여자 ID 는 이 타입에 들어올 자리가 없고, 구간은 1부터의 번호다.
 *
 * <p>구간별로 접지 않는다. 집단 신호는 임계 이벤트({@code group_alerts})·손들기·팁 이력이라 세션당 수십 행이고, 구간 시각을 모델이 함께 받으므로 어느 구간의 일인지는 모델이 맞춘다.
 * 학생별 분석(S15P11A105-249)이 접은 것은 {@code attention_events} 가 검출 틱마다 한 행이라 수천 개였기 때문이고, 여기서는 그 테이블을 읽지 않는다.
 *
 * @param instructorNote 확정된 강사 메모. 없으면 null 이다
 */
public record InstructorAnalysisContext(
        String lectureTitle,
        String classSummary,
        List<ConceptSection> sections,
        List<GroupAlert> alerts,
        List<Long> handRaisedOffsetsMs,
        List<DeliveredTip> tips,
        List<PublicChat> chats,
        String instructorNote) {}
