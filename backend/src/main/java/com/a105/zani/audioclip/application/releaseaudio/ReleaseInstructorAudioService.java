package com.a105.zani.audioclip.application.releaseaudio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.audioclip.application.port.InstructorAudioBufferPort;

/** 메모리 반납만 수행한다. 저장소를 건드리지 않으므로 트랜잭션이 필요 없다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseInstructorAudioService implements ReleaseInstructorAudioUseCase {

    private final InstructorAudioBufferPort buffer;

    @Override
    public void release(long sessionId) {
        buffer.release(sessionId);
        log.info("Instructor audio buffer released for session {}", sessionId);
    }
}
