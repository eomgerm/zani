package com.a105.zani.attention.application.getattentiontimeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.attention.domain.model.timeline.DistractionIntervalDetector;
import com.a105.zani.attention.domain.model.timeline.GroupTimelineCalculator;
import com.a105.zani.attention.domain.model.timeline.GroupTimelinePoint;
import com.a105.zani.attention.domain.model.timeline.ObservationRecord;
import com.a105.zani.attention.domain.model.timeline.ParticipantReplay;
import com.a105.zani.attention.domain.model.timeline.PromptRecord;
import com.a105.zani.attention.domain.model.timeline.TimelinePolicy;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.resolveendedsessionaccess.ResolveEndedSessionAccessQuery;
import com.a105.zani.session.application.resolveendedsessionaccess.ResolveEndedSessionAccessResult;
import com.a105.zani.session.application.resolveendedsessionaccess.ResolveEndedSessionAccessUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 저장된 관측을 재생해 강사용 익명 집단 타임라인을 만든다.
 *
 * <p>사전 계산·저장을 하지 않는다. 30명 3시간이면 이벤트 32,000 행에 점 2,160 개인데, 이벤트는 1패스이고 격자 계산은 학생별 만료 시각 비교뿐이라 조회할 때마다 계산해도 충분하다. 저장하면
 * 정책을 바꿀 때마다 과거 리포트를 다시 만들어야 한다.
 */
@Service
@RequiredArgsConstructor
public class GetGroupAttentionTimelineService implements GetGroupAttentionTimelineUseCase {

    private final ResolveEndedSessionAccessUseCase resolveEndedSessionAccess;
    private final AttentionTimelineQueryPort queryPort;
    private final TimelinePolicy policy;

    @Override
    @Transactional(readOnly = true)
    public GetGroupAttentionTimelineResult get(GetGroupAttentionTimelineQuery query) {
        ResolveEndedSessionAccessResult access = resolveEndedSessionAccess.resolve(
                new ResolveEndedSessionAccessQuery(query.sessionId(), query.memberId()));
        if (access.role() != SessionParticipantRole.INSTRUCTOR) {
            throw new NotSessionInstructorException();
        }

        int intervalSeconds = (int) policy.samplingInterval().toSeconds();
        List<ObservationRecord> observations = queryPort.observations(query.sessionId());
        if (observations.isEmpty()) {
            // 계산기를 부르지 않는다. 학생이 아무도 없던 세션에 2,160 개의 빈 점을 만들 이유가 없다.
            return new GetGroupAttentionTimelineResult(intervalSeconds, 0L, List.of(), List.of());
        }

        long durationMs = TimelineDurations.resolveMillis(access.startedAt(), access.endedAt(), observations, policy);
        List<ParticipantReplay> replays = replay(observations, queryPort.prompts(query.sessionId()));
        List<GroupTimelinePoint> points = GroupTimelineCalculator.calculate(replays, durationMs, policy);

        return new GetGroupAttentionTimelineResult(
                intervalSeconds, durationMs / 1000L, points, DistractionIntervalDetector.detect(points, policy));
    }

    /** 관측과 응답을 참가자별로 묶어 재생기를 만든다. 재생은 학생 단위 상태 머신이라 섞인 채로는 돌릴 수 없다. */
    private List<ParticipantReplay> replay(List<ObservationRecord> observations, List<PromptRecord> prompts) {
        Map<Long, List<ObservationRecord>> observationsByParticipant = new LinkedHashMap<>();
        for (ObservationRecord observation : observations) {
            observationsByParticipant
                    .computeIfAbsent(observation.participantId(), key -> new ArrayList<>())
                    .add(observation);
        }
        Map<Long, List<PromptRecord>> promptsByParticipant = new LinkedHashMap<>();
        for (PromptRecord prompt : prompts) {
            promptsByParticipant
                    .computeIfAbsent(prompt.participantId(), key -> new ArrayList<>())
                    .add(prompt);
        }

        List<ParticipantReplay> replays = new ArrayList<>(observationsByParticipant.size());
        observationsByParticipant.forEach((participantId, records) -> replays.add(ParticipantReplay.of(
                participantId, records, promptsByParticipant.getOrDefault(participantId, List.of()), policy)));
        return replays;
    }
}
