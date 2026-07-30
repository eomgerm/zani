package com.a105.zani.session.application.resolveendedsessionaccess;

import java.time.Instant;

import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 종료된 세션에 대한 접근 판정 결과. 다른 도메인은 session 엔티티가 아니라 이 값만 받는다.
 *
 * @param participantId 호출자의 세션 참가자 식별자. 본인 데이터만 읽게 하는 기준이다
 * @param role 강사·학생 구분. 어느 리포트를 열 수 있는지가 여기서 갈린다
 * @param startedAt 세션 시작 시각. 수업 안의 상대 시각(offset)을 계산하는 기준이다
 * @param endedAt 세션 종료 시각. <b>현재는 항상 {@code null} 이다</b> — {@code sessions.ended_at} 컬럼은 있지만 애플리케이션이 값을 쓰지
 *     않는다({@code SessionPersistenceMapper} 가 그 필드를 빼고 저장하고, {@code EndSessionService} 는 status 만 바꾼다). 값이 없다고 감추지 않고
 *     그대로 내보내, 받는 쪽이 있으면 쓰고 없으면 다른 근거로 대신하도록 판단을 넘긴다
 */
public record ResolveEndedSessionAccessResult(
        Long participantId, SessionParticipantRole role, Instant startedAt, Instant endedAt) {}
