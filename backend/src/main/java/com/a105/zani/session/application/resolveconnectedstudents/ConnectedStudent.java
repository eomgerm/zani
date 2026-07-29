package com.a105.zani.session.application.resolveconnectedstudents;

import java.time.Instant;

/**
 * 지금 접속 중인 학생 한 명과 이번 연속 접속이 시작된 시각.
 *
 * <p>"얼마나 오래 붙어 있어야 세는가"는 세션이 정하지 않는다. 코칭 집계는 1분(확정 문서 §7), 다른 소비자는 다른 값을 쓸 수 있어 판단은 호출자에게 남긴다.
 *
 * @param participantId 세션 참가자 ID. presence·판정 상태와 같은 식별자 공간이다
 * @param connectedSince 이번 연속 접속 시작 시각. 재연결하면 새로 잡힌다
 */
public record ConnectedStudent(Long participantId, Instant connectedSince) {}
