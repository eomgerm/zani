package com.a105.zani.session.application.resolveendedsessionaccess;

import java.time.Instant;

import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 종료된 세션에 대한 접근 판정 결과. 다른 도메인은 session 엔티티가 아니라 이 값만 받는다.
 *
 * @param participantId 호출자의 세션 참가자 식별자. 본인 데이터만 읽게 하는 기준이다
 * @param role 강사·학생 구분. 어느 리포트를 열 수 있는지가 여기서 갈린다
 * @param startedAt 세션 시작 시각. 수업 안의 상대 시각(offset)을 계산하는 기준이다
 * @param endedAt 세션 종료 시각. 저장을 시작하기 전에 종료된 과거 세션이면 {@code null} 이며, 받는 쪽이 관측 시각으로 대신한다
 */
public record ResolveEndedSessionAccessResult(
        Long participantId, SessionParticipantRole role, Instant startedAt, Instant endedAt) {}
