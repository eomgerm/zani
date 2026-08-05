package com.a105.zani.postclass.application.analyzeinstructor;

/**
 * 수업 중 강사에게 전달된 실시간 팁 하나.
 *
 * @param triggeredOffsetMs 세션 시작 기준 발동 시각. DB 는 절대 시각으로만 갖고 있어 조회가 오프셋으로 되돌린다
 * @param tipType 팁 유형. 팁을 만들지 못한 이력이면 null 이다
 * @param outcomeStatus TIP_DELIVERED 또는 TIP_UNAVAILABLE
 */
public record DeliveredTip(long triggeredOffsetMs, String tipType, String title, String topic, String outcomeStatus) {}
