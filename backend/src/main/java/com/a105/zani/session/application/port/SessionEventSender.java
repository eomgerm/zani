package com.a105.zani.session.application.port;

import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 이벤트를 만든 참가자. 표시에 필요한 만큼만 담는다.
 *
 * <p>이메일·회원 ID 는 넣지 않는다. 이 봉투는 세션의 모든 참가자에게 브로드캐스트되므로, 담긴 값은 전원이 볼 수 있다고 봐야 한다.
 *
 * @param identity LiveKit participant identity 와 같은 값. 프론트가 LiveKit 참가자 목록과 이 이벤트를 이어 붙이는 키다.
 * @param displayName 화면에 보여줄 이름
 * @param role 강사 배지 표시에 쓴다
 */
public record SessionEventSender(String identity, String displayName, SessionParticipantRole role) {}
