package com.a105.zani.postclass.application.analyzestudents;

/**
 * 이번 실행에서 분석할 학생 하나.
 *
 * @param sessionParticipantId 내부 FK. 저장과 로그에 쓰고 GMS 로는 보내지 않는다
 * @param studentOrder 세션 참여자 ID 오름차순으로 센 <b>전체 학생</b> 중 순번(1부터). 별칭의 기준이라 이미 리포트가 있는 학생을 건너뛰어도 번호가 밀리지 않아야 한다
 */
public record AnalysisTarget(Long sessionParticipantId, int studentOrder) {}
