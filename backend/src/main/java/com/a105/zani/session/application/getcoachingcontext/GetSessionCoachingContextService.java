package com.a105.zani.session.application.getcoachingcontext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;

@Service
public class GetSessionCoachingContextService implements GetSessionCoachingContextUseCase {

    private final SessionRepository sessionRepository;

    public GetSessionCoachingContextService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public GetSessionCoachingContextResult get(GetSessionCoachingContextQuery query) {
        Session session = sessionRepository.findById(query.sessionId()).orElseThrow(SessionNotFoundException::new);
        return new GetSessionCoachingContextResult(session.title(), session.startedAt());
    }
}
