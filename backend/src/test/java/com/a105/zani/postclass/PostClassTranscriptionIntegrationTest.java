package com.a105.zani.postclass;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.postclass.application.port.AudioChunk;
import com.a105.zani.postclass.application.port.AudioChunkPort;
import com.a105.zani.postclass.application.port.PipelineJobPort;
import com.a105.zani.postclass.application.port.PostClassTranscriptionPort;
import com.a105.zani.postclass.application.port.PostClassTranscriptionSettings;
import com.a105.zani.postclass.application.port.TranscriptSegment;
import com.a105.zani.postclass.application.port.TranscriptionChunk;
import com.a105.zani.postclass.application.port.TranscriptionChunkPort;
import com.a105.zani.postclass.application.port.TranscriptionResult;
import com.a105.zani.postclass.application.recoverstalledtranscriptions.RecoverStalledTranscriptionsUseCase;
import com.a105.zani.postclass.application.starttranscription.TryStartTranscriptionUseCase;
import com.a105.zani.postclass.application.transcribesession.TranscribeSessionUseCase;
import com.a105.zani.postclass.domain.model.PipelineStatus;
import com.a105.zani.postclass.domain.model.TranscriptionChunkStatus;
import com.a105.zani.postclass.infrastructure.scheduler.PostClassTranscriptionScheduler;
import com.a105.zani.recording.domain.model.RecordingStatus;
import com.a105.zani.recording.domain.model.TrackSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 사후 전사 파이프라인 전체를 실제 MySQL 로 검증한다.
 *
 * <p>FFmpeg 과 GMS 만 대역이다. 그 둘은 외부 프로세스·네트워크라 자동화에 넣으면 결과가 환경에 흔들린다 — 실제 바이너리와 실제 GMS 는 최종 백엔드 이미지 smoke 에서 한 번 잇는다.
 * 나머지는 모두 진짜다: 체크포인트 테이블, {@code transcripts} JSON 컬럼, {@code pipeline_jobs} 전이, 녹화 준비 판정, lease 회수와 fencing.
 *
 * <p>대역으로 바꿀 수 없는 것들이 여기서만 드러난다 — 세 겹의 오프셋이 JSON 에 정확히 남는지, 재실행이 행을 늘리지 않는지, GMS 를 기다리는 동안 {@code pipeline_jobs} 행 잠금을
 * 붙잡지 않는지.
 */
@SpringBootTest
class PostClassTranscriptionIntegrationTest {

    private static final Instant START = Instant.parse("2026-08-04T06:00:00Z");
    private static final long CHUNK_MS = 600_000L;

    /** 시각을 앞으로 밀 수 있는 시계. lease 만료와 재시도 기한을 실제로 넘겨 봐야 한다. */
    static final class MutableClock extends Clock {
        private Instant now = START;

        void set(Instant instant) {
            now = instant;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 넘겨받은 작업 디렉터리에 청크 파일을 실제로 만든다. 정리 여부를 보려면 파일이 있어야 한다. */
    static final class ScriptedAudioChunkPort implements AudioChunkPort {
        int chunksPerTrack = 1;
        final List<Path> splitSources = new ArrayList<>();

        @Override
        public List<AudioChunk> split(Path source, Path workDir) {
            splitSources.add(source);
            List<AudioChunk> chunks = new ArrayList<>();
            for (int index = 0; index < chunksPerTrack; index++) {
                Path file = workDir.resolve("chunk-" + index + ".ogg");
                try {
                    Files.writeString(file, "ogg-bytes");
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                chunks.add(new AudioChunk(index, file, index * CHUNK_MS, (index + 1) * CHUNK_MS, 9L));
            }
            return chunks;
        }

        void reset() {
            chunksPerTrack = 1;
            splitSources.clear();
        }
    }

    /**
     * 대본대로 결과나 실패를 돌려준다.
     *
     * <p><b>키는 트랙+청크다</b>({@code file-<recordingFileId>/chunk-<index>.ogg}). 호출 순서로 지정하면 안 된다 — 한 트랙의 청크들은 동시성만큼 병렬로
     * 올라가므로 어느 쪽이 먼저 호출되는지 정해지지 않는다. 파일명만으로도 안 된다: 작업 디렉터리가 트랙마다 다르지만 그 안의 이름은 모두 {@code chunk-0.ogg} 다.
     */
    static final class ScriptedTranscriptionPort implements PostClassTranscriptionPort {
        private static final List<TranscriptSegment> DEFAULT_SEGMENTS =
                List.of(new TranscriptSegment(0, 4_000, "기본 발화", -0.21, 0.02));

        final Map<String, RuntimeException> failures = new HashMap<>();
        final Map<String, List<TranscriptSegment>> segments = new HashMap<>();
        final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable beforeReturn;

        static String key(long recordingFileId, int chunkIndex) {
            return "file-" + recordingFileId + "/chunk-" + chunkIndex + ".ogg";
        }

        @Override
        public TranscriptionResult transcribe(Path audio, String contentType) {
            String key = audio.getParent().getFileName() + "/" + audio.getFileName();
            calls.add(key);
            if (beforeReturn != null) {
                beforeReturn.run();
            }
            RuntimeException failure = failures.get(key);
            if (failure != null) {
                throw failure;
            }
            return new TranscriptionResult(CHUNK_MS, "ko", segments.getOrDefault(key, DEFAULT_SEGMENTS));
        }

        void reset() {
            failures.clear();
            segments.clear();
            calls.clear();
            beforeReturn = null;
        }
    }

    @TestConfiguration
    static class Doubles {

        /**
         * 시계를 앞으로 밀 수 있게 바꾼다.
         *
         * <p>메서드 이름을 {@code clock} 으로 두면 안 된다. {@code SessionApplicationConfig.clock()} 과 빈 이름이 같아지고, 정의 덮어쓰기가 막혀 있어
         * 컨텍스트가 뜨지 않는다. 이름을 달리 두면 {@code Clock} 후보가 둘이 되고 {@code @Primary} 가 이쪽을 고른다.
         */
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        ScriptedAudioChunkPort scriptedAudioChunkPort() {
            return new ScriptedAudioChunkPort();
        }

        @Bean
        @Primary
        ScriptedTranscriptionPort scriptedTranscriptionPort() {
            return new ScriptedTranscriptionPort();
        }

        /** 원본 루트·작업 디렉터리를 임시 경로로 옮긴다. 운영 기본값(/srv, /tmp/zani-postclass)을 테스트가 건드리면 안 된다. */
        @Bean
        @Primary
        PostClassTranscriptionSettings testSettings() {
            try {
                return new PostClassTranscriptionSettings(
                        Files.createTempDirectory("zani-src"),
                        Files.createTempDirectory("zani-work"),
                        Duration.ofMinutes(5),
                        2,
                        "ko",
                        false);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    @Autowired
    private MutableClock clock;

    @Autowired
    private ScriptedAudioChunkPort audioChunkPort;

    @Autowired
    private ScriptedTranscriptionPort transcriptionPort;

    @Autowired
    private PostClassTranscriptionSettings settings;

    @Autowired
    private PipelineJobPort pipelineJobPort;

    @Autowired
    private TranscriptionChunkPort chunkPort;

    @Autowired
    private TryStartTranscriptionUseCase tryStartTranscriptionUseCase;

    @Autowired
    private RecoverStalledTranscriptionsUseCase recoverStalledTranscriptionsUseCase;

    @Autowired
    private TranscribeSessionUseCase transcribeSessionUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * {@code PipelineJobPort} 쓰기를 트랜잭션으로 감싼다.
     *
     * <p>그 어댑터는 자기 트랜잭션을 열지 않는다 — 설계상 메모 확정과 같은 트랜잭션에서 불려야 하기 때문이다(확정만 커밋되고 작업이 남지 않으면 분석이 영원히 시작되지 않는다). 테스트가 직접 부를 때는
     * 그 경계를 여기서 만들어 준다.
     */
    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private final List<Long> createdSessionIds = new ArrayList<>();
    private final List<Long> createdMemberIds = new ArrayList<>();

    private long sessionId;
    private long instructorId;
    private long recordingId;

    @BeforeEach
    void setUp() {
        clock.set(START);
        audioChunkPort.reset();
        transcriptionPort.reset();
        sessionId = createEndedSession();
        instructorId = createParticipant();
        recordingId = insertRecording(RecordingStatus.COMPLETE, TrackSource.MICROPHONE);
    }

    @AfterEach
    void clean() {
        for (Long id : createdSessionIds) {
            jdbcTemplate.update("DELETE FROM postclass_transcription_chunks WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM transcripts WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM pipeline_jobs WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM recording_files WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM recordings WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM recording_outbox WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM session_participants WHERE session_id = ?", id);
            jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", id);
        }
        for (Long id : createdMemberIds) {
            jdbcTemplate.update("DELETE FROM members WHERE id = ?", id);
        }
        createdSessionIds.clear();
        createdMemberIds.clear();
    }

    // ---- 픽스처 ----

    private long createEndedSession() {
        long memberId = TsidGenerator.generate();
        long id = TsidGenerator.generate();
        createdMemberIds.add(memberId);
        createdSessionIds.add(id);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                memberId,
                "pipeline-" + suffix,
                "pipeline-" + suffix + "@example.invalid",
                "pipeline test",
                java.sql.Timestamp.from(START),
                java.sql.Timestamp.from(START));
        jdbcTemplate.update(
                "INSERT INTO sessions (id, host_member_id, title, invite_code, status, analysis_status, started_at,"
                        + " ended_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ENDED', 'NOT_STARTED', ?, ?, ?, ?)",
                id,
                memberId,
                "pipeline",
                suffix,
                java.sql.Timestamp.from(START.minus(Duration.ofHours(1))),
                java.sql.Timestamp.from(START),
                java.sql.Timestamp.from(START),
                java.sql.Timestamp.from(START));
        return id;
    }

    /** 참가자마다 회원을 새로 만든다. {@code session_participants} 는 세션당 회원 하나만 허용한다. */
    private long createParticipant() {
        long memberId = TsidGenerator.generate();
        long id = TsidGenerator.generate();
        createdMemberIds.add(memberId);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO members (id, google_subject, email, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                memberId,
                "participant-" + suffix,
                "participant-" + suffix + "@example.invalid",
                "participant " + suffix,
                java.sql.Timestamp.from(START),
                java.sql.Timestamp.from(START));
        jdbcTemplate.update(
                "INSERT INTO session_participants (id, session_id, member_id, role, first_joined_at,"
                        + " last_accessed_at, created_at, updated_at) VALUES (?, ?, ?, 'STUDENT', ?, ?, ?, ?)",
                id,
                sessionId,
                memberId,
                java.sql.Timestamp.from(START),
                java.sql.Timestamp.from(START),
                java.sql.Timestamp.from(START),
                java.sql.Timestamp.from(START));
        return id;
    }

    private long insertRecording(RecordingStatus status, TrackSource source) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO recordings (id, session_id, livekit_egress_id, session_participant_id, track_source,"
                        + " recording_type, attempt_number, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'TRACK', 1, ?, ?, ?)",
                id,
                sessionId,
                "EG_" + UUID.randomUUID(),
                instructorId,
                source == null ? null : source.name(),
                status.name(),
                java.sql.Timestamp.from(START),
                java.sql.Timestamp.from(START));
        return id;
    }

    /** @return recording_files.id */
    private long insertTrackFile(long participantId, TrackSource source, long startedOffsetMs) {
        long id = TsidGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO recording_files (id, session_id, recording_id, session_participant_id, file_type,"
                        + " track_source, storage_key, livekit_track_sid, started_offset_ms, ended_offset_ms,"
                        + " created_at) VALUES (?, ?, ?, ?, 'TRACK', ?, ?, ?, ?, ?, ?)",
                id,
                sessionId,
                recordingId,
                participantId,
                source.name(),
                "raw/participants/track-" + UUID.randomUUID(),
                "TR_" + UUID.randomUUID(),
                startedOffsetMs,
                startedOffsetMs + CHUNK_MS,
                java.sql.Timestamp.from(START));
        return id;
    }

    private void enqueueJob() {
        inTransaction(() -> pipelineJobPort.enqueue(sessionId, START.minus(Duration.ofMinutes(5))));
    }

    /** 스케줄러를 실제 빈들로 조립해 디스패치까지 함께 돈다. 배경 폴링은 테스트에서 꺼져 있다. */
    private void dispatch() {
        Executor direct = Runnable::run;
        new PostClassTranscriptionScheduler(
                        direct,
                        pipelineJobPort,
                        tryStartTranscriptionUseCase,
                        transcribeSessionUseCase,
                        new com.a105.zani.postclass.infrastructure.config.PostClassTranscriptionProperties(
                                settings.sourceRoot().toString(),
                                settings.workDir().toString(),
                                "ffmpeg",
                                "ffprobe",
                                Duration.ofSeconds(60),
                                Duration.ofSeconds(10),
                                5,
                                true,
                                Duration.ofMinutes(10),
                                settings.leaseDuration(),
                                25_165_824L,
                                2,
                                false,
                                true,
                                0.8,
                                true),
                        clock)
                .dispatchDueTranscriptions();
    }

    // ---- 조회 헬퍼 ----

    private String status() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM pipeline_jobs WHERE session_id = ?", String.class, sessionId);
    }

    private Integer attemptCount() {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM pipeline_jobs WHERE session_id = ?", Integer.class, sessionId);
    }

    private java.sql.Timestamp nextAttemptAt() {
        return jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM pipeline_jobs WHERE session_id = ?", java.sql.Timestamp.class, sessionId);
    }

    private int transcriptRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transcripts WHERE session_id = ?", Integer.class, sessionId);
    }

    private int chunkRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM postclass_transcription_chunks WHERE session_id = ?", Integer.class, sessionId);
    }

    private String document() {
        return jdbcTemplate.queryForObject(
                "SELECT transcript_document FROM transcripts WHERE session_id = ?", String.class, sessionId);
    }

    private Long segmentLong(int index, String field) {
        return jdbcTemplate.queryForObject(
                "SELECT JSON_EXTRACT(transcript_document, ?) FROM transcripts WHERE session_id = ?",
                Long.class,
                "$.segments[" + index + "]." + field,
                sessionId);
    }

    /** 체크포인트 한 행이 들고 있는 GMS 원본 세그먼트. 최종 전사와 다른 것을 담는지 보려면 양쪽을 함께 읽어야 한다. */
    private String checkpointDocument(long recordingFileId, int chunkIndex) {
        return jdbcTemplate.queryForObject(
                "SELECT result_document FROM postclass_transcription_chunks"
                        + " WHERE session_id = ? AND recording_file_id = ? AND chunk_index = ?",
                String.class,
                sessionId,
                recordingFileId,
                chunkIndex);
    }

    private int checkpointSegmentCount(long recordingFileId, int chunkIndex) {
        return jdbcTemplate.queryForObject(
                "SELECT JSON_LENGTH(result_document) FROM postclass_transcription_chunks"
                        + " WHERE session_id = ? AND recording_file_id = ? AND chunk_index = ?",
                Integer.class,
                sessionId,
                recordingFileId,
                chunkIndex);
    }

    private int segmentCount() {
        return jdbcTemplate.queryForObject(
                "SELECT JSON_LENGTH(JSON_EXTRACT(transcript_document, '$.segments')) FROM transcripts"
                        + " WHERE session_id = ?",
                Integer.class,
                sessionId);
    }

    /**
     * 이 세션의 작업 디렉터리에 남은 파일 수.
     *
     * <p>작업 디렉터리 전체가 아니라 세션 몫만 센다. 임시 루트는 컨텍스트당 하나라 테스트끼리 공유되고, 오케스트레이션이 지우는 범위는 {@code session-<id>} 아래뿐이다 — 전체를 세면 다른
     * 테스트가 남긴 파일까지 잡혀 실패 원인이 엉뚱해진다.
     */
    private long workFiles() throws IOException {
        Path sessionWorkDir = settings.workDir().resolve("session-" + sessionId);
        if (!Files.exists(sessionWorkDir)) {
            return 0;
        }
        try (var paths = Files.walk(sessionWorkDir)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    // ---- 1. 정상 완료 ----

    @Test
    void 대기_작업이_전사를_거쳐_ANALYZING_까지_간다() throws IOException {
        insertTrackFile(instructorId, TrackSource.MICROPHONE, 0);
        enqueueJob();
        assertEquals(PipelineStatus.QUEUED.name(), status());

        dispatch();

        assertEquals(PipelineStatus.ANALYZING.name(), status());
        assertEquals(1, transcriptRows());
        assertEquals(1, segmentCount());
        assertEquals(0, workFiles(), "임시 청크 파일을 남기지 않는다");
        assertNull(nextAttemptAt());
    }

    // ---- 2. 절대 시간축 ----

    @Test
    void 파일_청크_세그먼트_세_겹이_그대로_JSON_에_남는다() {
        // 수업 30분 뒤에 시작된 트랙의 1번 청크에서 5초 지점의 발화.
        // 1_800_000 + 600_000 + 5_000 = 2_405_000
        long fileId = insertTrackFile(instructorId, TrackSource.MICROPHONE, 1_800_000);
        audioChunkPort.chunksPerTrack = 2;
        transcriptionPort.segments.put(ScriptedTranscriptionPort.key(fileId, 0), List.of());
        transcriptionPort.segments.put(
                ScriptedTranscriptionPort.key(fileId, 1),
                List.of(new TranscriptSegment(5_000, 9_000, "여기서부터 이해가 안 됐어요", -0.21, 0.02)));
        enqueueJob();

        dispatch();

        assertEquals(1, segmentCount());
        assertEquals(2_405_000L, segmentLong(0, "startOffsetMs"));
        assertEquals(2_409_000L, segmentLong(0, "endOffsetMs"));
        assertEquals(fileId, segmentLong(0, "recordingFileId"));
        assertEquals(1L, segmentLong(0, "chunkIndex"));
        assertEquals(instructorId, segmentLong(0, "sessionParticipantId"));
    }

    // ---- 2-1. 무음 환각 필터(S15P11A105-306) ----

    @Test
    void 환각은_최종_전사에서_빠지고_체크포인트에는_남는다() {
        // 실측 fixture. 2026-08-05 실제 세션에서 환각 "고맙습니다." 는 30초 간격으로 반복됐고 실제 질문은
        // 그 뒤에 나왔다. 운영 기본값은 반복 문구 규칙만 켜 두므로(S15P11A105-316) 여기서도 그 경로를 탄다 —
        // 같은 짧은 문구가 연속 3회 이상이고 과반이 무음이면 반복 전체가 빠진다.
        //
        // 이 테스트의 핵심은 두 저장소가 다른 것을 담는다는 것이다. 최종 전사는 정제된 결과이고
        // 체크포인트는 GMS 원본이다. 원본이 함께 지워지면 규칙을 바꿔도 되돌릴 수 없다.
        long studentId = createParticipant();
        long studentFile = insertTrackFile(studentId, TrackSource.MICROPHONE, 0);
        transcriptionPort.segments.put(
                ScriptedTranscriptionPort.key(studentFile, 0),
                List.of(
                        new TranscriptSegment(15_000, 18_000, "고맙습니다.", -0.21, 0.953),
                        new TranscriptSegment(45_000, 48_000, "고맙습니다.", -0.21, 0.984),
                        new TranscriptSegment(75_000, 78_000, "고맙습니다.", -0.21, 0.976),
                        // 실제 세션의 발화 원문을 쓰지 않는다 — 확률값과 시각이 재현 대상이고 질문 내용은
                        // 판정에 쓰이지 않는다. 이유는 AssembleTranscriptServiceTest 의 같은 상수에 적었다.
                        new TranscriptSegment(
                                165_000, 174_000, "적재율이 높아지면 조회 성능이 어떻게 달라지는지 다시 설명해 주실 수 있나요?", -0.309, 0.176)));
        enqueueJob();

        dispatch();

        // 최종 전사에는 실제 질문만 남는다.
        assertEquals(1, segmentCount());
        assertEquals(165_000L, segmentLong(0, "startOffsetMs"));
        assertEquals(174_000L, segmentLong(0, "endOffsetMs"));
        assertTrue(document().contains("적재율"), "실제 질문은 유지돼야 한다");
        assertTrue(!document().contains("고맙습니다"), "환각은 최종 전사에서 빠져야 한다");
        // 계약은 그대로다. 전량 제거가 아니어도 partial 로 바뀌지 않는다.
        assertEquals("ANALYZING", status());
        assertTrue(document().contains("\"schemaVersion\": 1") || document().contains("\"schemaVersion\":1"));
        assertTrue(document().contains("\"partial\": false") || document().contains("\"partial\":false"));

        // 체크포인트는 GMS 원본 4개를 그대로 들고 있다.
        assertEquals(4, checkpointSegmentCount(studentFile, 0));
        String checkpoint = checkpointDocument(studentFile, 0);
        assertTrue(checkpoint.contains("고맙습니다"), "체크포인트에는 원본이 남아야 한다");
        assertTrue(checkpoint.contains("0.953"), "무음 확률 원값도 남아야 한다");
    }

    @Test
    void 전부_환각이면_빈_전사를_정상_저장하고_ANALYZING_으로_넘긴다() {
        // 필터가 다 걷어 내도 실패가 아니다. 빈 전사를 저장하고 다음 단계로 간다 —
        // 공통 분석이 빈 전사를 "무음 수업" 으로 처리하는 경로가 이미 있다.
        long studentId = createParticipant();
        long studentFile = insertTrackFile(studentId, TrackSource.MICROPHONE, 0);
        transcriptionPort.segments.put(
                ScriptedTranscriptionPort.key(studentFile, 0),
                List.of(
                        new TranscriptSegment(15_000, 18_000, "고맙습니다.", -0.21, 0.953),
                        new TranscriptSegment(45_000, 48_000, "고맙습니다.", -0.21, 0.906),
                        new TranscriptSegment(75_000, 78_000, "고맙습니다.", -0.21, 0.924)));
        enqueueJob();

        dispatch();

        assertEquals(1, transcriptRows());
        assertEquals(0, segmentCount());
        assertEquals("ANALYZING", status());
        assertEquals(3, checkpointSegmentCount(studentFile, 0));
    }

    // ---- 3. 다중 화자 정렬 ----

    @Test
    void 여러_트랙의_세그먼트가_전체_시간순으로_병합된다() {
        long studentId = createParticipant();
        long instructorFile = insertTrackFile(instructorId, TrackSource.MICROPHONE, 0);
        long studentFile = insertTrackFile(studentId, TrackSource.MICROPHONE, 300_000);
        // 강사 트랙: 10s, 400s / 학생 트랙: 300s + 50s = 350s
        transcriptionPort.segments.put(
                ScriptedTranscriptionPort.key(instructorFile, 0),
                List.of(
                        new TranscriptSegment(10_000, 20_000, "첫 발화", -0.21, 0.02),
                        new TranscriptSegment(400_000, 410_000, "세 번째 발화", -0.21, 0.02)));
        transcriptionPort.segments.put(
                ScriptedTranscriptionPort.key(studentFile, 0),
                List.of(new TranscriptSegment(50_000, 55_000, "두 번째 발화", -0.34, 0.05)));
        enqueueJob();

        dispatch();

        assertEquals(3, segmentCount());
        assertEquals(10_000L, segmentLong(0, "startOffsetMs"));
        assertEquals(350_000L, segmentLong(1, "startOffsetMs"), "학생 발화가 시간순 두 번째다");
        assertEquals(400_000L, segmentLong(2, "startOffsetMs"));
        assertEquals(studentId, segmentLong(1, "sessionParticipantId"));
    }

    // ---- 4. 멱등성 ----

    @Test
    void 같은_세션을_다시_실행해도_행이_늘지_않고_문서가_같다() {
        insertTrackFile(instructorId, TrackSource.MICROPHONE, 0);
        audioChunkPort.chunksPerTrack = 3;
        enqueueJob();
        dispatch();
        String first = document();
        int chunksAfterFirst = chunkRows();

        // 다음 단계로 넘어간 작업을 다시 전사 대기로 되돌려 재실행한다(재처리 시나리오).
        inTransaction(() -> {
            pipelineJobPort.updateStatus(sessionId, PipelineStatus.TRANSCRIBING, clock.instant());
            pipelineJobPort.markRetry(sessionId, "재처리", clock.instant().minusSeconds(1), clock.instant());
        });
        transcriptionPort.calls.clear();
        dispatch();

        assertEquals(chunksAfterFirst, chunkRows(), "체크포인트 행이 늘지 않는다");
        assertEquals(1, transcriptRows(), "전사는 세션당 한 행이다");
        assertEquals(first, document(), "같은 체크포인트에서 같은 문서가 나온다");
        assertTrue(transcriptionPort.calls.isEmpty(), "성공한 청크는 다시 호출하지 않는다");
    }

    // ---- 5. 부분 재시도 ----

    @Test
    void 실패한_청크만_다시_호출한다() {
        long fileId = insertTrackFile(instructorId, TrackSource.MICROPHONE, 0);
        audioChunkPort.chunksPerTrack = 3;
        transcriptionPort.failures.put(
                ScriptedTranscriptionPort.key(fileId, 1),
                new com.a105.zani.postclass.application.exception.PostClassTranscriptionFailedException(true));
        enqueueJob();

        dispatch();

        // 1번은 재시도 대기로 남고, 조립은 아직 못 한다.
        assertEquals(0, transcriptRows());
        assertEquals(PipelineStatus.TRANSCRIBING.name(), status());
        assertNotNull(nextAttemptAt(), "재시도 가능 실패이므로 대기 시각이 남는다");
        assertEquals(1, attemptCount());

        // 재시도 기한을 넘기고 실패를 걷어 낸 뒤 다시 실행한다.
        transcriptionPort.failures.clear();
        transcriptionPort.calls.clear();
        clock.advance(Duration.ofMinutes(10));

        dispatch();

        assertEquals(
                List.of(ScriptedTranscriptionPort.key(fileId, 1)), transcriptionPort.calls, "성공했던 두 청크는 재호출하지 않는다");
        assertEquals(PipelineStatus.ANALYZING.name(), status());
        assertEquals(3, segmentCount());
    }

    // ---- 6. 영구 실패 ----

    @Test
    void 청크_하나가_영구_실패하면_전사를_저장하지_않고_파이프라인을_끊는다() {
        long fileId = insertTrackFile(instructorId, TrackSource.MICROPHONE, 0);
        audioChunkPort.chunksPerTrack = 2;
        transcriptionPort.failures.put(
                ScriptedTranscriptionPort.key(fileId, 1),
                new com.a105.zani.postclass.application.exception.PostClassTranscriptionFailedException(false));
        enqueueJob();

        dispatch();

        assertEquals(0, transcriptRows(), "성공한 청크만 담은 문서를 남겨서도 안 된다");
        assertEquals(PipelineStatus.FAILED.name(), status());
        assertNull(nextAttemptAt(), "재시도 불가이므로 대기 시각을 남기지 않는다");
        List<TranscriptionChunk> chunks = chunkPort.findAllBySessionId(sessionId);
        assertEquals(TranscriptionChunkStatus.SUCCEEDED, chunks.get(0).status());
        assertEquals(TranscriptionChunkStatus.FAILED, chunks.get(1).status());
    }

    // ---- 7. 재기동 복구와 fencing ----

    @Test
    void lease_가_만료된_청크를_회수하고_이전_실행의_늦은_결과를_거절한다() throws IOException {
        insertTrackFile(instructorId, TrackSource.MICROPHONE, 0);
        enqueueJob();
        // 죽은 실행이 선점만 하고 끝난 상태를 만든다.
        assertTrue(tryStartTranscriptionUseCase.tryStart(sessionId));
        // 청크 목록만 필요하다. 오케스트레이션이 지우는 경로 밖에 만들어 다른 테스트에 남기지 않는다.
        Path scratch = Files.createTempDirectory("zani-dead-run");
        List<AudioChunk> chunks = audioChunkPort.split(settings.sourceRoot().resolve("dummy.ogg"), scratch);
        long fileId = jdbcTemplate.queryForObject(
                "SELECT id FROM recording_files WHERE session_id = ?", Long.class, sessionId);
        chunkPort.registerAll(sessionId, fileId, chunks, clock.instant());
        TranscriptionChunk claimable =
                chunkPort.findClaimable(fileId, clock.instant(), 10).get(0);
        OptionalInt deadRunToken =
                chunkPort.tryClaim(claimable.id(), clock.instant().plus(settings.leaseDuration()), clock.instant());
        assertTrue(deadRunToken.isPresent());

        // lease 를 넘긴 뒤 새 실행이 회수해 끝낸다.
        clock.advance(settings.leaseDuration().plusMinutes(1));
        inTransaction(() ->
                pipelineJobPort.markRetry(sessionId, "재기동", clock.instant().minusSeconds(1), clock.instant()));
        dispatch();

        assertEquals(PipelineStatus.ANALYZING.name(), status());
        assertEquals(1, segmentCount());
        List<TranscriptionChunk> after = chunkPort.findAllBySessionId(sessionId);
        assertEquals(TranscriptionChunkStatus.SUCCEEDED, after.get(0).status());
        assertEquals(2, after.get(0).attemptCount(), "회수로 시도 횟수가 올라간다");

        // 늦게 끝난 이전 실행의 쓰기는 거절돼야 한다.
        boolean lateWrite = chunkPort.markSucceeded(
                claimable.id(),
                deadRunToken.getAsInt(),
                List.of(new TranscriptSegment(0, 1_000, "늦은 결과", -0.5, 0.1)),
                clock.instant());

        assertFalse(lateWrite, "실행권을 잃은 쪽의 결과는 버려진다");
        assertEquals(
                "기본 발화",
                chunkPort.findAllBySessionId(sessionId).get(0).segments().get(0).text());
    }

    // ---- 재기동 복구 (프로세스가 죽어 남은 작업) ----

    @Test
    void 워커_없이_남은_TRANSCRIBING_작업이_복구_없이는_영구_정지한다() throws IOException {
        // 이것이 복구가 필요한 이유다. 후보 조회는 next_attempt_at 이 없는 TRANSCRIBING 을 "실행 중" 으로
        // 보고 제외하므로, 워커가 사라지면 아무도 그 세션을 다시 보지 않는다.
        insertTrackFile(instructorId, TrackSource.MICROPHONE, 0);
        enqueueJob();
        assertTrue(tryStartTranscriptionUseCase.tryStart(sessionId), "최초 시작이 TRANSCRIBING 으로 옮긴다");

        assertEquals(PipelineStatus.TRANSCRIBING.name(), status());
        assertNull(nextAttemptAt());
        assertFalse(
                pipelineJobPort
                        .findDueTranscriptionSessionIds(clock.instant(), 10)
                        .contains(sessionId),
                "복구 전에는 후보에 담기지 않는다");
    }

    @Test
    void 기동_복구가_수동_개입_없이_전사를_이어간다() throws IOException {
        // 기존 재기동 테스트는 markRetry 를 직접 불러 복구를 흉내 냈다. 여기서는 복구 경로만 쓴다.
        insertTrackFile(instructorId, TrackSource.MICROPHONE, 0);
        enqueueJob();
        tryStartTranscriptionUseCase.tryStart(sessionId);
        int attemptsBefore = attemptCount();

        int recovered = recoverStalledTranscriptionsUseCase.recover();

        assertTrue(recovered >= 1);
        assertEquals(attemptsBefore, attemptCount(), "크래시는 단계 실패가 아니므로 시도 횟수를 올리지 않는다");
        assertTrue(pipelineJobPort
                .findDueTranscriptionSessionIds(clock.instant(), 10)
                .contains(sessionId));

        dispatch();

        assertEquals(PipelineStatus.ANALYZING.name(), status());
        assertEquals(1, transcriptRows());
    }

    // ---- 8. 녹화 준비 경쟁 ----

    @Test
    void 녹화가_진행_중이면_빈_전사를_확정하지_않는다() {
        // recording_files 는 Egress 종료 webhook 이 만든다. 그 전에 빈 목록을 정상으로 확정하면
        // ANALYZING 으로 넘어간 뒤 실제 OGG 가 도착해 영원히 전사되지 않는다.
        insertRecording(RecordingStatus.RECORDING, TrackSource.MICROPHONE);
        enqueueJob();

        dispatch();

        assertEquals(0, transcriptRows());
        assertEquals(PipelineStatus.TRANSCRIBING.name(), status());
        assertNotNull(nextAttemptAt(), "기다리면 되므로 재시도 가능이다");
    }

    @Test
    void 녹화가_모두_종결된_빈_세션은_빈_세그먼트로_저장한다() {
        // 아무도 마이크를 열지 않은 수업. 이때의 빈 목록만 정상적인 빈 전사다.
        enqueueJob();

        dispatch();

        assertEquals(1, transcriptRows());
        assertEquals(0, segmentCount());
        assertTrue(document().contains("\"partial\": false") || document().contains("\"partial\":false"));
        assertEquals(PipelineStatus.ANALYZING.name(), status());
    }

    // ---- 9. 트랜잭션 경계 ----

    @Test
    void GMS_를_기다리는_동안_pipeline_jobs_잠금을_붙잡지_않는다() throws Exception {
        insertTrackFile(instructorId, TrackSource.MICROPHONE, 0);
        enqueueJob();
        ExecutorService probe = Executors.newSingleThreadExecutor();
        List<String> probeResult = new ArrayList<>();
        try {
            // 전사 호출 도중에 다른 커넥션이 같은 행을 잠금 읽기 한다. 오케스트레이션이 잠금을 쥐고 있으면
            // 이 조회가 호출이 끝날 때까지 막히고, 아래 get(...) 이 시간 초과로 실패한다.
            transcriptionPort.beforeReturn = () -> {
                Future<String> locked = probe.submit(() -> {
                    Long id = jdbcTemplate.queryForObject(
                            "SELECT id FROM pipeline_jobs WHERE session_id = ? FOR UPDATE", Long.class, sessionId);
                    return String.valueOf(id);
                });
                try {
                    probeResult.add(locked.get(5, TimeUnit.SECONDS));
                } catch (Exception exception) {
                    throw new IllegalStateException("pipeline_jobs 행이 잠겨 있다", exception);
                }
            };

            dispatch();
        } finally {
            probe.shutdownNow();
        }

        assertEquals(1, probeResult.size(), "전사 도중에 잠금 읽기가 통과해야 한다");
        assertEquals(PipelineStatus.ANALYZING.name(), status());
    }
}
