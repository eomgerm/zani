package com.a105.zani.session.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.session.domain.model.ChatMessage;
import com.a105.zani.session.infrastructure.persistence.entity.ChatMessageJpaEntity;

@Component
public class ChatMessagePersistenceMapper {

    /** {@code chat_messages.channel_type} 값. 1:1 채팅은 범위 제외라 PUBLIC 만 쓴다(2026-07-30 확정). */
    public static final String PUBLIC_CHANNEL = "PUBLIC";

    public ChatMessageJpaEntity toEntity(ChatMessage chatMessage) {
        return ChatMessageJpaEntity.builder()
                .id(chatMessage.id())
                .sessionId(chatMessage.sessionId())
                .senderParticipantId(chatMessage.senderParticipantId())
                // 공개 채팅은 수신자가 없다. 컬럼은 1:1 을 위해 남아 있지만 채우지 않는다.
                .recipientParticipantId(null)
                .channelType(PUBLIC_CHANNEL)
                .content(chatMessage.content())
                .occurredOffsetMs(chatMessage.occurredOffsetMs())
                .build();
    }

    public ChatMessage toDomain(ChatMessageJpaEntity entity) {
        return ChatMessage.reconstitute(
                entity.getId(),
                entity.getSessionId(),
                entity.getSenderParticipantId(),
                entity.getContent(),
                entity.getOccurredOffsetMs());
    }
}
