package com.a105.zani.attention.application.getattentiontimeline;

/** 종료된 수업에서 호출자 본인의 집중 흐름을 계산해 돌려준다. 학생만 부를 수 있다. */
public interface GetMyAttentionTimelineUseCase {

    GetMyAttentionTimelineResult get(GetMyAttentionTimelineQuery query);
}
