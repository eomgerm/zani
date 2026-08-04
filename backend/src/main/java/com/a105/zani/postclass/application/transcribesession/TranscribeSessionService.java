package com.a105.zani.postclass.application.transcribesession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.a105.zani.common.error.BusinessException;
import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobCommand;
import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobUseCase;
import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptCommand;
import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptResult;
import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptUseCase;
import com.a105.zani.postclass.application.exception.AudioChunkFailedException;
import com.a105.zani.postclass.application.exception.PipelineJobNotFoundException;
import com.a105.zani.postclass.application.exception.PipelineJobUnavailableException;
import com.a105.zani.postclass.application.exception.PostClassTranscriptionFailedException;
import com.a105.zani.postclass.application.exception.SessionRecordingBrokenException;
import com.a105.zani.postclass.application.exception.SessionRecordingNotSettledException;
import com.a105.zani.postclass.application.exception.TranscriptNotReadyException;
import com.a105.zani.postclass.application.exception.TranscriptStoreUnavailableException;
import com.a105.zani.postclass.application.exception.TranscriptionChunkStoreUnavailableException;
import com.a105.zani.postclass.application.exception.TranscriptionSourceInvalidException;
import com.a105.zani.postclass.application.port.AudioChunk;
import com.a105.zani.postclass.application.port.AudioChunkPort;
import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.application.port.PipelineJobState;
import com.a105.zani.postclass.application.port.PostClassTranscriptionPort;
import com.a105.zani.postclass.application.port.PostClassTranscriptionSettings;
import com.a105.zani.postclass.application.port.TranscriptionChunk;
import com.a105.zani.postclass.application.port.TranscriptionChunkPort;
import com.a105.zani.postclass.application.port.TranscriptionResult;
import com.a105.zani.postclass.application.port.TranscriptionTrack;
import com.a105.zani.postclass.application.recordpipelinefailure.RecordPipelineFailureCommand;
import com.a105.zani.postclass.application.recordpipelinefailure.RecordPipelineFailureResult;
import com.a105.zani.postclass.application.recordpipelinefailure.RecordPipelineFailureUseCase;
import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.postclass.domain.model.PostClassRetryPolicy;
import com.a105.zani.postclass.domain.model.RetryDecision;
import com.a105.zani.recording.application.gettrackfiles.GetSessionRecordingSnapshotUseCase;
import com.a105.zani.recording.application.gettrackfiles.SessionRecordingSnapshot;
import com.a105.zani.recording.application.gettrackfiles.SessionTrackFile;
import com.a105.zani.recording.domain.model.TrackSource;

/**
 * 세션 전사 오케스트레이션.
 *
 * <p>트랙을 <b>하나씩</b> 처리한다. 분할·전사·정리를 끝낸 뒤 다음 트랙으로 넘어가므로 작업 디렉터리 피크가 트랙 하나 크기로 묶인다. 트랙 전부를 먼저 자르면 그 상한이 트랙 수만큼 곱해진다 — 24명
 * 수업이면 4 GB 가 넘는다.
 *
 * <p>청크는 트랙 안에서만 병렬이다. 동시성만큼 묶어 제출하고 그 묶음이 끝나면 다음을 낸다 — GMS 실행기의 큐 용량이 동시성과 같아서 한 번에 다 던지면 거부된다.
 */
@Slf4j
@Service
public class TranscribeSessionService implements TranscribeSessionUseCase {

    /** 청크는 원본을 stream-copy 로 다시 묶은 것이라 컨테이너가 그대로 OGG 다. */
    private static final String CHUNK_CONTENT_TYPE = "audio/ogg";

    /**
     * GMS 가 보고한 길이와 CSV 구간 길이의 허용 차이.
     *
     * <p>크게 어긋난다면 우리가 올린 바이트가 우리가 생각한 구간이 아니라는 뜻이고, 그대로 두면 시간축이 밀린 전사가 저장된다.
     *
     * <p><b>등호로 비교하면 안 된다.</b> 원본 파일 둘은 소수점까지 맞았지만(326.19s / 276.40s), 잘라 낸 청크로 확인했을 때는 CSV 구간 50.718417s 에 GMS 가
     * 50.709999s 를 보고했다 — 8.4ms 차이다. 반면 분할이 잘못되면 청크 하나 단위로 어긋나 분 단위로 벗어난다. 1초는 그 둘을 가르는 폭이고, 조립 쪽 clamp 와 같은 값이다.
     */
    private static final long DURATION_TOLERANCE_MS = 1_000L;

    private final Executor gmsExecutor;
    private final Clock clock;
    private final PostClassTranscriptionSettings settings;
    private final GetSessionRecordingSnapshotUseCase getSessionRecordingSnapshotUseCase;
    private final AudioChunkPort audioChunkPort;
    private final TranscriptionChunkPort transcriptionChunkPort;
    private final PostClassTranscriptionPort transcriptionPort;
    private final AssembleTranscriptUseCase assembleTranscriptUseCase;
    private final AdvancePipelineJobUseCase advancePipelineJobUseCase;
    private final RecordPipelineFailureUseCase recordPipelineFailureUseCase;
    private final PipelineJobPort pipelineJobPort;

    public TranscribeSessionService(
            // 문자열 상수를 쓴다. 빈 이름은 infrastructure 가 정하는 값이고, 그 클래스를 여기서 import 하면
            // application 이 infrastructure 를 참조하게 된다(coach 의 팁 실행기와 같은 방식).
            @Qualifier("postclassGmsExecutor") Executor gmsExecutor,
            Clock clock,
            PostClassTranscriptionSettings settings,
            GetSessionRecordingSnapshotUseCase getSessionRecordingSnapshotUseCase,
            AudioChunkPort audioChunkPort,
            TranscriptionChunkPort transcriptionChunkPort,
            PostClassTranscriptionPort transcriptionPort,
            AssembleTranscriptUseCase assembleTranscriptUseCase,
            AdvancePipelineJobUseCase advancePipelineJobUseCase,
            RecordPipelineFailureUseCase recordPipelineFailureUseCase,
            PipelineJobPort pipelineJobPort) {
        this.gmsExecutor = gmsExecutor;
        this.clock = clock;
        this.settings = settings;
        this.getSessionRecordingSnapshotUseCase = getSessionRecordingSnapshotUseCase;
        this.audioChunkPort = audioChunkPort;
        this.transcriptionChunkPort = transcriptionChunkPort;
        this.transcriptionPort = transcriptionPort;
        this.assembleTranscriptUseCase = assembleTranscriptUseCase;
        this.advancePipelineJobUseCase = advancePipelineJobUseCase;
        this.recordPipelineFailureUseCase = recordPipelineFailureUseCase;
        this.pipelineJobPort = pipelineJobPort;
    }

    @Override
    public void transcribe(Long sessionId) {
        try {
            Instant queuedAt = queuedAt(sessionId);
            List<SessionTrackFile> tracks = microphoneTracks(sessionId);
            if (settings.silencePrefilterEnabled()) {
                // 설정만 있고 판별 구현이 없다(S15P11A105-292 Spike). 조용히 무시하면 "켰는데 왜 호출 수가
                // 그대로인가" 를 알 수 없으므로 남긴다.
                log.warn("Silence prefilter is enabled but not implemented yet, transcribing every chunk");
            }
            try {
                for (SessionTrackFile track : tracks) {
                    processTrack(sessionId, track, queuedAt);
                }
                assembleAndAdvance(sessionId, tracks);
            } finally {
                // 트랙별 정리는 processTrack 이 하지만 세션 디렉터리 자체는 남는다. 실행마다 빈 디렉터리가
                // 하나씩 쌓이면 몇 달 뒤 inode 를 먹는다 — 지우는 비용이 없으므로 여기서 접는다.
                deleteRecursively(sessionWorkDir(sessionId));
            }
        } catch (RuntimeException failure) {
            // 실행 한 번에 실패 보고도 한 번이다. 청크별 실패는 이미 체크포인트 행에 각각 남아 있다.
            recordFailure(sessionId, failure);
        }
    }

    private Path sessionWorkDir(Long sessionId) {
        return settings.workDir().resolve("session-" + sessionId);
    }

    /** 8시간 마감의 기준점. 청크마다 재시도 여부를 정할 때 쓴다. */
    private Instant queuedAt(Long sessionId) {
        return pipelineJobPort
                .find(sessionId)
                .map(PipelineJobState::queuedAt)
                .orElseThrow(PipelineJobNotFoundException::new);
    }

    /**
     * 전사 대상은 마이크 트랙뿐이다.
     *
     * <p><b>목록을 믿기 전에 준비 상태를 본다.</b> {@code recording_files} 행은 Egress 종료 webhook 이 도착해야 만들어지므로, 그 전에는 목록이 비어 있는 것과 정말로
     * 아무도 말하지 않은 것이 똑같이 보인다. 구별하지 않으면 빈 전사를 {@code partial=false} 로 확정하고 {@code ANALYZING} 으로 넘긴 뒤에 실제 OGG 가 도착해, 그 녹화는
     * 다시 전사되지 않는다.
     *
     * <p>화면 공유 오디오는 녹화하되 MVP 전사 대상에서 뺀다(V12 주석). 강사 카메라도 오디오가 없어 대상이 아니다. 트랙 종류를 모르는 파일(V12 이전 legacy, 또는 알 수 없는 값)도
     * 여기서 걸러진다 — 마이크라고 단정할 근거가 없다.
     */
    private List<SessionTrackFile> microphoneTracks(Long sessionId) {
        SessionRecordingSnapshot snapshot = getSessionRecordingSnapshotUseCase.findBySessionId(sessionId);
        switch (snapshot.readiness()) {
            case IN_PROGRESS -> throw new SessionRecordingNotSettledException();
            case BROKEN -> throw new SessionRecordingBrokenException();
            case SETTLED -> {
                /* 목록을 최종 결과로 믿을 수 있다. */
            }
        }
        List<SessionTrackFile> all = snapshot.files();
        List<SessionTrackFile> microphones = all.stream()
                .filter(file -> file.trackSource() == TrackSource.MICROPHONE)
                .toList();
        if (microphones.size() < all.size()) {
            log.info(
                    "Skipping non-microphone tracks: sessionId={}, total={}, microphones={}",
                    sessionId,
                    all.size(),
                    microphones.size());
        }
        if (microphones.isEmpty()) {
            // 녹화가 안정된 뒤의 빈 목록이므로 실패가 아니다. 아무도 마이크를 열지 않은 수업이면 빈 전사가
            // 사실이고, 조립이 그것을 저장한다.
            log.warn("No microphone track to transcribe after recordings settled: sessionId={}", sessionId);
        }
        return microphones;
    }

    /**
     * 트랙 하나를 분할하고 청크를 전사한다.
     *
     * <p>작업 디렉터리는 어떤 종료 경로에서도 지운다. 남기면 실패가 반복될 때마다 트랙 하나 크기가 쌓여 디스크를 채우고, 그러면 전사가 아니라 호스트가 멈춘다.
     */
    private void processTrack(Long sessionId, SessionTrackFile track, Instant queuedAt) {
        Path source = resolveSource(track);
        Path trackWorkDir = sessionWorkDir(sessionId).resolve("file-" + track.recordingFileId());
        try {
            Files.createDirectories(trackWorkDir);
            List<AudioChunk> chunks = audioChunkPort.split(source, trackWorkDir);
            // 등록은 기록된 경계와 대조까지 한다. 어긋나면 여기서 비재시도 실패로 끊긴다.
            transcriptionChunkPort.registerAll(sessionId, track.recordingFileId(), chunks, clock.instant());
            transcribeChunks(track, chunks, queuedAt);
        } catch (IOException exception) {
            throw new AudioChunkFailedException(exception);
        } finally {
            deleteRecursively(trackWorkDir);
        }
    }

    /**
     * {@code storageKey} 를 원본 루트 아래 절대 경로로 푼다.
     *
     * <p>{@code normalize()} 뒤에 루트 아래인지 다시 본다. 쓰기 시점에 검증된 값이지만 읽는 쪽에서 한 번 더 보는 비용이 거의 없고, 통과하면 임의 경로를 읽게 되는 종류의 실수다.
     */
    private Path resolveSource(SessionTrackFile track) {
        String storageKey = track.storageKey();
        if (storageKey == null || storageKey.isBlank()) {
            log.error("Track has no storage key: recordingFileId={}", track.recordingFileId());
            throw new TranscriptionSourceInvalidException();
        }
        Path root = settings.sourceRoot().toAbsolutePath().normalize();
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            log.error("Track path escapes the source root: recordingFileId={}", track.recordingFileId());
            throw new TranscriptionSourceInvalidException();
        }
        return resolved;
    }

    /**
     * 아직 처리할 청크를 동시성만큼 묶어 전사한다.
     *
     * <p>대상 목록을 <b>한 번만</b> 조회한다. 묶음마다 다시 조회하면 선점에 실패한 청크(다른 실행이 쥐고 있다)가 계속 목록에 남아 같은 묶음을 무한히 반복한다. 한 번 스냅샷을 떠 두면 각 청크는
     * 이 실행에서 정확히 한 번만 시도된다.
     */
    private void transcribeChunks(SessionTrackFile track, List<AudioChunk> chunks, Instant queuedAt) {
        Map<Integer, AudioChunk> byIndex = new HashMap<>();
        for (AudioChunk chunk : chunks) {
            byIndex.put(chunk.index(), chunk);
        }
        List<TranscriptionChunk> claimable =
                transcriptionChunkPort.findClaimable(track.recordingFileId(), clock.instant(), chunks.size());
        if (claimable.isEmpty()) {
            // 재기동 후 이어받은 트랙이다. 성공한 청크는 findClaimable 이 담지 않으므로 다시 호출되지 않는다.
            log.info("Every chunk already finished: recordingFileId={}", track.recordingFileId());
            return;
        }
        for (int from = 0; from < claimable.size(); from += settings.concurrency()) {
            int to = Math.min(from + settings.concurrency(), claimable.size());
            List<CompletableFuture<Void>> batch = claimable.subList(from, to).stream()
                    .map(chunk ->
                            CompletableFuture.runAsync(() -> transcribeChunk(chunk, byIndex, queuedAt), gmsExecutor))
                    .toList();
            CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new)).join();
        }
    }

    /**
     * 청크 하나를 선점해 전사하고 결과를 기록한다.
     *
     * <p><b>예외를 올리지 않는다.</b> 올리면 {@code join()} 이 터져 같은 트랙의 남은 묶음이 처리되지 않고, 한 청크의 429 가 트랙 전체를 멈춘다. 실패는 체크포인트 행에 남고 전체
     * 판정은 조립이 한다.
     */
    private void transcribeChunk(TranscriptionChunk chunk, Map<Integer, AudioChunk> byIndex, Instant queuedAt) {
        OptionalInt claimed;
        try {
            Instant now = clock.instant();
            claimed = transcriptionChunkPort.tryClaim(chunk.id(), now.plus(settings.leaseDuration()), now);
        } catch (RuntimeException exception) {
            log.error("Could not claim a chunk: chunkId={}", chunk.id(), exception);
            return;
        }
        if (claimed.isEmpty()) {
            // 다른 실행이 먼저 가져갔다. 이 실행의 실패로 세지 않는다 — 그쪽이 결과를 기록한다.
            log.debug("Chunk already claimed elsewhere: chunkId={}", chunk.id());
            return;
        }
        int fencingToken = claimed.getAsInt();
        AudioChunk audio = byIndex.get(chunk.chunkIndex());
        if (audio == null) {
            // 경계 대조를 통과했으므로 정상적으로는 불가능하다. 다시 잘라도 같으므로 재시도하지 않는다.
            log.error(
                    "No split output for a registered chunk: recordingFileId={}, chunkIndex={}",
                    chunk.recordingFileId(),
                    chunk.chunkIndex());
            recordChunkFailure(chunk, fencingToken, "SPLIT_OUTPUT_MISSING", false, queuedAt);
            return;
        }
        try {
            TranscriptionResult result = transcriptionPort.transcribe(audio.file(), CHUNK_CONTENT_TYPE);
            verifyDuration(chunk, audio, result);
            if (!transcriptionChunkPort.markSucceeded(chunk.id(), fencingToken, result.segments(), clock.instant())) {
                log.warn("Discarding a transcription result that lost its lease: chunkId={}", chunk.id());
            }
        } catch (PostClassTranscriptionFailedException failure) {
            recordChunkFailure(chunk, fencingToken, failure.errorCode().code(), failure.retryable(), queuedAt);
        } catch (RuntimeException failure) {
            log.error("Unexpected failure while transcribing a chunk: chunkId={}", chunk.id(), failure);
            recordChunkFailure(chunk, fencingToken, reason(failure), isRetryable(failure), queuedAt);
        }
    }

    /**
     * GMS 가 보고한 길이가 우리가 자른 구간과 맞는지 본다.
     *
     * <p>어긋난다면 올린 바이트가 우리가 생각한 구간이 아니다. 그대로 두면 시간축이 밀린 전사가 저장되고, 나중에 그것을 되돌릴 근거가 없다. 응답에 이미 들어 있는 값이라 검사에 드는 비용이 없다.
     */
    private void verifyDuration(TranscriptionChunk chunk, AudioChunk audio, TranscriptionResult result) {
        long gap = Math.abs(result.durationMs() - audio.durationMs());
        if (gap > DURATION_TOLERANCE_MS) {
            log.error(
                    "Reported audio length disagrees with the split boundary: recordingFileId={}, chunkIndex={},"
                            + " csvDurationMs={}, reportedDurationMs={}",
                    chunk.recordingFileId(),
                    chunk.chunkIndex(),
                    audio.durationMs(),
                    result.durationMs());
            // 재시도해도 같은 바이트를 올려 같은 응답을 받는다.
            throw new PostClassTranscriptionFailedException(false);
        }
    }

    /**
     * 청크 실패를 체크포인트에 기록한다. 재시도 예산은 파이프라인과 같은 정책을 쓴다.
     *
     * <p>{@code pipeline_jobs} 를 건드리지 않는다 — 실행 한 번의 파이프라인 실패는 마지막에 한 번만 보고한다.
     *
     * <p>시도 횟수로 선점 토큰을 그대로 쓴다. 그 값이 방금 올린 뒤의 {@code attemptCount} 이므로 이번 시도를 포함한 정확한 횟수다.
     */
    private void recordChunkFailure(
            TranscriptionChunk chunk, int fencingToken, String reason, boolean retryable, Instant queuedAt) {
        Instant now = clock.instant();
        RetryDecision decision = PostClassRetryPolicy.decide(fencingToken, queuedAt, now, retryable);
        try {
            boolean recorded = decision.shouldRetry()
                    ? transcriptionChunkPort.markRetry(chunk.id(), fencingToken, reason, decision.nextAttemptAt(), now)
                    : transcriptionChunkPort.markFailed(chunk.id(), fencingToken, reason, now);
            if (!recorded) {
                log.warn("Discarding a chunk failure that lost its lease: chunkId={}", chunk.id());
            } else if (!decision.shouldRetry()) {
                log.error(
                        "Chunk permanently failed: chunkId={}, attempt={}, reason={}, giveUp={}",
                        chunk.id(),
                        fencingToken,
                        reason,
                        decision.giveUpReason());
            }
        } catch (RuntimeException exception) {
            // 실패를 기록하려다 실패했다. 청크는 PROCESSING 으로 남고 lease 만료 후 회수된다.
            log.error("Could not record a chunk failure: chunkId={}", chunk.id(), exception);
        }
    }

    /**
     * 조립·저장 뒤에 단계를 옮긴다. <b>순서를 바꾸면 안 된다.</b>
     *
     * <p>단계를 먼저 옮기면 저장이 실패했을 때 하류 분석이 전사 없는 세션을 읽는다. 반대로 저장 후 전이 전에 서버가 죽어도 복구된다 — 재실행이 같은 체크포인트에서 같은 문서를 만들고 upsert 가
     * 멱등이라 내용이 바뀌지 않는다.
     */
    private void assembleAndAdvance(Long sessionId, List<SessionTrackFile> tracks) {
        List<TranscriptionChunk> chunks = transcriptionChunkPort.findAllBySessionId(sessionId);
        AssembleTranscriptResult assembled = assembleTranscriptUseCase.assemble(
                new AssembleTranscriptCommand(sessionId, settings.language(), toTranscriptionTracks(tracks), chunks));
        advancePipelineJobUseCase.advance(new AdvancePipelineJobCommand(sessionId, PipelineStatus.ANALYZING));
        log.info(
                "Transcription finished: sessionId={}, tracks={}, chunks={}, segments={}, speakers={}",
                sessionId,
                tracks.size(),
                chunks.size(),
                assembled.segmentCount(),
                assembled.speakerCount());
    }

    private List<TranscriptionTrack> toTranscriptionTracks(List<SessionTrackFile> tracks) {
        return tracks.stream()
                .map(track -> new TranscriptionTrack(
                        track.recordingFileId(),
                        track.sessionParticipantId(),
                        track.trackSource(),
                        track.startedOffsetMs()))
                .toList();
    }

    /**
     * 이번 실행의 실패를 한 번 보고한다.
     *
     * <p>여러 청크가 실패했어도 여기까지 올라오는 것은 조립이 내린 하나의 판정이다 — 종결되지 않은 청크가 남았으면 재시도 가능, 영구 실패한 청크가 하나라도 있으면 재시도 불가. 청크마다 보고하면 한 번
     * 실행했는데 단계의 5회 예산이 여러 번 차감된다.
     */
    private void recordFailure(Long sessionId, RuntimeException failure) {
        boolean retryable = isRetryable(failure);
        log.error("Transcription failed: sessionId={}, retryable={}", sessionId, retryable, failure);
        try {
            RecordPipelineFailureResult result = recordPipelineFailureUseCase.record(
                    new RecordPipelineFailureCommand(sessionId, reason(failure), retryable));
            log.info(
                    "Recorded transcription failure: sessionId={}, status={}, nextAttemptAt={}, giveUp={}",
                    sessionId,
                    result.status(),
                    result.nextAttemptAt(),
                    result.giveUpReason());
        } catch (RuntimeException exception) {
            // 실패를 기록하려다 실패했다. 작업은 TRANSCRIBING 으로 남고 SLA 경보가 결국 잡는다.
            log.error("Could not record the transcription failure: sessionId={}", sessionId, exception);
        }
    }

    /**
     * 실패를 재시도 여부로 가른다. 조립·저장 계층이 예외 종류로 이미 판정해 둔 것을 그대로 옮긴다.
     *
     * <p>모르는 예외는 재시도하지 않는다. 정체를 모르는 실패는 대개 버그이고, 버그는 기다려도 낫지 않는다 — 재시도로 두면 8시간 예산을 태운 뒤에야 드러난다.
     *
     * <p>{@link CompletionException} 은 껍데기라 벗긴다. 청크를 병렬로 돌리므로 {@code join()} 이 원인을 이것으로 감싸는데, 그대로 보면 무엇이든 "모르는 예외" 가 되어
     * 재시도 가능한 실패까지 최종 실패로 굳는다.
     */
    private boolean isRetryable(RuntimeException failure) {
        if (failure instanceof CompletionException wrapper && wrapper.getCause() instanceof RuntimeException cause) {
            return isRetryable(cause);
        }
        if (failure instanceof PostClassTranscriptionFailedException gmsFailure) {
            return gmsFailure.retryable();
        }
        // 실행기 포화는 정상 동작이라 재시도 대상이다. 지금 구성(오케스트레이션 1개, 묶음마다 join)에서는
        // 나올 수 없지만, 동시성 설정이 바뀌면 나올 수 있고 그때 최종 실패로 굳으면 원인을 찾기 어렵다.
        return failure instanceof RejectedExecutionException
                || failure instanceof TranscriptNotReadyException
                || failure instanceof SessionRecordingNotSettledException
                || failure instanceof TranscriptStoreUnavailableException
                || failure instanceof TranscriptionChunkStoreUnavailableException
                || failure instanceof PipelineJobUnavailableException
                || failure instanceof AudioChunkFailedException;
    }

    /**
     * 실패 사유 문자열.
     *
     * <p>오류 코드나 예외 이름만 남긴다. 예외 메시지를 그대로 넣지 않는 이유는 그 안에 전사 원문이나 요청 URL 이 섞일 수 있기 때문이다 — 이 값은 DB 에 남고 운영 화면에 보인다.
     *
     * <p>{@link CompletionException} 은 껍데기라 벗긴다. 그대로 두면 사유가 전부 {@code CompletionException} 으로 기록돼 아무것도 구분하지 못한다.
     */
    private String reason(RuntimeException failure) {
        if (failure instanceof CompletionException wrapper && wrapper.getCause() instanceof RuntimeException cause) {
            return reason(cause);
        }
        if (failure instanceof BusinessException business) {
            return business.errorCode().code();
        }
        return failure.getClass().getSimpleName();
    }

    /**
     * 작업 디렉터리를 지운다. 실패하면 경고만 남긴다.
     *
     * <p>여기서 예외를 올리면 정작 원인이 된 예외를 덮어쓴다({@code finally} 에서 부르기 때문이다). 지우지 못한 것은 다음 실행이 같은 경로에 다시 만들면서 덮어쓰거나, 디스크 경보가
     * 잡는다.
     */
    private void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (IOException | UncheckedIOException exception) {
            log.warn("Could not clean the transcription work directory: {}", directory, exception);
        }
    }
}
