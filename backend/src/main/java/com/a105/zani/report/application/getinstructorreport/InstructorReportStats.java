package com.a105.zani.report.application.getinstructorreport;

/**
 * 강사 리포트 "한눈에 보기" 집계.
 *
 * <p><b>집중 구간 비율은 여기 없다.</b> 그 값은 {@code GET /reports/attention/group} 의 집중 흐름에서 나오고, 화면이 이미 그 응답을 받아 차트를 그린다. 같은 계산을
 * 서버가 또 하면 판정 기준(2.5 단계)이 두 곳에 생긴다.
 *
 * @param studentCount 이 수업에 들어온 적 있는 학생 수. 강사는 세지 않는다
 * @param durationSeconds 수업 길이. 종료 시각을 저장하기 전에 끝난 과거 세션이면 0 이다
 * @param questionCount 모델이 판단한 질문 수의 합. 학생 리포트가 아직 없거나 분석이 값을 내지 못했으면 {@code null} 이며 0 이 아니다
 * @param alertCount 수업 중 발생한 이해도 알림 횟수
 */
public record InstructorReportStats(long studentCount, long durationSeconds, Integer questionCount, long alertCount) {}
