package com.a105.zani.recording.application.gettrackfiles;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.recording.application.port.SessionRecordingProgressPort;
import com.a105.zani.recording.domain.model.RecordingFile;
import com.a105.zani.recording.domain.repository.RecordingFileRepository;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.repository.SessionRepository;

/** 세션의 녹화 산출물과 준비 상태 조회. 파일을 걸러 내지 않고 그대로 옮기며, 판정만 함께 얹는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class GetSessionRecordingSnapshotService implements GetSessionRecordingSnapshotUseCase {

    private final RecordingFileRepository recordingFileRepository;
    private final SessionRecordingProgressPort sessionRecordingProgressPort;
    private final SessionRepository sessionRepository;

    @Override
    @Transactional(readOnly = true)
    public SessionRecordingSnapshot findBySessionId(Long sessionId) {
        List<SessionTrackFile> files = recordingFileRepository.findBySessionId(sessionId).stream()
                .map(GetSessionRecordingSnapshotService::toTrackFile)
                .toList();
        return new SessionRecordingSnapshot(files, readiness(sessionId, files.size()));
    }

    /**
     * 순서가 곧 정책이다.
     *
     * <p>먼저 "아직 진행 중" 을 본다. 진행 중인 Egress 가 있으면 실패한 것이 함께 있어도 기다리는 쪽이 맞다 — 실패 여부는 다 끝난 뒤에 판정해야 하고, 진행 중인 것이 성공하면 실패가
     * 무의미해질 수도 있다.
     *
     * <p>세션이 아직 {@code ENDED} 가 아니면 무조건 진행 중이다. 정상 흐름에서는 나올 수 없다(메모 확정이 세션 종료 뒤에 일어난다). 그래도 확인하는 이유는 이 값이 뒤집혔을 때의 결과가
     * "수업 도중의 절반짜리 전사를 최종본으로 확정" 이라서다.
     */
    private RecordingReadiness readiness(Long sessionId, int fileCount) {
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !session.isEnded()) {
            log.info("Session is not ended yet, recordings cannot be settled: sessionId={}", sessionId);
            return RecordingReadiness.IN_PROGRESS;
        }
        SessionRecordingProgressPort.RecordingProgress progress = sessionRecordingProgressPort.load(sessionId);
        if (progress.stillRunning()) {
            log.info(
                    "Recordings are still running: sessionId={}, unfinished={}, undeliveredOutbox={}, files={}",
                    sessionId,
                    progress.unfinishedRecordings(),
                    progress.undeliveredOutbox(),
                    fileCount);
            return RecordingReadiness.IN_PROGRESS;
        }
        if (progress.permanentlyBroken()) {
            log.error(
                    "Speech recordings did not complete: sessionId={}, failedMicrophone={}, failedOutbox={}, files={}",
                    sessionId,
                    progress.failedMicrophoneRecordings(),
                    progress.failedOutbox(),
                    fileCount);
            return RecordingReadiness.BROKEN;
        }
        return RecordingReadiness.SETTLED;
    }

    private static SessionTrackFile toTrackFile(RecordingFile file) {
        return new SessionTrackFile(
                file.id(),
                file.sessionParticipantId(),
                file.trackSource(),
                file.storageKey(),
                file.startedOffsetMs(),
                file.endedOffsetMs());
    }
}
