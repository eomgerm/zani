package com.a105.zani.attention.application.polltip;

/**
 * 강사가 대기 중인 팁을 가져가면서 트리거 판정을 돌리는 입력.
 *
 * @param userId 폴링한 회원. 강사만 팁을 받는다
 */
public record PollCoachingTipCommand(Long sessionId, Long userId) {}
