package com.a105.zani.report.application.listsessionsections;

/**
 * 수업 내용 구간 하나의 경계와 제목. 다른 도메인은 {@code SessionSectionJpaEntity} 가 아니라 이 값만 받는다.
 *
 * <p>요약({@code summary})은 담지 않는다. 타임라인이 쓰는 것은 경계와 제목뿐이고, 필요하지 않은 값을 도메인 경계 밖으로 내보낼 이유가 없다.
 *
 * @param startedOffsetMs 세션 시작 기준 구간 시작 <b>밀리초</b>
 * @param endedOffsetMs 세션 시작 기준 구간 종료 <b>밀리초</b>
 * @param title 248 이 채운 구간 제목. FE 가 구간에 붙일 이름이 없으면 "구간 1·2·3" 이 되므로 함께 준다
 */
public record SessionSectionView(long startedOffsetMs, long endedOffsetMs, String title, String summary) {}
