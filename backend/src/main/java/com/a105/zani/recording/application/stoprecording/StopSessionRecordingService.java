package com.a105.zani.recording.application.stoprecording;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.recording.application.port.TrackEgressPort;

/**
 * 수업이 끝날 때 그 세션의 Egress 를 모두 멈춘다.
 *
 * <p>트랜잭션을 걸지 않는다 — 외부(LiveKit) 호출을 DB 트랜잭션 안에 가두지 않는 녹화 도메인의 경계를 따른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StopSessionRecordingService implements StopSessionRecordingUseCase {

    private final TrackEgressPort trackEgressPort;

    @Override
    public int stopRecording(Long sessionId) {
        int stopped = trackEgressPort.stopLiveEgress(sessionId);
        if (stopped > 0) {
            log.info("Stopped {} live egress(es) for session {}", stopped, sessionId);
        }
        return stopped;
    }
}
