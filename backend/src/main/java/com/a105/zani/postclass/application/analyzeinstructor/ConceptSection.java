package com.a105.zani.postclass.application.analyzeinstructor;

/**
 * 수업 내용 구간 하나. QueryPort 의 결과이자 LLM 요청의 입력이다.
 *
 * <p>DB 구간 ID 를 담지 않는다. 모델에게는 1부터의 구간 번호만 주고, 저장할 시각은 서버가 이 객체에서 되돌린다(FRD §17.6).
 */
public record ConceptSection(
        int sectionIndex, String title, String summary, long startedOffsetMs, long endedOffsetMs) {}
