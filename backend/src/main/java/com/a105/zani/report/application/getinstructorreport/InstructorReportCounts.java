package com.a105.zani.report.application.getinstructorreport;

/**
 * 조회 시점에 세는 값.
 *
 * <p>질문 수는 여기 없다. 그 값은 AI 가 판단해 리포트 행에 굳혀 둔 것이라(V19) 셀 대상이 아니다 — 같은 채팅을 나중에 다시 세면 모델이 다르게 판단할 수 있고, 그러면 강사가 어제 본 숫자와 오늘
 * 본 숫자가 달라진다.
 *
 * @param studentCount 이 수업에 들어온 적 있는 학생 수. 강사는 세지 않는다
 * @param alertCount 수업 중 발생한 이해도 알림 횟수
 */
public record InstructorReportCounts(long studentCount, long alertCount) {}
