package com.a105.zani.attention.application.getcoachingsignals;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.a105.zani.attention.application.getdenominator.GetDenominatorQuery;
import com.a105.zani.attention.application.getdenominator.GetDenominatorResult;
import com.a105.zani.attention.application.getdenominator.GetDenominatorUseCase;
import com.a105.zani.attention.application.port.AttentionStatePort;
import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.CoachingSignalSummary;

/**
 * 최근 5분 익명 집계를 계산한다(확정 문서 §7·§7.3).
 *
 * <p>분자는 {@code CONFUSED}·{@code MISSED}·{@code NON_RESPONSE}·{@code UNMEASURABLE} 을 한 번이라도 겪은 학생의 <b>합집합</b>이다. 상태별로
 * 세어 더하면 두 상태를 겪은 학생이 두 번 세어져 비율이 부풀고 30% 임계를 잘못 넘긴다.
 *
 * <p>{@code GOOD}·{@code CAMERA_OFF} 는 분자에 넣지 않는다. 카메라를 끈 학생을 문제 있는 학생으로 세면 사실상 카메라를 강제하는 셈이고(§7.2), 그쪽은 분모 제외로 따로 다룬다.
 * {@code DETECTOR_UNAVAILABLE} 은 학생 상태가 아니라 분모 제외 판단에만 쓰인다.
 *
 * <p>"최근 5분"은 유의 상태 표시의 TTL 이 대신한다(§7.3). 5분이 지난 표시는 저장소에서 사라지므로 창을 따로 계산하지 않는다.
 *
 * <p>폐기된 규칙은 쓰지 않는다 — 30초 창, {@code NEEDS_CHECK} 지속, 측정 가능 70% 전제, 카메라 응답 기반 분모 제외.
 */
@Service
@RequiredArgsConstructor
public class GetCoachingSignalsService implements GetCoachingSignalsUseCase {

    private final GetDenominatorUseCase getDenominatorUseCase;
    private final AttentionStatePort attentionStatePort;

    @Override
    public GetCoachingSignalsResult get(GetCoachingSignalsQuery query) {
        GetDenominatorResult denominator = getDenominatorUseCase.get(new GetDenominatorQuery(query.sessionId()));
        Set<Long> counted = denominator.denominator().participantIds();
        if (counted.isEmpty()) {
            return new GetCoachingSignalsResult(CoachingSignalSummary.empty());
        }

        Map<AttentionState, Set<Long>> byState = attentionStatePort.significantParticipants(query.sessionId(), counted);

        Map<AttentionState, Integer> countsByState = new EnumMap<>(AttentionState.class);
        Set<Long> significantStudents = new HashSet<>();
        byState.forEach((state, participants) -> {
            if (participants.isEmpty()) {
                return;
            }
            countsByState.put(state, participants.size());
            significantStudents.addAll(participants);
        });

        return new GetCoachingSignalsResult(
                new CoachingSignalSummary(counted.size(), significantStudents.size(), countsByState));
    }
}
