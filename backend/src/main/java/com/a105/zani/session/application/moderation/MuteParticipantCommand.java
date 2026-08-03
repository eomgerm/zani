package com.a105.zani.session.application.moderation;

/**
 * 강사가 학생 한 명을 음소거하려는 요청.
 *
 * @param instructorUserId 요청한 회원. 강사인지는 유스케이스가 확인한다 — 화면이 보낸 역할을 믿지 않는다
 * @param targetParticipantId 음소거할 세션 참가자
 */
public record MuteParticipantCommand(Long sessionId, Long instructorUserId, Long targetParticipantId) {}
