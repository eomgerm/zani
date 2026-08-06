package com.a105.zani.postclass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobResult;
import com.a105.zani.postclass.application.advancepipelinejob.AdvancePipelineJobUseCase;
import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptCommand;
import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptResult;
import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptService;
import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptUseCase;
import com.a105.zani.postclass.application.exception.PostClassTranscriptionFailedException;
import com.a105.zani.postclass.application.port.AudioChunk;
import com.a105.zani.postclass.application.port.AudioChunkPort;
import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.application.port.PipelineJobState;
import com.a105.zani.postclass.application.port.PostClassTranscriptionPort;
import com.a105.zani.postclass.application.port.PostClassTranscriptionSettings;
import com.a105.zani.postclass.application.port.TranscriptFilterSettings;
import com.a105.zani.postclass.application.port.TranscriptPort;
import com.a105.zani.postclass.application.port.TranscriptSegment;
import com.a105.zani.postclass.application.port.TranscriptionChunk;
import com.a105.zani.postclass.application.port.TranscriptionChunkPort;
import com.a105.zani.postclass.application.port.TranscriptionResult;
import com.a105.zani.postclass.application.recordpipelinefailure.RecordPipelineFailureCommand;
import com.a105.zani.postclass.application.recordpipelinefailure.RecordPipelineFailureResult;
import com.a105.zani.postclass.application.recordpipelinefailure.RecordPipelineFailureUseCase;
import com.a105.zani.postclass.application.transcribesession.TranscribeSessionService;
import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.postclass.domain.model.TranscriptDocument;
import com.a105.zani.postclass.domain.model.TranscriptionChunkStatus;
import com.a105.zani.recording.application.gettrackfiles.GetSessionRecordingSnapshotUseCase;
import com.a105.zani.recording.application.gettrackfiles.RecordingReadiness;
import com.a105.zani.recording.application.gettrackfiles.SessionRecordingSnapshot;
import com.a105.zani.recording.application.gettrackfiles.SessionTrackFile;
import com.a105.zani.recording.domain.model.TrackSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 오케스트레이션의 계약을 검증한다.
 *
 * <p>외부 프로세스도 HTTP 도 타지 않는다. 여기서 확인하는 것은 <b>순서와 셈</b>이다 — 저장이 전이보다 먼저인지, 청크 여러 개가 실패해도 파이프라인 실패는 한 번인지, 마이크가 아닌 트랙을
 * 건드리지 않는지, 임시 파일을 지우는지. FFmpeg 실제 실행은 백엔드 이미지 통합 테스트가 본다.
 */
class TranscribeSessionServiceTest {

    private static final Long SESSION_ID = 9_500_001L;
    private static final Long INSTRUCTOR = 9_500_101L;
    private static final Long STUDENT = 9_500_102L;
    private static final Long MIC_FILE = 9_500_201L;
    private static final Long STUDENT_MIC_FILE = 9_500_202L;
    private static final Long SCREEN_AUDIO_FILE = 9_500_203L;
    private static final Instant NOW = Instant.parse("2026-08-04T03:00:00Z");
    private static final Instant QUEUED_AT = NOW.minus(Duration.ofMinutes(10));
    private static final long CHUNK_MS = 600_000L;

    @TempDir
    Path tempDir;

    private final List<String> calls = new ArrayList<>();
    private final List<RecordPipelineFailureCommand> pipelineFailures = new ArrayList<>();
    private final List<PipelineStatus> advanced = new ArrayList<>();
    private final List<TranscriptDocument> savedDocuments = new ArrayList<>();

    private FakeChunkPort chunkPort;
    private FakeTranscriptionPort transcriptionPort;
    private List<SessionTrackFile> tracks;
    private RecordingReadiness readiness;
    private Path sourceRoot;
    private Path workDir;

    @BeforeEach
    void setUp() throws IOException {
        sourceRoot = Files.createDirectories(tempDir.resolve("source"));
        workDir = Files.createDirectories(tempDir.resolve("work"));
        chunkPort = new FakeChunkPort();
        transcriptionPort = new FakeTranscriptionPort();
        tracks = new ArrayList<>();
        readiness = RecordingReadiness.SETTLED;
    }

    // ---- 대역 ----

    /** 같은 스레드에서 바로 실행한다. 병렬성이 아니라 순서와 셈을 보는 테스트다. */
    private static final Executor DIRECT = Runnable::run;

    private static SessionTrackFile track(Long fileId, Long participantId, TrackSource source, String key) {
        return new SessionTrackFile(fileId, participantId, source, key, "TR_" + fileId, 0L, CHUNK_MS);
    }

    private final class FakeChunkPort implements TranscriptionChunkPort {
        private final Map<Long, TranscriptionChunk> rows = new HashMap<>();
        private final Map<Long, Integer> tokens = new HashMap<>();
        private boolean refuseClaim;

        void seed(Long fileId, int chunkIndex, TranscriptionChunkStatus status) {
            long id = fileId * 1_000 + chunkIndex;
            rows.put(
                    id,
                    new TranscriptionChunk(
                            id,
                            SESSION_ID,
                            fileId,
                            chunkIndex,
                            chunkIndex * CHUNK_MS,
                            (chunkIndex + 1) * CHUNK_MS,
                            status,
                            0,
                            null,
                            null,
                            List.of()));
        }

        private void replace(Long chunkId, TranscriptionChunkStatus status, List<TranscriptSegment> segments) {
            TranscriptionChunk old = rows.get(chunkId);
            rows.put(
                    chunkId,
                    new TranscriptionChunk(
                            old.id(),
                            old.sessionId(),
                            old.recordingFileId(),
                            old.chunkIndex(),
                            old.sourceStartMs(),
                            old.sourceEndMs(),
                            status,
                            tokens.getOrDefault(chunkId, old.attemptCount()),
                            null,
                            null,
                            segments));
        }

        @Override
        public List<TranscriptionChunk> registerAll(
                Long sessionId, Long recordingFileId, List<AudioChunk> chunks, Instant now) {
            calls.add("register:" + recordingFileId);
            for (AudioChunk chunk : chunks) {
                if (!rows.containsKey(recordingFileId * 1_000 + chunk.index())) {
                    seed(recordingFileId, chunk.index(), TranscriptionChunkStatus.PENDING);
                }
            }
            return byFile(recordingFileId);
        }

        private List<TranscriptionChunk> byFile(Long recordingFileId) {
            return rows.values().stream()
                    .filter(chunk -> chunk.recordingFileId().equals(recordingFileId))
                    .sorted((a, b) -> Integer.compare(a.chunkIndex(), b.chunkIndex()))
                    .toList();
        }

        @Override
        public List<TranscriptionChunk> findClaimable(Long recordingFileId, Instant now, int limit) {
            return byFile(recordingFileId).stream()
                    .filter(chunk -> !chunk.status().isTerminal())
                    .limit(limit)
                    .toList();
        }

        @Override
        public OptionalInt tryClaim(Long chunkId, Instant leaseUntil, Instant now) {
            if (refuseClaim) {
                return OptionalInt.empty();
            }
            int token = tokens.merge(chunkId, 1, Integer::sum);
            replace(chunkId, TranscriptionChunkStatus.PROCESSING, List.of());
            return OptionalInt.of(token);
        }

        @Override
        public boolean markSucceeded(Long chunkId, int fencingToken, List<TranscriptSegment> segments, Instant now) {
            calls.add("succeeded:" + chunkId);
            replace(chunkId, TranscriptionChunkStatus.SUCCEEDED, segments);
            return true;
        }

        @Override
        public boolean markSkippedSilent(Long chunkId, int fencingToken, Instant now) {
            replace(chunkId, TranscriptionChunkStatus.SKIPPED_SILENT, List.of());
            return true;
        }

        @Override
        public boolean markRetry(Long chunkId, int fencingToken, String error, Instant nextAttemptAt, Instant now) {
            calls.add("retry:" + chunkId);
            replace(chunkId, TranscriptionChunkStatus.PENDING, List.of());
            return true;
        }

        @Override
        public boolean markFailed(Long chunkId, int fencingToken, String error, Instant now) {
            calls.add("failed:" + chunkId);
            replace(chunkId, TranscriptionChunkStatus.FAILED, List.of());
            return true;
        }

        @Override
        public List<TranscriptionChunk> findAllBySessionId(Long sessionId) {
            return rows.values().stream()
                    .sorted((a, b) -> a.recordingFileId().equals(b.recordingFileId())
                            ? Integer.compare(a.chunkIndex(), b.chunkIndex())
                            : Long.compare(a.recordingFileId(), b.recordingFileId()))
                    .toList();
        }

        @Override
        public void deleteBySessionId(Long sessionId) {
            rows.clear();
        }
    }

    private final class FakeTranscriptionPort implements PostClassTranscriptionPort {
        private final Map<String, RuntimeException> failures = new HashMap<>();
        private long reportedDurationMs = CHUNK_MS;

        @Override
        public TranscriptionResult transcribe(Path audio, String contentType) {
            calls.add("transcribe:" + audio.getFileName());
            RuntimeException failure = failures.get(audio.getFileName().toString());
            if (failure != null) {
                throw failure;
            }
            return new TranscriptionResult(
                    reportedDurationMs, "ko", List.of(new TranscriptSegment(0, 4_000, "발화", -0.21, 0.02)));
        }
    }

    /** 넘겨받은 작업 디렉터리에 청크 파일을 실제로 만든다. 정리되는지 확인하려면 파일이 있어야 한다. */
    private final class FakeAudioChunkPort implements AudioChunkPort {
        private final int chunkCount;
        private final List<Path> splitSources = new ArrayList<>();

        private FakeAudioChunkPort(int chunkCount) {
            this.chunkCount = chunkCount;
        }

        @Override
        public List<AudioChunk> split(Path source, Path chunkWorkDir) {
            splitSources.add(source);
            calls.add("split:" + source.getFileName());
            List<AudioChunk> chunks = new ArrayList<>();
            for (int index = 0; index < chunkCount; index++) {
                Path file = chunkWorkDir.resolve("chunk-" + index + ".ogg");
                try {
                    Files.writeString(file, "ogg");
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
                chunks.add(new AudioChunk(index, file, index * CHUNK_MS, (index + 1) * CHUNK_MS, 3L));
            }
            return chunks;
        }
    }

    private TranscribeSessionService service(int chunksPerTrack) {
        return serviceWith(new FakeAudioChunkPort(chunksPerTrack));
    }

    private TranscribeSessionService serviceWith(FakeAudioChunkPort audioChunkPort) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        TranscriptPort transcriptPort = new TranscriptPort() {
            @Override
            public void save(Long sessionId, TranscriptDocument document, Instant now) {
                calls.add("save");
                savedDocuments.add(document);
            }

            @Override
            public Optional<TranscriptDocument> findBySessionId(Long sessionId) {
                return savedDocuments.isEmpty()
                        ? Optional.empty()
                        : Optional.of(savedDocuments.get(savedDocuments.size() - 1));
            }
        };
        // 오케스트레이션 테스트라 필터는 끈다. 켜 두면 fixture 의 noSpeechProb 를 바꾸는 순간 여기 기대값이
        // 함께 흔들리고, 무엇이 깨졌는지가 흐려진다. 필터 자체는 AssembleTranscriptServiceTest 가 본다.
        AssembleTranscriptUseCase assemble =
                new AssembleTranscriptService(transcriptPort, clock, TranscriptFilterSettings.disabled());
        GetSessionRecordingSnapshotUseCase trackFiles = sessionId -> new SessionRecordingSnapshot(tracks, readiness);
        AdvancePipelineJobUseCase advance = command -> {
            calls.add("advance:" + command.targetStatus());
            advanced.add(command.targetStatus());
            return new AdvancePipelineJobResult(command.targetStatus(), true);
        };
        RecordPipelineFailureUseCase failure = command -> {
            calls.add("pipelineFailure:" + command.retryable());
            pipelineFailures.add(command);
            return new RecordPipelineFailureResult(PipelineStatus.TRANSCRIBING, NOW.plusSeconds(120), null);
        };
        PipelineJobPort jobPort = new InMemoryQueuedAtPort();
        return new TranscribeSessionService(
                DIRECT,
                clock,
                new PostClassTranscriptionSettings(sourceRoot, workDir, Duration.ofMinutes(5), 2, "ko", false),
                trackFiles,
                audioChunkPort,
                chunkPort,
                transcriptionPort,
                assemble,
                advance,
                failure,
                jobPort);
    }

    /** {@code queuedAt} 만 필요한 최소 대역. 다른 메서드를 부르면 그것이 곧 계약 위반이다. */
    private static final class InMemoryQueuedAtPort implements PipelineJobPort {
        @Override
        public Optional<PipelineJobState> find(Long sessionId) {
            return Optional.of(new PipelineJobState(PipelineStatus.TRANSCRIBING, 1, QUEUED_AT, null));
        }

        @Override
        public Optional<PipelineJobState> findForUpdate(Long sessionId) {
            // 전사는 수십 분 걸리므로 잠금 읽기를 쓰면 안 된다. 부르면 실패한다.
            throw new AssertionError("오케스트레이션은 잠금 읽기를 쓰지 않아야 한다");
        }

        @Override
        public boolean enqueue(Long sessionId, Instant queuedAt) {
            throw new AssertionError("unexpected");
        }

        @Override
        public void updateStatus(Long sessionId, PipelineStatus status, Instant changedAt) {
            throw new AssertionError("전이는 AdvancePipelineJobUseCase 를 거쳐야 한다");
        }

        @Override
        public void markRetry(Long sessionId, String error, Instant nextAttemptAt, Instant changedAt) {
            throw new AssertionError("실패 기록은 RecordPipelineFailureUseCase 를 거쳐야 한다");
        }

        @Override
        public void markFailed(Long sessionId, String error, Instant changedAt) {
            throw new AssertionError("실패 기록은 RecordPipelineFailureUseCase 를 거쳐야 한다");
        }

        @Override
        public List<Long> findDueTranscriptionSessionIds(Instant now, int limit) {
            throw new AssertionError("unexpected");
        }

        @Override
        public List<Long> findDueAnalysisSessionIds(Instant now, int limit) {
            throw new AssertionError("unexpected");
        }

        @Override
        public void claimAnalysis(Long sessionId, Instant leaseUntil, Instant changedAt) {
            throw new AssertionError("전사는 분석 실행권을 잡지 않는다");
        }

        @Override
        public void clearRetryWait(Long sessionId, Instant changedAt) {
            throw new AssertionError("unexpected");
        }

        @Override
        public int requeueStalledTranscriptions(Instant now) {
            throw new AssertionError("복구는 기동 시점 전용이다. 전사 도중에 부르면 도는 세션을 되살린다");
        }

        @Override
        public List<Long> findOverdueSessionIds(Instant queuedBefore, int limit) {
            throw new AssertionError("unexpected");
        }
    }

    private long workFiles() throws IOException {
        try (var paths = Files.walk(workDir)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    // ---- 테스트 ----

    @Test
    void 저장을_끝낸_뒤에_ANALYZING_으로_옮긴다() {
        // 순서가 뒤집히면 저장 실패 시 하류 분석이 전사 없는 세션을 읽는다.
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));

        service(1).transcribe(SESSION_ID);

        assertTrue(calls.indexOf("save") < calls.indexOf("advance:ANALYZING"), "저장이 전이보다 앞서야 한다: " + calls);
        assertEquals(List.of(PipelineStatus.ANALYZING), advanced);
        assertTrue(pipelineFailures.isEmpty());
    }

    @Test
    void 청크_둘이_모두_실패해도_파이프라인_실패는_한_번만_보고한다() {
        // 청크마다 보고하면 한 번 실행했는데 단계의 5회 예산이 두 번 차감된다.
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));
        transcriptionPort.failures.put("chunk-0.ogg", new PostClassTranscriptionFailedException(true));
        transcriptionPort.failures.put("chunk-1.ogg", new PostClassTranscriptionFailedException(true));

        service(2).transcribe(SESSION_ID);

        assertEquals(2, calls.stream().filter(call -> call.startsWith("retry:")).count(), "청크 실패는 각각 남는다");
        assertEquals(1, pipelineFailures.size(), "파이프라인 실패는 실행당 한 번이다");
        assertTrue(pipelineFailures.get(0).retryable());
        assertTrue(advanced.isEmpty(), "실패했으면 ANALYZING 으로 넘기지 않는다");
    }

    @Test
    void 하나라도_비재시도_실패면_전체를_비재시도로_보고한다() {
        // 영구 실패한 청크가 있으면 기다려도 달라지지 않는다. 조립이 그것을 판정하고 여기로 올라온다.
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));
        transcriptionPort.failures.put("chunk-0.ogg", new PostClassTranscriptionFailedException(false));
        transcriptionPort.failures.put("chunk-1.ogg", new PostClassTranscriptionFailedException(true));

        service(2).transcribe(SESSION_ID);

        assertEquals(1, pipelineFailures.size());
        assertFalse(pipelineFailures.get(0).retryable());
        assertTrue(advanced.isEmpty());
    }

    @Test
    void 전부_재시도_가능한_실패면_전체를_재시도로_보고한다() {
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));
        transcriptionPort.failures.put("chunk-0.ogg", new PostClassTranscriptionFailedException(true));

        service(2).transcribe(SESSION_ID);

        assertEquals(1, pipelineFailures.size());
        assertTrue(pipelineFailures.get(0).retryable());
    }

    @Test
    void 마이크가_아닌_트랙은_전사하지_않는다() {
        // 화면 공유 오디오는 녹화하되 MVP 전사 대상이 아니다. 종류를 모르는 파일도 마이크로 단정하지 않는다.
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));
        tracks.add(track(SCREEN_AUDIO_FILE, INSTRUCTOR, TrackSource.SCREEN_SHARE_AUDIO, "raw/instructor/share.ogg"));
        tracks.add(track(9_500_204L, INSTRUCTOR, null, "raw/instructor/legacy.ogg"));

        service(1).transcribe(SESSION_ID);

        assertEquals(
                List.of("split:mic.ogg"),
                calls.stream().filter(c -> c.startsWith("split:")).toList());
        assertEquals(List.of(PipelineStatus.ANALYZING), advanced);
    }

    @Test
    void 트랙을_하나씩_처리하고_다음_트랙으로_넘어간다() {
        // 전부 먼저 자르면 작업 디렉터리 피크가 트랙 수만큼 곱해진다.
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));
        tracks.add(track(STUDENT_MIC_FILE, STUDENT, TrackSource.MICROPHONE, "raw/students/mic.ogg"));

        service(1).transcribe(SESSION_ID);

        int firstSplit = calls.indexOf("split:mic.ogg");
        int firstTranscribe = calls.indexOf("transcribe:chunk-0.ogg");
        int secondSplit = calls.lastIndexOf("split:mic.ogg");
        assertTrue(firstSplit < firstTranscribe, "첫 트랙을 자른 뒤 바로 전사한다: " + calls);
        assertTrue(firstTranscribe < secondSplit, "첫 트랙을 끝낸 뒤 다음 트랙을 자른다: " + calls);
    }

    @Test
    void 실행기_포화는_재시도_가능_실패로_보고한다() {
        // CompletionException 껍데기를 벗기지 않으면 "모르는 예외" 가 되어 비재시도로 굳는다. 포화는
        // 정상 동작이므로 그렇게 되면 잠깐 붐빈 것 때문에 세션이 최종 실패한다.
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));
        Executor saturated = command -> {
            throw new java.util.concurrent.RejectedExecutionException("saturated");
        };
        TranscribeSessionService service = new TranscribeSessionService(
                saturated,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new PostClassTranscriptionSettings(sourceRoot, workDir, Duration.ofMinutes(5), 2, "ko", false),
                sessionId -> new SessionRecordingSnapshot(tracks, readiness),
                new FakeAudioChunkPort(1),
                chunkPort,
                transcriptionPort,
                new AssembleTranscriptService(
                        new TranscriptPort() {
                            @Override
                            public void save(Long sessionId, TranscriptDocument document, Instant now) {
                                savedDocuments.add(document);
                            }

                            @Override
                            public Optional<TranscriptDocument> findBySessionId(Long sessionId) {
                                return Optional.empty();
                            }
                        },
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        TranscriptFilterSettings.disabled()),
                command -> {
                    advanced.add(command.targetStatus());
                    return new AdvancePipelineJobResult(command.targetStatus(), true);
                },
                command -> {
                    pipelineFailures.add(command);
                    return new RecordPipelineFailureResult(PipelineStatus.TRANSCRIBING, NOW.plusSeconds(120), null);
                },
                new InMemoryQueuedAtPort());

        service.transcribe(SESSION_ID);

        assertEquals(1, pipelineFailures.size());
        assertTrue(pipelineFailures.get(0).retryable(), "포화는 기다리면 풀리므로 재시도 가능이다");
        assertTrue(advanced.isEmpty());
    }

    @Test
    void 어떤_종료_경로에서도_임시_파일을_지운다() throws IOException {
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));
        transcriptionPort.failures.put("chunk-0.ogg", new PostClassTranscriptionFailedException(true));

        service(2).transcribe(SESSION_ID);

        assertEquals(0, workFiles(), "실패해도 청크 파일이 남지 않아야 한다");
        assertFalse(
                Files.exists(workDir.resolve("session-" + SESSION_ID)),
                "세션 디렉터리도 남기지 않는다. 실행마다 빈 디렉터리가 쌓이면 몇 달 뒤 inode 를 먹는다");
    }

    @Test
    void 성공한_청크는_다시_호출하지_않는다() {
        // 재기동 후 이어받은 트랙. findClaimable 이 담지 않으므로 GMS 예산을 다시 쓰지 않는다.
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));
        chunkPort.seed(MIC_FILE, 0, TranscriptionChunkStatus.SUCCEEDED);
        chunkPort.seed(MIC_FILE, 1, TranscriptionChunkStatus.PENDING);

        service(2).transcribe(SESSION_ID);

        assertEquals(
                List.of("transcribe:chunk-1.ogg"),
                calls.stream().filter(call -> call.startsWith("transcribe:")).toList());
    }

    @Test
    void 선점에_실패한_청크는_이번_실행의_실패로_세지_않는다() {
        // 다른 실행이 쥐고 있다. 그쪽이 결과를 기록하므로 여기서 실패로 세면 예산이 이중으로 깎인다.
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));
        chunkPort.refuseClaim = true;

        service(1).transcribe(SESSION_ID);

        assertTrue(calls.stream().noneMatch(call -> call.startsWith("transcribe:")), "호출하지 않는다");
        assertTrue(calls.stream().noneMatch(call -> call.startsWith("failed:")), "청크 실패로 기록하지 않는다");
        // 청크가 PENDING 으로 남았으므로 조립은 "아직 안 끝났다" 로 보고 재시도 가능 실패가 된다.
        assertEquals(1, pipelineFailures.size());
        assertTrue(pipelineFailures.get(0).retryable());
    }

    @Test
    void 보고_길이가_분할_구간과_크게_다르면_비재시도_실패로_기록한다() {
        // 올린 바이트가 우리가 생각한 구간이 아니라는 뜻이다. 다시 보내도 같은 응답이 온다.
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));
        transcriptionPort.reportedDurationMs = CHUNK_MS - 120_000;

        service(1).transcribe(SESSION_ID);

        assertTrue(calls.contains("failed:" + (MIC_FILE * 1_000)), "재시도 대기가 아니라 최종 실패다: " + calls);
        assertEquals(1, pipelineFailures.size());
        assertFalse(pipelineFailures.get(0).retryable());
    }

    @Test
    void 반올림_수준의_길이_차이는_통과시킨다() {
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));
        transcriptionPort.reportedDurationMs = CHUNK_MS - 400;

        service(1).transcribe(SESSION_ID);

        assertEquals(List.of(PipelineStatus.ANALYZING), advanced);
    }

    @Test
    void 녹화가_안정된_뒤의_빈_목록만_빈_전사로_확정한다() {
        // 아무도 마이크를 열지 않은 수업이면 빈 전사가 사실이다. 실패가 아니다.
        tracks.add(track(SCREEN_AUDIO_FILE, INSTRUCTOR, TrackSource.SCREEN_SHARE_AUDIO, "raw/instructor/share.ogg"));

        service(1).transcribe(SESSION_ID);

        assertEquals(1, savedDocuments.size());
        assertTrue(savedDocuments.get(0).segments().isEmpty());
        assertFalse(savedDocuments.get(0).partial());
        assertEquals(List.of(PipelineStatus.ANALYZING), advanced);
    }

    @Test
    void 녹화가_진행_중이면_빈_전사를_확정하지_않고_기다린다() {
        // recording_files 행은 Egress 종료 webhook 이 도착해야 만들어진다. 그 전에 빈 목록을 정상으로
        // 확정하면 ANALYZING 으로 넘어간 뒤 실제 OGG 가 도착해 영원히 전사되지 않는다.
        readiness = RecordingReadiness.IN_PROGRESS;

        service(1).transcribe(SESSION_ID);

        assertTrue(savedDocuments.isEmpty(), "전사를 저장하지 않는다");
        assertTrue(advanced.isEmpty(), "다음 단계로 넘기지 않는다");
        assertEquals(1, pipelineFailures.size());
        assertTrue(pipelineFailures.get(0).retryable(), "기다리면 끝나므로 재시도 가능이다");
    }

    @Test
    void 발화_녹화가_최종_실패했으면_비재시도로_보고한다() {
        // 실패한 Egress 는 다시 시도되지 않아 그 구간의 오디오가 존재하지 않는다. 남은 트랙만으로 만든
        // 문서는 partial=false 로 저장돼 하류가 완전한 전사로 착각한다.
        readiness = RecordingReadiness.BROKEN;
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));

        service(1).transcribe(SESSION_ID);

        assertTrue(savedDocuments.isEmpty());
        assertTrue(advanced.isEmpty());
        assertEquals(1, pipelineFailures.size());
        assertFalse(pipelineFailures.get(0).retryable());
    }

    @Test
    void 앞_청크가_대기_중이고_뒤_청크가_영구_실패면_비재시도로_보고한다() {
        // 회귀 테스트. 순서대로 확인하며 첫 예외에서 멈추면 0번의 대기 상태 때문에 retryable=true 로
        // 기록되고 1번의 영구 실패는 보이지 않는다. 그러면 8시간 예산을 다 쓴 뒤에야 최종 실패가 된다.
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));
        chunkPort.seed(MIC_FILE, 0, TranscriptionChunkStatus.PENDING);
        chunkPort.seed(MIC_FILE, 1, TranscriptionChunkStatus.FAILED);
        chunkPort.refuseClaim = true;

        service(2).transcribe(SESSION_ID);

        assertTrue(savedDocuments.isEmpty());
        assertTrue(advanced.isEmpty());
        assertEquals(1, pipelineFailures.size());
        assertFalse(pipelineFailures.get(0).retryable(), "영구 실패가 대기보다 우선한다");
    }

    @Test
    void 원본_경로에_세션_디렉터리를_넣는다() throws IOException {
        // storage_key 는 세션 루트 이후만 담는다(RecordingWebhookService.sessionRelativePath 가
        // /{sessionId}/ 까지 잘라 낸다). 실제 파일은 {sourceRoot}/{sessionId}/{storageKey} 에 있으므로
        // 세션 조각을 빼면 정상 신규 녹화도 FFmpeg 읽기 검사에서 실패한다.
        String storageKey = "raw/participants/student-002/student-002-microphone-TR_AMwJHeUVPpSG8t.ogg";
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, storageKey));
        FakeAudioChunkPort chunks = new FakeAudioChunkPort(1);

        serviceWith(chunks).transcribe(SESSION_ID);

        assertEquals(
                sourceRoot.resolve(String.valueOf(SESSION_ID)).resolve(storageKey),
                chunks.splitSources.get(0),
                "분할에 넘기는 경로에 세션 디렉터리가 있어야 한다");
    }

    @Test
    void 원본_경로가_루트를_벗어나면_비재시도_실패로_보고한다() {
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "../../etc/passwd"));

        service(1).transcribe(SESSION_ID);

        assertTrue(calls.stream().noneMatch(call -> call.startsWith("split:")), "자르지도 않는다");
        assertEquals(1, pipelineFailures.size());
        assertFalse(pipelineFailures.get(0).retryable());
    }

    @Test
    void 여러_트랙의_전사를_한_문서로_병합한다() {
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));
        tracks.add(track(STUDENT_MIC_FILE, STUDENT, TrackSource.MICROPHONE, "raw/students/mic.ogg"));

        service(1).transcribe(SESSION_ID);

        assertEquals(1, savedDocuments.size(), "전사는 세션당 한 문서다");
        assertEquals(2, savedDocuments.get(0).segments().size(), "두 화자의 발화가 함께 담긴다");
        assertEquals("ko", savedDocuments.get(0).language());
    }

    @Test
    void 조립_명령에_마이크_트랙만_넘긴다() {
        // 조립은 청크가 가리키는 파일이 트랙 목록에 있어야 한다고 요구한다. 필터가 양쪽에서 같아야 한다.
        tracks.add(track(MIC_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "raw/instructor/mic.ogg"));
        tracks.add(track(SCREEN_AUDIO_FILE, INSTRUCTOR, TrackSource.SCREEN_SHARE_AUDIO, "raw/instructor/share.ogg"));
        List<AssembleTranscriptCommand> commands = new ArrayList<>();
        TranscribeSessionService recording = spyingAssemble(commands);

        recording.transcribe(SESSION_ID);

        assertEquals(1, commands.size());
        assertEquals(1, commands.get(0).tracks().size());
        assertEquals(MIC_FILE, commands.get(0).tracks().get(0).recordingFileId());
    }

    private TranscribeSessionService spyingAssemble(List<AssembleTranscriptCommand> commands) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new TranscribeSessionService(
                DIRECT,
                clock,
                new PostClassTranscriptionSettings(sourceRoot, workDir, Duration.ofMinutes(5), 2, "ko", false),
                sessionId -> new SessionRecordingSnapshot(tracks, readiness),
                new FakeAudioChunkPort(1),
                chunkPort,
                transcriptionPort,
                command -> {
                    commands.add(command);
                    return new AssembleTranscriptResult(0, 0, 0, 0);
                },
                command -> {
                    advanced.add(command.targetStatus());
                    return new AdvancePipelineJobResult(command.targetStatus(), true);
                },
                command -> {
                    pipelineFailures.add(command);
                    return new RecordPipelineFailureResult(PipelineStatus.TRANSCRIBING, null, null);
                },
                new InMemoryQueuedAtPort());
    }
}
