package com.a105.zani.attention.application.getattentiontimeline;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.attention.application.exception.NotSessionStudentTimelineException;
import com.a105.zani.attention.domain.model.timeline.FocusBucket;
import com.a105.zani.attention.domain.model.timeline.FocusFlowCalculator;
import com.a105.zani.attention.domain.model.timeline.ObservationRecord;
import com.a105.zani.attention.domain.model.timeline.ParticipantReplay;
import com.a105.zani.attention.domain.model.timeline.SectionFocusCalculator;
import com.a105.zani.attention.domain.model.timeline.StateIntervalMerger;
import com.a105.zani.attention.domain.model.timeline.TimelinePolicy;
import com.a105.zani.report.application.listsessionsections.ListSessionSectionsQuery;
import com.a105.zani.report.application.listsessionsections.ListSessionSectionsUseCase;
import com.a105.zani.session.application.resolveendedsessionaccess.ResolveEndedSessionAccessQuery;
import com.a105.zani.session.application.resolveendedsessionaccess.ResolveEndedSessionAccessResult;
import com.a105.zani.session.application.resolveendedsessionaccess.ResolveEndedSessionAccessUseCase;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 호출자 본인의 집중 흐름과 상태 구간을 만든다.
 *
 * <p>참가자 식별자를 요청에서 받지 않고 접근 판정 결과에서 가져온다. 경로나 본문으로 받으면 남의 식별자를 넣어 볼 수 있는 통로가 생기고, 그 통로는 방어 코드가 아무리 있어도 언젠가 새는 쪽이 된다.
 *
 * <p>내용 구간 평균도 본인 칸 값으로 낸다. 집단 평균을 섞으면 학생 화면에 타인 비교가 들어간다(REPORT-S-010).
 */
@Service
@RequiredArgsConstructor
public class GetMyAttentionTimelineService implements GetMyAttentionTimelineUseCase {

    private final ResolveEndedSessionAccessUseCase resolveEndedSessionAccess;
    private final AttentionTimelineQueryPort queryPort;
    private final ListSessionSectionsUseCase listSessionSections;
    private final TimelinePolicy policy;

    @Override
    @Transactional(readOnly = true)
    public GetMyAttentionTimelineResult get(GetMyAttentionTimelineQuery query) {
        ResolveEndedSessionAccessResult access = resolveEndedSessionAccess.resolve(
                new ResolveEndedSessionAccessQuery(query.sessionId(), query.memberId()));
        if (access.role() != SessionParticipantRole.STUDENT) {
            throw new NotSessionStudentTimelineException();
        }

        int focusIntervalSeconds = (int) policy.focusBucket().toSeconds();
        List<ObservationRecord> observations = queryPort.observations(query.sessionId(), access.participantId());
        if (observations.isEmpty()) {
            return new GetMyAttentionTimelineResult(0L, focusIntervalSeconds, List.of(), List.of(), List.of());
        }

        long durationMs = TimelineDurations.resolveMillis(access.startedAt(), access.endedAt(), observations, policy);
        ParticipantReplay replay = ParticipantReplay.of(
                access.participantId(),
                observations,
                queryPort.prompts(query.sessionId(), access.participantId()),
                policy);

        List<FocusBucket> focusBuckets = FocusFlowCalculator.calculate(replay, durationMs, policy);

        return new GetMyAttentionTimelineResult(
                durationMs / 1000L,
                focusIntervalSeconds,
                focusBuckets,
                StateIntervalMerger.merge(replay, durationMs, policy),
                SectionFocusCalculator.calculate(
                        SectionBoundaries.from(
                                listSessionSections.list(new ListSessionSectionsQuery(query.sessionId()))),
                        focusBuckets.stream().map(FocusBucket::focusLevel).toList(),
                        policy));
    }
}
