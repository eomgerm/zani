package com.a105.zani.session.application.checkended;

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
}
