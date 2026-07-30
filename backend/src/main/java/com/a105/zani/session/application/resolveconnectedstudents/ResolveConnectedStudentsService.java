package com.a105.zani.session.application.resolveconnectedstudents;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.application.port.SessionPresencePort;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 접속 중인 학생 조회. 참가자 명단은 DB 가, 지금 붙어 있는지는 presence 가 답한다.
 *
 * <p>두 곳을 합치는 순서가 중요하다. presence 키 공간을 훑으면(SCAN) 학생 수와 무관하게 키 전체를 순회해 세션이 늘어날수록 느려지고, 다른 세션의 키까지 지나간다. 그래서 <b>DB 에서 후보를
 * 먼저 얻고</b> 그 ID 들만 조회한다.
 *
 * <p>종료 절차에 들어간 세션은 빈 목록을 돌려준다. 예외를 던지면 집계가 수업 종료 직후에 실패하는데, "세는 학생이 없다"가 사실에 더 가깝다. 준비 중인 세션은 학생 행 자체가 없어 자연히 빈 목록이다.
 */
@Service
@RequiredArgsConstructor
public class ResolveConnectedStudentsService implements ResolveConnectedStudentsUseCase {

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;
    private final SessionPresencePort presencePort;

    @Override
    @Transactional(readOnly = true)
    public ResolveConnectedStudentsResult resolve(ResolveConnectedStudentsQuery query) {
        Session session = sessionRepository.findById(query.sessionId()).orElseThrow(SessionNotFoundException::new);
        if (session.isClosed()) {
            return new ResolveConnectedStudentsResult(List.of());
        }

        List<Long> studentIds = participantRepository.findBySessionId(query.sessionId()).stream()
                .filter(participant -> participant.role() == SessionParticipantRole.STUDENT)
                .map(SessionParticipant::id)
                .toList();

        Map<Long, Instant> connectedSince = presencePort.connectedSince(query.sessionId(), studentIds);
        return new ResolveConnectedStudentsResult(connectedSince.entrySet().stream()
                .map(entry -> new ConnectedStudent(entry.getKey(), entry.getValue()))
                .toList());
    }
}
