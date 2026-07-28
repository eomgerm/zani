package com.a105.zani.session.application.end;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.audioclip.application.releaseaudio.ReleaseInstructorAudioUseCase;
import com.a105.zani.session.application.exception.SessionNotFoundException;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class EndSessionService implements EndSessionUseCase {

    private final SessionRepository sessionRepository;
    private final ReleaseInstructorAudioUseCase releaseInstructorAudioUseCase;

    @Override
    @Transactional
    public EndSessionResult end(EndSessionCommand command) {
        Session session = sessionRepository.findById(command.sessionId()).orElseThrow(SessionNotFoundException::new);
        if (session.isEnded()) {
            // 이미 종료된 세션은 그대로 둔다(중복 종료 요청·재시도에 멱등).
            return new EndSessionResult(session.id(), session.status(), false);
        }
        session.end();
        Session ended = sessionRepository.save(session);
        // 코칭 오디오 버퍼는 세션당 수십 MB를 잡고 있어 종료 시 반납해야 한다. 메모리 조작뿐이라
        // 실패해도 종료를 되돌릴 이유가 없고, 되돌아가더라도 스트림이 다시 채운다.
        releaseInstructorAudioUseCase.release(ended.id());
        log.info("Session {} ended: reason={}", ended.id(), command.reason());
        return new EndSessionResult(ended.id(), ended.status(), true);
    }
}
