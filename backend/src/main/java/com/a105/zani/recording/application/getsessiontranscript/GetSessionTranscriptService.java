package com.a105.zani.recording.application.getsessiontranscript;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetSessionTranscriptService implements GetSessionTranscriptUseCase {

    private final GetSessionTranscriptQueryPort getSessionTranscriptQueryPort;

    @Override
    @Transactional(readOnly = true)
    public Optional<GetSessionTranscriptResult> get(GetSessionTranscriptQuery query) {
        return getSessionTranscriptQueryPort.findBySessionId(query.sessionId());
    }
}
