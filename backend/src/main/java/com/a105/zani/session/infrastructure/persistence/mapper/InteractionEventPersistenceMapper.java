package com.a105.zani.session.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;

import com.a105.zani.session.domain.model.InteractionEvent;
import com.a105.zani.session.domain.model.InteractionEventType;
import com.a105.zani.session.infrastructure.persistence.entity.InteractionEventJpaEntity;

@Component
public class InteractionEventPersistenceMapper {

    public InteractionEventJpaEntity toEntity(InteractionEvent interactionEvent) {
        return InteractionEventJpaEntity.builder()
                .id(interactionEvent.id())
                .sessionId(interactionEvent.sessionId())
                .actorParticipantId(interactionEvent.actorParticipantId())
                .eventType(interactionEvent.type().name())
                .occurredOffsetMs(interactionEvent.occurredOffsetMs())
                .payload(interactionEvent.payload())
                .build();
    }

    public InteractionEvent toDomain(InteractionEventJpaEntity entity) {
        return InteractionEvent.reconstitute(
                entity.getId(),
                entity.getSessionId(),
                entity.getActorParticipantId(),
                InteractionEventType.valueOf(entity.getEventType()),
                entity.getOccurredOffsetMs(),
                entity.getPayload());
    }
}
