package com.a105.zani.session.application.confirmconnection;

import java.time.Instant;

/**
 * 참가자의 실제 미디어 연결이 확인됐다는 사실.
 *
 * @param sessionId 연결이 확인된 세션. 참가자가 그 세션 소속인지 대조하는 데 쓴다
 * @param participantId 세션 참여자 ID
 * @param connectedAt 연결이 확인된 시각
 */
public record ConfirmParticipantConnectionCommand(Long sessionId, Long participantId, Instant connectedAt) {}
