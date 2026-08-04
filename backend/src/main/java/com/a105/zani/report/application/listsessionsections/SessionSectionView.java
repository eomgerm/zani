package com.a105.zani.report.application.listsessionsections;

/**
 * 수업 내용 구간 하나의 경계와 제목. 다른 도메인은 {@code SessionSectionJpaEntity} 가 아니라 이 값만 받는다.
 *
 * <p>학생 개인 타임라인은 nullable 요약({@code summary})도 사용한다.
 *
 * @param startedOffsetMs 세션 시작 기준 구간 시작 <b>밀리초</b>
 * @param endedOffsetMs 세션 시작 기준 구간 종료 <b>밀리초</b>
 * @param title 248 이 채운 구간 제목. FE 가 구간에 붙일 이름이 없으면 "구간 1·2·3" 이 되므로 함께 준다
 * @param summary 248 이 채운 구간 요약. 아직 생성되지 않았으면 {@code null}
 */
public record SessionSectionView(long startedOffsetMs, long endedOffsetMs, String title, String summary) {}
