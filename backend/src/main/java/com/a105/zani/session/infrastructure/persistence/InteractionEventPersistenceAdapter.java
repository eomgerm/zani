package com.a105.zani.session.infrastructure.persistence;

import org.springframework.stereotype.Component;

import com.a105.zani.session.domain.model.InteractionEvent;
import com.a105.zani.session.domain.repository.InteractionEventRepository;
import com.a105.zani.session.infrastructure.persistence.mapper.InteractionEventPersistenceMapper;
import com.a105.zani.session.infrastructure.persistence.repository.InteractionEventJpaRepository;

@Component
public class InteractionEventPersistenceAdapter implements InteractionEventRepository {

    private final InteractionEventJpaRepository interactionEventJpaRepository;
    private final InteractionEventPersistenceMapper mapper;

    public InteractionEventPersistenceAdapter(
            InteractionEventJpaRepository interactionEventJpaRepository, InteractionEventPersistenceMapper mapper) {
        this.interactionEventJpaRepository = interactionEventJpaRepository;
        this.mapper = mapper;
    }

    @Override
    public InteractionEvent save(InteractionEvent interactionEvent) {
        return mapper.toDomain(interactionEventJpaRepository.saveAndFlush(mapper.toEntity(interactionEvent)));
    }
}
