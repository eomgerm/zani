package com.a105.zani.attention.domain.model.timeline;

/**
 * 수업 내용 구간 하나의 경계. 10분 같은 고정 길이가 아니라 248 이 찾아낸 실제 경계다(REPORT-I-010 · REPORT-S-011).
 *
 * @param startSeconds 구간 시작 <b>초</b>. 조회 유스케이스가 주는 값은 밀리초이므로 변환해 넣는다
 * @param endSeconds 구간 종료 초
 * @param title 구간 제목
 */
public record SectionBoundary(long startSeconds, long endSeconds, String title) {}
