package com.a105.zani.attention.application.getdenominator;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.a105.zani.attention.application.port.AttentionStatePort;
import com.a105.zani.attention.domain.model.CoachingDenominator;
import com.a105.zani.session.application.resolveconnectedstudents.ConnectedStudent;
import com.a105.zani.session.application.resolveconnectedstudents.ResolveConnectedStudentsQuery;
import com.a105.zani.session.application.resolveconnectedstudents.ResolveConnectedStudentsResult;
import com.a105.zani.session.application.resolveconnectedstudents.ResolveConnectedStudentsUseCase;

/**
 * 집계 분모를 계산한다(확정 문서 §7·§7.1).
 *
 * <p>두 조건을 통과한 학생만 센다.
 *
 * <ul>
 *   <li><b>연속 접속 1분</b> — 방금 들어온 학생을 바로 세면 아직 수업을 듣지도 않은 사람이 비율을 흔든다.
 *   <li><b>측정 불가 1분 미만</b> — 카메라를 켤 수 없는 학생을 분모에 남겨 두면, 그 학생이 무엇을 하든 비율이 낮아져 실제로 어려움을 겪는 학생들이 가려진다.
 * </ul>
 *
 * <p>{@code UNMEASURABLE} 은 빼지 않는다. 카메라가 켜져 있어 관측이 됐고 그 결과가 "얼굴이 없다"인 것이므로, 오히려 분자에 들어간다(§7.2). 분모에는 넣고 분자에서는 빼는 조합이 가장
 * 나쁘다 — 분자에 절대 못 들어가는 학생이 분모만 채우면 학생들이 이탈할수록 알림이 안 뜬다.
 *
 * <p>학생 응답은 분모를 바꾸지 않는다(§5.2). 판단 근거는 검출기 이벤트뿐이다.
 */
@Service
@RequiredArgsConstructor
public class GetDenominatorService implements GetDenominatorUseCase {

    private final ResolveConnectedStudentsUseCase resolveConnectedStudentsUseCase;
    private final AttentionStatePort attentionStatePort;
    private final Clock clock;

    @Override
    public GetDenominatorResult get(GetDenominatorQuery query) {
        ResolveConnectedStudentsResult connected =
                resolveConnectedStudentsUseCase.resolve(new ResolveConnectedStudentsQuery(query.sessionId()));
        if (connected.students().isEmpty()) {
            return new GetDenominatorResult(CoachingDenominator.empty(), 0);
        }

        Instant countFrom = clock.instant().minus(CoachingDenominator.REQUIRED_CONNECTION);
        Set<Long> longEnough = connected.students().stream()
                // 1분을 "넘긴" 시점부터다. 정확히 1분이면 넘긴 것으로 본다 — 59초는 빠지고 60초는 들어온다.
                .filter(student -> !student.connectedSince().isAfter(countFrom))
                .map(ConnectedStudent::participantId)
                .collect(Collectors.toSet());

        Set<Long> excluded = attentionStatePort.excludedFromDenominator(query.sessionId(), longEnough);
        Set<Long> counted =
                longEnough.stream().filter(id -> !excluded.contains(id)).collect(Collectors.toSet());
        return new GetDenominatorResult(
                new CoachingDenominator(counted), connected.students().size());
    }
}
