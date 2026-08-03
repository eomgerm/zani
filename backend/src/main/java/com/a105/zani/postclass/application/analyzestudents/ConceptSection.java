package com.a105.zani.postclass.application.analyzestudents;

/**
 * 수업 내용 구간 하나. QueryPort 의 결과이자 LLM 요청의 입력이다.
 *
 * <p>DB 구간 ID 를 담지 않는다. 모델에게는 1부터의 구간 번호만 주고(GMS 가이드 §9 — 세션 식별자를 아예 보내지 않는다), 저장할 시각은 서버가 이 객체에서 되돌린다. 저장 대상인
 * {@code review_recommendations} 에도 구간 FK 가 없어 ID 가 필요한 자리가 없다.
 */
public record ConceptSection(
        int sectionIndex, String title, String summary, long startedOffsetMs, long endedOffsetMs) {}
