package com.a105.zani.session.domain.repository;

import java.util.List;

import com.a105.zani.session.domain.model.ChatMessage;

public interface ChatMessageRepository {

    ChatMessage save(ChatMessage chatMessage);

    /**
     * 공개 채팅 이력을 오래된 것부터 최대 {@code limit} 건 반환한다. 재입장 스냅샷이 쓴다.
     *
     * <p>전체를 주지 않는 이유: 세 시간짜리 수업의 채팅을 매번 다 내리면 스냅샷이 커지고, 재연결이 잦은 구간에서 그 비용이 반복된다. 화면에 필요한 만큼만 준다.
     */
    List<ChatMessage> findRecentPublic(Long sessionId, int limit);
}
