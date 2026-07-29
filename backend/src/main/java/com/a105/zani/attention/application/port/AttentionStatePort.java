package com.a105.zani.attention.application.port;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import com.a105.zani.attention.domain.model.AttentionState;
import com.a105.zani.attention.domain.model.DetectionRunTransition;

/**
 * 참여도 판정 상태를 Redis에 보관하는 포트. 벤더(Redis) 타입은 인프라 어댑터 안에만 존재한다.
 *
 * <p>보관하는 것은 두 가지다. 하나는 참가자의 <b>현재 상태</b>(짧은 TTL, 측정 가능 비율 계산용), 다른 하나는 <b>최근 유의 상태 흔적</b>(5분 TTL, 코칭 트리거 분자·팁 유형 선택용).
 * 둘 다 TTL로 자연 소멸하므로 별도 정리가 필요 없다.
 */
public interface AttentionStatePort {

    /**
     * 이 판정 이벤트를 처음 받았는지 기록한다. 같은 참가자가 이미 보낸 clientEventId면 false를 돌려준다(원자적).
     *
     * <p>네트워크 재시도로 같은 이벤트가 두 번 와도 상태가 중복 반영되지 않게 하는 유일한 지점이다. clientEventId는 참가자 단위로만 유일하면 된다. 세션 단위로 묶으면 클라이언트가 창 시작
     * 시각 같은 결정적 값을 쓸 때 학생끼리 충돌한다.
     */
    boolean registerEvent(long sessionId, long participantId, String clientEventId, Duration ttl);

    /** 기록해 둔 이벤트 표시를 지운다. 뒤따르는 쓰기가 실패해 판정이 반영되지 않았을 때 재시도를 다시 받기 위한 되돌리기다. */
    void clearEvent(long sessionId, long participantId, String clientEventId);

    /** 참가자의 현재 판정 상태를 TTL과 함께 기록한다. 같은 참가자의 이전 상태는 덮어쓴다. */
    void recordCurrentState(long sessionId, long participantId, AttentionSnapshot snapshot, Duration ttl);

    /**
     * 유의 상태를 5분 창에 남긴다. 창 안에 한 번이라도 있었으면 트리거 분자에 든다(확정 문서 §7.3).
     *
     * <p>상태 종류별로 따로 남긴다. 팁 유형 선택이 CONFUSED·MISSED·NON_RESPONSE·UNMEASURABLE 네 비율을 각각 요구하므로, 한 참가자가 창 안에서 두 종류를 겪었다면 둘 다
     * 세어야 한다.
     *
     * <p><b>읽는 쪽 주의</b>: 트리거 분자는 네 키 집합에 있는 참가자 ID의 <b>합집합</b>이지 개수의 합이 아니다. 두 종류를 겪은 참가자를 두 번 세면 비율이 부풀어 30% 임계를 잘못
     * 넘긴다. 유형별 비율(팁 선택)만 상태별로 따로 센다.
     */
    void markSignificant(long sessionId, long participantId, AttentionState state, Duration window);

    /**
     * 이 참가자를 집단 비율 <b>분모에서 제외</b>한다. 측정 불가 상태가 1분 이상 이어진 학생이 대상이다(확정 문서 §7.1).
     *
     * <p>카메라를 켤 수 없는 학생을 분모에 남겨 두면, 그 학생이 무엇을 하든 비율이 낮아져 실제로 어려움을 겪는 학생들이 가려진다.
     */
    void excludeFromDenominator(long sessionId, long participantId, Duration ttl);

    /** 분모 제외를 되돌린다. 카메라를 다시 쓸 수 있게 된 학생은 곧바로 분모로 돌아온다(확정 문서 §7.1). */
    void includeInDenominator(long sessionId, long participantId);

    /**
     * 주어진 참가자들 중 지금 분모에서 빠져 있는 사람.
     *
     * <p>후보를 밖에서 받는 이유는 키 공간을 훑지 않기 위해서다(SCAN 금지). 세는 후보는 presence 가 알고 있으므로 그 ID 들만 조회한다.
     */
    Set<Long> excludedFromDenominator(long sessionId, Collection<Long> participantIds);

    /**
     * 이 관측이 이미 반영된 것보다 새로우면 집계 상태에 반영한다. 더 최신 판정이 이미 반영돼 있으면 아무것도 하지 않고 빈 값을 돌려준다.
     *
     * <p>한 번에 갱신하는 것은 <b>순서 판단·연속 카운터(§4.1)·반영 지점·측정 불가 구간(§7.1)</b> 넷이다. 어떤 조작을 할지는
     * 도메인({@link DetectionRunTransition})이 정하고, 저장소는 그것을 적용하기만 한다.
     *
     * <p>넷을 쪼개지 않는 이유:
     *
     * <ul>
     *   <li>순서 판단을 밖에서 하면 겹쳐 들어온 두 판정이 둘 다 "내가 최신"으로 읽고, 나중에 쓴 옛 쪽이 반영 지점을 되돌린다.
     *   <li>카운터만 오르고 반영 지점이 빠지면 재시도가 그 사실을 알 길이 없어 같은 관측으로 카운터를 한 번 더 올린다. 3연속이 관측 두 건으로 앞당겨진다.
     *   <li>측정 불가 구간을 따로 재면 순서 판단을 통과한 관측들 사이에서도 순서가 뒤바뀔 수 있어, 구간 길이가 음수로 나온다. 여기서 재면 통과한 오프셋이 항상 증가하므로 그럴 수 없다.
     * </ul>
     *
     * @param measurementSuspended 이번 관측이 측정 불가 상태인지({@code CAMERA_OFF}·{@code DETECTOR_UNAVAILABLE})
     * @param observedOffsetMs 이 관측이 반영될 지점. 이미 반영된 지점보다 크지 않으면 거절된다
     * @return 반영 결과. 더 최신 판정이 이미 반영돼 있었다면 빈 값
     */
    Optional<ObservationApplied> applyObservation(
            long sessionId,
            long participantId,
            DetectionRunTransition transition,
            boolean measurementSuspended,
            long observedOffsetMs,
            Duration ttl);

    /**
     * 연속 카운터 두 개를 모두 0으로 되돌린다.
     *
     * <p>프롬프트가 닫히면 브라우저 카운터가 0이 되므로(§5) 서버 사본도 같이 되돌려야 한다. 그러지 않으면 서버 쪽만 3 이상으로 남아 다음 관측 한 건이 곧바로 학생 상태를 다시 확정하고 분자 마커를
     * 새로 건다.
     */
    void resetRuns(long sessionId, long participantId);
}
