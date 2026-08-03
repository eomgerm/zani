package com.a105.zani.recording.application.webhook;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.recording.application.exception.RecordingNotReadyException;
import com.a105.zani.recording.application.orchestrate.RequestTrackEgressCommand;
import com.a105.zani.recording.application.orchestrate.RequestTrackEgressUseCase;
import com.a105.zani.recording.application.port.AudioStreamEgressRegistryPort;
import com.a105.zani.recording.application.port.RecordingWebhookEventPort;
import com.a105.zani.recording.application.port.RecordingWebhookVerifierPort;
import com.a105.zani.recording.domain.exception.ForbiddenStudentCameraTrackException;
import com.a105.zani.recording.domain.model.Recording;
import com.a105.zani.recording.domain.model.RecordingAlias;
import com.a105.zani.recording.domain.model.RecordingFile;
import com.a105.zani.recording.domain.repository.RecordingFileRepository;
import com.a105.zani.recording.domain.repository.RecordingRepository;
import com.a105.zani.session.domain.model.Session;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;
import com.a105.zani.session.domain.repository.SessionRepository;

/**
 * LiveKit webhook 처리: 서명 검증 → 이벤트 내구 저장(event_id UNIQUE로 중복 차단) → 처리 → PROCESSED 마킹.
 *
 * <p>전 과정이 한 트랜잭션이다: 처리 중 예외가 나면 이벤트 행까지 함께 롤백되어 LiveKit 재전송에서 처음부터 재처리되고(5xx 응답), 성공하면 파일·상태·PROCESSED가 원자적으로 커밋된다. 동시
 * 중복 전송은 event_id UNIQUE 잠금이 직렬화한다. track_published는 녹화 정책을 거쳐 Track Egress를 등록하고, egress_*는 recordings 상태와
 * recording_files를 갱신한다. 상태는 역행하지 않으며(도메인 가드), 종결 전이가 실제로 일어난 경우에만 파일을 저장해 재처리 중복을 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingWebhookService implements ProcessRecordingWebhookUseCase {

    private static final String PARTICIPANT_IDENTITY_PREFIX = "p-";

    private final RecordingWebhookVerifierPort verifierPort;
    private final RecordingWebhookEventPort eventStore;
    private final RequestTrackEgressUseCase requestTrackEgressUseCase;
    private final RecordingRepository recordingRepository;
    private final RecordingFileRepository recordingFileRepository;
    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final AudioStreamEgressRegistryPort audioStreamEgressRegistry;
    private final Clock clock;

    @Override
    @Transactional
    public void process(String body, String authorizationHeader) {
        RecordingWebhookEvent event = verifierPort.verify(body, authorizationHeader);
        if (event.type() == RecordingWebhookEventType.IGNORED) {
            return;
        }
        if (!eventStore.begin(event.eventId(), event.type().name(), body)) {
            log.debug("Duplicate webhook event {} ignored", event.eventId());
            return;
        }
        if (isAudioStreamEgress(event)) {
            log.debug("Audio stream egress event {} needs no recording handling", event.eventId());
            eventStore.markProcessed(event.eventId());
            return;
        }
        switch (event.type()) {
            case TRACK_PUBLISHED -> handleTrackPublished(event);
            case EGRESS_STARTED, EGRESS_UPDATED -> handleEgressProgress(event);
            case EGRESS_ENDED -> handleEgressEnded(event);
            default -> log.debug("Webhook event {} needs no handling", event.type());
        }
        eventStore.markProcessed(event.eventId());
    }

    /**
     * 코칭용 스트림 Egress 인지. 파일을 만들지 않아 {@code recordings} 행이 없고, 아래 egress 처리는 행이 없으면 "아직 커밋 전"으로 보고 5xx 를 돌려주므로 여기서 걸러야
     * LiveKit 이 무한히 재전송하지 않는다.
     *
     * <p>근거를 두 겹으로 둔다. 이벤트 페이로드의 출력 종류가 1차다 — 외부 상태에 기대지 않아 Redis 가 죽어도 판정이 흔들리지 않는다. 페이로드에 track 정보가 없는 이벤트만 시작 시점에 남겨
     * 둔 표시로 되돌아간다.
     */
    private boolean isAudioStreamEgress(RecordingWebhookEvent event) {
        if (event.egressAudioStream() != null) {
            return event.egressAudioStream();
        }
        return audioStreamEgressRegistry.isAudioStream(event.egressId());
    }

    private void handleTrackPublished(RecordingWebhookEvent event) {
        if (event.sessionId() == null || event.trackSid() == null || event.trackSource() == null) {
            log.debug("track_published without session/track context, event={}", event.eventId());
            return;
        }
        Optional<SessionParticipant> participant = parseParticipantId(event.participantIdentity())
                .flatMap(sessionParticipantRepository::findById)
                .filter(found -> found.sessionId().equals(event.sessionId()));
        if (participant.isEmpty()) {
            log.warn(
                    "track_published from unknown identity {} in session {}",
                    event.participantIdentity(),
                    event.sessionId());
            return;
        }
        RecordingAlias alias = resolveAlias(participant.get());
        try {
            requestTrackEgressUseCase.request(new RequestTrackEgressCommand(
                    event.sessionId(),
                    event.trackSid(),
                    alias.value(),
                    participant.get().role(),
                    event.trackSource(),
                    participant.get().id()));
        } catch (ForbiddenStudentCameraTrackException securityViolation) {
            // 학생 카메라 발행은 저장 정책 위반이다. 보안 위반으로 기록만 하고 webhook은 정상 응답한다(재전송 불필요).
            log.warn(
                    "Security violation: student camera published, session={}, trackSid={}",
                    event.sessionId(),
                    event.trackSid());
        }
    }

    private void handleEgressProgress(RecordingWebhookEvent event) {
        Recording recording = findRecording(event.egressId());
        recording.markRecording();
        recordingRepository.save(recording);
    }

    private void handleEgressEnded(RecordingWebhookEvent event) {
        Recording recording = findRecording(event.egressId());
        if (event.egressComplete() == null) {
            // 종결 상태가 아닌 egress_ended(비정상 페이로드)는 상태를 건드리지 않고 대조 작업에 맡긴다.
            log.warn("egress_ended without terminal status, egressId={}", event.egressId());
            return;
        }
        if (event.egressComplete()) {
            // 전이가 실제로 일어났을 때만 파일을 저장한다 → 중복 이벤트 재처리로 파일 행이 늘어나지 않는다.
            if (recording.complete(clock.instant())) {
                saveFiles(recording, event);
            }
        } else {
            // 실패 상태 저장: FAILED로 남겨 후처리(최종 MP4)에서 누락 구간으로 다뤄지게 한다.
            recording.fail(clock.instant());
        }
        recordingRepository.save(recording);
    }

    private void saveFiles(Recording recording, RecordingWebhookEvent event) {
        Session session =
                sessionRepository.findById(recording.sessionId()).orElseThrow(RecordingNotReadyException::new);
        long timelineStartMs = session.startedAt().toEpochMilli();
        boolean trackSidTaken = false;
        for (EgressFileResult file : event.files()) {
            String relativePath = sessionRelativePath(file.filepath(), recording.sessionId());
            if (relativePath == null) {
                // 세션 루트를 특정할 수 없는 경로는 절대 경로로 저장하지 않고 건너뛴다(가이드 §14). 대조 작업의 복구 대상.
                log.warn(
                        "Egress file path outside session root, skipped: session={}, egressId={}",
                        recording.sessionId(),
                        recording.livekitEgressId());
                continue;
            }
            // storage_key는 파일의 자연 식별자이고 UNIQUE라, 이미 기록된 파일은 건너뛴다(재처리 멱등).
            if (recordingFileRepository.existsByStorageKey(relativePath)) {
                continue;
            }
            // 시작/종료 offset은 수업 타임라인 기준이다. 파일별 구간 사이의 공백이 곧 누락 구간의 근거가 된다.
            Long startedOffset = file.startedAtMs() > 0 ? Math.max(0, file.startedAtMs() - timelineStartMs) : null;
            Long endedOffset = file.endedAtMs() > 0 ? Math.max(0, file.endedAtMs() - timelineStartMs) : null;
            // UK(recording_id, livekit_track_sid)는 한 녹화에 트랙당 한 행만 허용한다. Track Egress는 트랙당 파일 하나가
            // 정상이며, 세그먼트가 여러 개로 오면 첫 행만 trackSid를 갖고 나머지는 null로 남긴다(MySQL은 NULL을 중복으로 보지 않음).
            // webhook 페이로드에 track 정보가 없을 수 있으므로 Egress 시작 시 적어 둔 recordings 의 값을 정본으로 쓴다.
            String trackSid = trackSidTaken ? null : event.egressTrackSid();
            if (trackSid == null && !trackSidTaken) {
                trackSid = recording.livekitTrackSid();
            }
            trackSidTaken = trackSidTaken || trackSid != null;
            // 화자·트랙 종류는 추정하지 않고 recordings 에 보관된 값을 그대로 옮긴다(S15P11A105-97).
            recordingFileRepository.save(RecordingFile.trackFile(
                    TsidGenerator.generate(),
                    recording.sessionId(),
                    recording.id(),
                    recording.sessionParticipantId(),
                    recording.trackSource(),
                    relativePath,
                    trackSid,
                    startedOffset,
                    endedOffset));
        }
    }

    private Recording findRecording(String egressId) {
        if (egressId == null || egressId.isBlank()) {
            throw new RecordingNotReadyException();
        }
        // egress 이벤트가 릴레이의 recordings 커밋보다 먼저 도착할 수 있다. 5xx로 응답해 재전송에서 재처리한다.
        return recordingRepository.findByLivekitEgressId(egressId).orElseThrow(RecordingNotReadyException::new);
    }

    private RecordingAlias resolveAlias(SessionParticipant participant) {
        if (participant.role() == SessionParticipantRole.INSTRUCTOR) {
            return RecordingAlias.instructor();
        }
        // 학생 순번은 세션 참가자 id 오름차순의 학생 순서로 결정한다. id는 불변이라 재호출에도 같은 별칭이 나온다.
        List<SessionParticipant> participants = sessionParticipantRepository.findBySessionId(participant.sessionId());
        int order = 0;
        for (SessionParticipant candidate : participants) {
            if (candidate.role() == SessionParticipantRole.STUDENT) {
                order++;
                if (candidate.id().equals(participant.id())) {
                    return RecordingAlias.student(order);
                }
            }
        }
        return RecordingAlias.student(order + 1);
    }

    private static Optional<Long> parseParticipantId(String identity) {
        if (identity == null || !identity.startsWith(PARTICIPANT_IDENTITY_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(identity.substring(PARTICIPANT_IDENTITY_PREFIX.length())));
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    /** Egress 노드 절대 경로에서 세션 루트 이후의 상대 경로만 추출한다(가이드 §14). 마커가 없으면 null(저장 금지). */
    private static String sessionRelativePath(String filepath, Long sessionId) {
        if (filepath == null) {
            return null;
        }
        String marker = "/" + sessionId + "/";
        int index = filepath.lastIndexOf(marker);
        return index >= 0 ? filepath.substring(index + marker.length()) : null;
    }
}
