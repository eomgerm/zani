package com.a105.zani.session.application.getpostclasscontext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.recording.domain.model.RecordingAlias;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

@Service
@RequiredArgsConstructor
public class GetPostClassContextService implements GetPostClassContextUseCase {

    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;

    @Override
    @Transactional(readOnly = true)
    public GetPostClassContextResult get(GetPostClassContextQuery query) {
        Session session = sessionRepository.findById(query.sessionId()).orElseThrow(SessionNotFoundException::new);
        return new GetPostClassContextResult(session.startedAt(), session.endedAt(), aliasesOf(query.sessionId()));
    }

    /**
     * 참여자 id 를 화자 별칭으로 옮기는 표를 만든다. 강사는 {@code instructor}, 학생은 참여자 id 오름차순으로 {@code student-001} 부터다.
     *
     * <p>id 오름차순인 이유는 id 가 불변이라 같은 세션을 다시 분석해도 같은 별칭이 나오기 때문이다. 순번이 흔들리면 같은 학생이 실행마다 다른 화자로 보인다.
     *
     * <p>ponytail: 이 순번 규칙은 {@code RecordingWebhookService.resolveAlias} 와 같다. 규칙 자체가 8줄이라 지금은 옮겨 적었다 — 세 번째 소비자가 생기면
     * 그때 공용 리졸버로 뽑는 편이 낫다. 지금 뽑으면 웹훅 경로를 이 MR 범위 밖에서 건드리게 된다.
     */
    private Map<Long, String> aliasesOf(Long sessionId) {
        List<SessionParticipant> participants = sessionParticipantRepository.findBySessionId(sessionId);
        Map<Long, String> aliases = new LinkedHashMap<>();
        int studentOrder = 0;
        for (SessionParticipant participant : participants) {
            if (participant.role() == SessionParticipantRole.INSTRUCTOR) {
                aliases.put(participant.id(), RecordingAlias.instructor().value());
                continue;
            }
            studentOrder++;
            aliases.put(participant.id(), RecordingAlias.student(studentOrder).value());
        }
        return aliases;
    }
}
