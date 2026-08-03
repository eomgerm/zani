package com.a105.zani.attention.application.getattentiontimeline;

import java.util.ArrayList;
import java.util.List;

import com.a105.zani.attention.domain.model.timeline.ObservationRecord;
import com.a105.zani.attention.domain.model.timeline.PromptRecord;

/** 조회 포트 대역. 어떤 경로로 읽었는지를 기록해 "본인 것만 읽었는가"를 확인할 수 있게 한다. */
class FakeAttentionTimelineQueryPort implements AttentionTimelineQueryPort {

    final List<ObservationRecord> observations = new ArrayList<>();
    final List<PromptRecord> prompts = new ArrayList<>();

    /** 세션 전체 조회가 몇 번 불렸는지. 학생 경로에서는 0 이어야 한다. */
    int wholeSessionObservationCalls;

    /** 학생별 조회에 넘어온 참가자 식별자. */
    final List<Long> observedParticipantIds = new ArrayList<>();

    @Override
    public List<ObservationRecord> observations(long sessionId) {
        wholeSessionObservationCalls++;
        return List.copyOf(observations);
    }

    @Override
    public List<ObservationRecord> observations(long sessionId, long participantId) {
        observedParticipantIds.add(participantId);
        return observations.stream()
                .filter(record -> record.participantId() == participantId)
                .toList();
    }

    @Override
    public List<PromptRecord> prompts(long sessionId) {
        return List.copyOf(prompts);
    }

    @Override
    public List<PromptRecord> prompts(long sessionId, long participantId) {
        return prompts.stream()
                .filter(record -> record.participantId() == participantId)
                .toList();
    }
}
