package com.a105.zani.attention.infrastructure.persistence;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.a105.zani.attention.domain.model.CheckPrompt;
import com.a105.zani.attention.domain.model.PromptKind;
import com.a105.zani.attention.domain.repository.CheckPromptRepository;
import com.a105.zani.attention.infrastructure.persistence.mapper.CheckPromptPersistenceMapper;
import com.a105.zani.attention.infrastructure.persistence.repository.CheckPromptJpaRepository;

@Component
@RequiredArgsConstructor
public class CheckPromptPersistenceAdapter implements CheckPromptRepository {

    private final CheckPromptJpaRepository checkPromptJpaRepository;
    private final CheckPromptPersistenceMapper mapper;

    @Override
    public Optional<CheckPrompt> findByParticipantAndKindAndShownOffset(
            Long sessionId, Long participantId, PromptKind kind, long shownOffsetMs) {
        return checkPromptJpaRepository
                .findBySessionIdAndSessionParticipantIdAndTriggerTypeAndShownOffsetMs(
                        sessionId, participantId, kind.name(), shownOffsetMs)
                .map(mapper::toDomain);
    }

    @Override
    public CheckPrompt save(CheckPrompt checkPrompt) {
        return mapper.toDomain(checkPromptJpaRepository.saveAndFlush(mapper.toEntity(checkPrompt)));
    }
}
