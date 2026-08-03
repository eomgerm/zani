package com.a105.zani.attention.domain.model.timeline;

/**
 * 학생 화면 하단 상태 막대의 구간 하나.
 *
 * <p>집중 흐름과 격자를 공유하지 않는다. 30초 칸 하나에 4단계 20초와 카메라 꺼짐 10초가 섞이면 대표 상태를 하나로 정할 수 없고, "확인 필요" 는 프롬프트 응답 기준 5분 유효라 30초 칸 열 개에
 * 걸친다(설계 문서 §2.13).
 *
 * @param startSeconds 구간 시작 시각
 * @param endSeconds 구간 종료 시각. 시작을 포함하고 끝을 포함하지 않는다
 * @param state 그 구간의 상태
 */
public record StateInterval(long startSeconds, long endSeconds, StudentTimelineState state) {}
