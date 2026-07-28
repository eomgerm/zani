package com.a105.zani.session.application.end;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * 강사의 명시적 수업 종료. 이 유스케이스는 "누가 종료할 수 있는가"만 판단하고, 종료 자체는 {@link EndSessionUseCase}가 수행한다(가이드 §12의 단일 종료 경로). 수업을 연 강사만
 * 종료할 수 있으며, 이미 종료된 세션에 대한 요청은 멱등하게 성공한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EndSessionByInstructorService implements EndSessionByInstructorUseCase {

    private final SessionRepository sessionRepository;
    private final EndSessionUseCase endSessionUseCase;

    @Override
    public EndSessionResult endByInstructor(EndSessionByInstructorCommand command) {
        Session session = sessionRepository.findById(command.sessionId()).orElseThrow(SessionNotFoundException::new);
        if (!session.instructorId().equals(command.userId())) {
            log.warn("Non-instructor {} tried to end session {}", command.userId(), command.sessionId());
            throw new NotSessionInstructorException();
        }
        return endSessionUseCase.end(new EndSessionCommand(command.sessionId(), SessionEndReason.INSTRUCTOR_REQUEST));
    }
}
