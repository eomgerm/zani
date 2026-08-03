package com.a105.zani.attention.application.getattentiontimeline;

import java.util.List;

import com.a105.zani.attention.domain.model.timeline.ObservationRecord;
import com.a105.zani.attention.domain.model.timeline.PromptRecord;

/**
 * 타임라인 재생에 쓸 원본 이력을 읽는다.
 *
 * <p>집계 결과가 아니라 원본 행을 그대로 읽는 이유는 계산이 도메인 쪽에 있기 때문이다. SQL 로 미리 묶으면 임계값이 쿼리 안에 흩어져 정책을 한 곳에서 고칠 수 없게 된다.
 */
public interface AttentionTimelineQueryPort {

    /** 세션 전체 학생의 검출기 관측을 관측 시각 오름차순으로 읽는다. 슬롯 시작 기준 정렬은 재생기가 다시 한다. */
    List<ObservationRecord> observations(long sessionId);

    /** 한 학생의 검출기 관측만 읽는다. */
    List<ObservationRecord> observations(long sessionId, long participantId);

    /** 이해 확인 프롬프트 응답을 읽는다. 응답이 없는 행은 제외한다. */
    List<PromptRecord> prompts(long sessionId);

    List<PromptRecord> prompts(long sessionId, long participantId);
}
