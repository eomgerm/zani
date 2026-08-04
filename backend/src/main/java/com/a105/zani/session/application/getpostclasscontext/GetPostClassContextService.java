package com.a105.zani.session.application.getpostclasscontext;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;

@Service
@RequiredArgsConstructor
public class GetPostClassContextService implements GetPostClassContextUseCase {

    private final SessionRepository sessionRepository;

    @Override
    @Transactional(readOnly = true)
    public GetPostClassContextResult get(GetPostClassContextQuery query) {
        Session session = sessionRepository.findById(query.sessionId()).orElseThrow(SessionNotFoundException::new);
        return new GetPostClassContextResult(session.title(), session.startedAt(), session.endedAt());
    }
}
