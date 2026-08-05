package com.a105.zani.session.application.checkended;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;

@Service
@RequiredArgsConstructor
public class CheckSessionEndedService implements CheckSessionEndedUseCase {

    private final SessionRepository sessionRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean isEnded(Long sessionId) {
        return sessionRepository.findById(sessionId).map(Session::isEnded).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> endedSessionIds(Collection<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Set.of();
        }
        return sessionRepository.findByIds(sessionIds).stream()
                .filter(Session::isEnded)
                .map(Session::id)
                .collect(Collectors.toUnmodifiableSet());
    }
}
