package com.a105.zani.session.application.sendreaction;

/** @param reaction {@link com.a105.zani.session.domain.model.ReactionKind} 의 이름. 모르는 값은 거절한다 */
public record SendReactionCommand(Long sessionId, Long userId, String clientEventId, String reaction) {}
