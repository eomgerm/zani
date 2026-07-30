package com.a105.zani.session.application.getcoachingcontext;

/** 다른 도메인이 수업 제목·시작 시각만 필요할 때 쓰는 공개 조회. session 엔티티를 노출하지 않는다. */
public interface GetSessionCoachingContextUseCase {

    GetSessionCoachingContextResult get(GetSessionCoachingContextQuery query);
}
