package com.a105.zani.session.application.getrecordingcontext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/** 녹화 manifest가 수업 시간축과 익명 화자 순서를 계산할 때 쓰는 세션 공개 조회 유스케이스. */
@Service
public class GetSessionRecordingContextService implements GetSessionRecordingContextUseCase {

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;

    public GetSessionRecordingContextService(
            SessionRepository sessionRepository, SessionParticipantRepository sessionParticipantRepository) {
        this.sessionRepository = sessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public GetSessionRecordingContextResult get(GetSessionRecordingContextQuery query) {
        Session session = sessionRepository.findById(query.sessionId()).orElseThrow(SessionNotFoundException::new);
        var participants = sessionParticipantRepository.findBySessionId(query.sessionId()).stream()
                .map(participant -> new SessionRecordingParticipant(participant.id(), participant.role()))
                .toList();
        return new GetSessionRecordingContextResult(session.startedAt(), participants);
    }
}
