package com.a105.zani.attention.application.getattentiontimeline;

/** 종료된 수업의 익명 집단 참여도 타임라인을 계산해 돌려준다. 강사만 부를 수 있다. */
public interface GetGroupAttentionTimelineUseCase {

    GetGroupAttentionTimelineResult get(GetGroupAttentionTimelineQuery query);
}
