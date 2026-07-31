package com.a105.zani.session.application.getlivestate;

import java.util.List;

import com.a105.zani.session.application.port.SessionEventSender;

/**
 * 재연결 직후 화면을 되돌리기 위한 현재 상태.
 *
 * <p>종류별로 엔드포인트를 나누지 않고 하나로 합쳤다. 재연결은 채팅만 또는 손들기만 잃는 일이 없어서, 나누면 클라이언트가 매번 여러 번 호출하고 그 사이 상태가 어긋난다.
 *
 * @param participants 이력에 등장하는 발신자를 포함한 세션 참가자 목록. 떠난 참가자의 옛 메시지도 이름을 붙일 수 있어야 하므로, LiveKit 참가자 목록만으로는 부족하다
 * @param raisedHandIdentities 지금 손을 든 참가자. 손들기(64)가 채운다 — 규격을 먼저 두어 나중에 응답 형태가 바뀌지 않게 한다
 */
public record LiveStateResult(
        List<SessionEventSender> participants, List<LiveChatMessage> chatMessages, List<String> raisedHandIdentities) {}
