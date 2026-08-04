package com.a105.zani.postclass;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptCommand;
import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptResult;
import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptService;
import com.a105.zani.postclass.application.exception.TranscriptAssemblyInvalidException;
import com.a105.zani.postclass.application.exception.TranscriptIncompleteException;
import com.a105.zani.postclass.application.exception.TranscriptNotReadyException;
import com.a105.zani.postclass.application.port.TranscriptPort;
import com.a105.zani.postclass.application.port.TranscriptSegment;
import com.a105.zani.postclass.application.port.TranscriptionChunk;
import com.a105.zani.postclass.application.port.TranscriptionTrack;
import com.a105.zani.postclass.domain.model.TranscriptDocument;
import com.a105.zani.postclass.domain.model.TranscriptDocumentSegment;
import com.a105.zani.postclass.domain.model.TranscriptionChunkStatus;
import com.a105.zani.recording.domain.model.TrackSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시간축 합성과 거절 규칙을 검증한다.
 *
 * <p>여기서 확인하는 것은 <b>세 겹의 합</b>이다. 파일 시작 + 청크 시작 + 세그먼트 상대 시각이 맞게 더해지는지, 그리고 그중 하나가 없거나 어긋날 때 그럴듯한 숫자를 만들어 내지 않고 멈추는지.
 * 저장소는 가짜로 두고 계산만 본다.
 */
class AssembleTranscriptServiceTest {

    private static final Long SESSION_ID = 9_200_001L;
    private static final Long INSTRUCTOR = 9_200_101L;
    private static final Long STUDENT = 9_200_102L;
    private static final Long INSTRUCTOR_FILE = 9_200_201L;
    private static final Long STUDENT_FILE = 9_200_202L;
    private static final long CHUNK_MS = 600_000L;
    private static final Instant NOW = Instant.parse("2026-08-04T02:00:00Z");

    private final RecordingTranscriptPort transcriptPort = new RecordingTranscriptPort();
    private final AssembleTranscriptService service =
            new AssembleTranscriptService(transcriptPort, Clock.fixed(NOW, ZoneOffset.UTC));

    /** 저장된 문서를 들여다보기 위한 가짜 포트. 조립 결과가 정본과 같은 형태인지 보려면 실제로 저장되는 값이 필요하다. */
    private static final class RecordingTranscriptPort implements TranscriptPort {
        private final List<TranscriptDocument> saved = new ArrayList<>();

        @Override
        public void save(Long sessionId, TranscriptDocument document, Instant now) {
            saved.add(document);
        }

        @Override
        public java.util.Optional<TranscriptDocument> findBySessionId(Long sessionId) {
            return saved.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(saved.get(saved.size() - 1));
        }

        TranscriptDocument only() {
            assertEquals(1, saved.size(), "정확히 한 번 저장돼야 한다");
            return saved.get(0);
        }
    }

    private static TranscriptionTrack track(Long fileId, Long participantId, Long startedOffsetMs) {
        return new TranscriptionTrack(fileId, participantId, TrackSource.MICROPHONE, startedOffsetMs);
    }

    private static TranscriptionChunk chunk(
            Long fileId, int index, TranscriptionChunkStatus status, List<TranscriptSegment> segments) {
        long start = index * CHUNK_MS;
        return new TranscriptionChunk(
                fileId * 1_000 + index,
                SESSION_ID,
                fileId,
                index,
                start,
                start + CHUNK_MS,
                status,
                1,
                null,
                null,
                segments);
    }

    private static TranscriptSegment segment(long startMs, long endMs, String text) {
        return new TranscriptSegment(startMs, endMs, text, -0.21, 0.02);
    }

    private AssembleTranscriptResult assemble(List<TranscriptionTrack> tracks, List<TranscriptionChunk> chunks) {
        return service.assemble(new AssembleTranscriptCommand(SESSION_ID, "ko", tracks, chunks));
    }

    @Test
    void 파일_청크_세그먼트_세_겹을_더해_수업_기준_절대_시각을_만든다() {
        // 수업 시작 30분 뒤에 발행된 트랙(재접속)의 2번 청크에서 5초 지점의 발화.
        // 1_800_000 + 1_200_000 + 5_000 = 3_005_000 이어야 한다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                STUDENT_FILE,
                2,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(segment(5_000, 9_000, "여기서부터 이해가 안 됐어요"))));

        assemble(List.of(track(STUDENT_FILE, STUDENT, 1_800_000L)), chunks);

        TranscriptDocumentSegment stored = transcriptPort.only().segments().get(0);
        assertEquals(3_005_000, stored.startOffsetMs());
        assertEquals(3_009_000, stored.endOffsetMs());
        assertEquals(STUDENT, stored.sessionParticipantId());
        assertEquals(TrackSource.MICROPHONE, stored.source());
        // 추적용 좌표. 이 문장이 이상하면 이 두 값으로 체크포인트 행을 바로 찾는다.
        assertEquals(STUDENT_FILE, stored.recordingFileId());
        assertEquals(2, stored.chunkIndex());
    }

    @Test
    void 파일_시작_오프셋을_0_으로_가정하지_않는다() {
        // 이것을 빠뜨리면 늦게 접속한 학생의 발화 전체가 수업 시작 지점으로 밀리고,
        // 리포트는 그 학생이 도입부에만 말했다고 집계한다.
        List<TranscriptionChunk> chunks = List.of(
                chunk(STUDENT_FILE, 0, TranscriptionChunkStatus.SUCCEEDED, List.of(segment(0, 3_000, "늦게 들어왔어요"))));

        assemble(List.of(track(STUDENT_FILE, STUDENT, 2_400_000L)), chunks);

        assertEquals(2_400_000, transcriptPort.only().segments().get(0).startOffsetMs());
    }

    @Test
    void 여러_화자의_트랙을_하나의_시간순_타임라인으로_병합한다() {
        List<TranscriptionChunk> chunks = List.of(
                chunk(
                        INSTRUCTOR_FILE,
                        0,
                        TranscriptionChunkStatus.SUCCEEDED,
                        List.of(
                                segment(2_000, 32_000, "오늘은 상태 관리를 다룹니다"),
                                segment(400_000, 431_000, "props drilling"))),
                chunk(
                        STUDENT_FILE,
                        0,
                        TranscriptionChunkStatus.SUCCEEDED,
                        List.of(segment(130_000, 145_000, "어떤 기준으로 골라야 하나요?"))));

        assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L), track(STUDENT_FILE, STUDENT, 600_000L)), chunks);

        List<TranscriptDocumentSegment> segments = transcriptPort.only().segments();
        assertEquals(3, segments.size());
        // 학생 발화는 600_000 + 130_000 = 730_000 이라 강사의 431_000 뒤, 두 번째 강사 발화보다 뒤다.
        assertEquals(
                List.of(2_000L, 400_000L, 730_000L),
                segments.stream().map(TranscriptDocumentSegment::startOffsetMs).toList());
        assertEquals(STUDENT, segments.get(2).sessionParticipantId());
    }

    @Test
    void 같은_시각의_세그먼트도_순서가_결정적이다() {
        // 두 화자가 동시에 말한 구간. 순서가 실행마다 달라지면 같은 체크포인트에서 다른 JSON 이 나오고,
        // "재조립이 내용을 바꾸지 않는다" 를 확인할 수 없다.
        List<TranscriptionChunk> chunks = List.of(
                chunk(STUDENT_FILE, 0, TranscriptionChunkStatus.SUCCEEDED, List.of(segment(1_000, 2_000, "학생"))),
                chunk(INSTRUCTOR_FILE, 0, TranscriptionChunkStatus.SUCCEEDED, List.of(segment(1_000, 2_000, "강사"))));
        List<TranscriptionTrack> tracks =
                List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L), track(STUDENT_FILE, STUDENT, 0L));

        assemble(tracks, chunks);
        List<TranscriptDocumentSegment> first = transcriptPort.only().segments();

        // 입력 순서를 뒤집어도 같은 결과여야 한다.
        RecordingTranscriptPort second = new RecordingTranscriptPort();
        new AssembleTranscriptService(second, Clock.fixed(NOW, ZoneOffset.UTC))
                .assemble(new AssembleTranscriptCommand(SESSION_ID, "ko", tracks.reversed(), chunks.reversed()));

        assertEquals(
                first.stream().map(TranscriptDocumentSegment::text).toList(),
                second.only().segments().stream()
                        .map(TranscriptDocumentSegment::text)
                        .toList());
        // 참여자 id 오름차순이 동점 규칙이다.
        assertEquals(INSTRUCTOR, first.get(0).sessionParticipantId());
    }

    @Test
    void 무음_스킵_청크는_세그먼트_없이_완전한_전사에_포함된다() {
        List<TranscriptionChunk> chunks = List.of(
                chunk(INSTRUCTOR_FILE, 0, TranscriptionChunkStatus.SUCCEEDED, List.of(segment(0, 5_000, "시작합니다"))),
                chunk(INSTRUCTOR_FILE, 1, TranscriptionChunkStatus.SKIPPED_SILENT, List.of()));

        AssembleTranscriptResult result = assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), chunks);

        assertEquals(1, result.segmentCount());
        assertTrue(!transcriptPort.only().partial(), "스킵은 누락이 아니므로 partial 이 아니다");
    }

    @Test
    void 영구_실패_청크가_하나라도_있으면_저장하지_않는다() {
        // MVP 정책. 부분 전사를 소비하는 계약이 하류에 없어 불완전한 전사를 넘기지 않는다.
        List<TranscriptionChunk> chunks = List.of(
                chunk(INSTRUCTOR_FILE, 0, TranscriptionChunkStatus.SUCCEEDED, List.of(segment(0, 5_000, "시작합니다"))),
                chunk(INSTRUCTOR_FILE, 1, TranscriptionChunkStatus.FAILED, List.of()));

        assertThrows(
                TranscriptIncompleteException.class,
                () -> assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), chunks));

        assertTrue(transcriptPort.saved.isEmpty(), "성공한 청크만 담은 문서를 남겨서도 안 된다");
    }

    @Test
    void 아직_끝나지_않은_청크는_영구_실패와_다른_예외로_구분한다() {
        // 둘 다 지금 ANALYZING 으로 넘기지 않지만 이후가 다르다. 이것은 기다리면 끝나므로 재시도 가능이고,
        // 합쳐 두면 오케스트레이션이 처리 중인 세션을 너무 일찍 최종 실패로 굳힌다.
        List<TranscriptionChunk> processing = List.of(
                chunk(INSTRUCTOR_FILE, 0, TranscriptionChunkStatus.SUCCEEDED, List.of(segment(0, 5_000, "시작합니다"))),
                chunk(INSTRUCTOR_FILE, 1, TranscriptionChunkStatus.PROCESSING, List.of()));

        assertThrows(
                TranscriptNotReadyException.class,
                () -> assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), processing));
    }

    @Test
    void 앞_청크가_대기_중이어도_뒤_청크의_영구_실패를_먼저_판정한다() {
        // 회귀 테스트. 청크를 하나씩 보며 그 자리에서 던지면 0번의 대기 상태가 먼저 걸려 NotReady(재시도
        // 가능)가 나가고, 1번의 영구 실패는 보이지 않는다. 그러면 "하나라도 영구 실패면 전체 비재시도" 가
        // 청크 순서에 따라 깨진다.
        List<TranscriptionChunk> chunks = List.of(
                chunk(INSTRUCTOR_FILE, 0, TranscriptionChunkStatus.PENDING, List.of()),
                chunk(INSTRUCTOR_FILE, 1, TranscriptionChunkStatus.FAILED, List.of()));

        assertThrows(
                TranscriptIncompleteException.class,
                () -> assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), chunks));
    }

    @Test
    void 데이터가_틀린_것은_대기보다_먼저_판정한다() {
        // 잘못된 데이터도 재시도 불가다. 대기 상태에 가려지면 재시도 예산을 다 쓴 뒤에야 드러난다.
        TranscriptionChunk foreign = new TranscriptionChunk(
                1L,
                SESSION_ID + 1,
                INSTRUCTOR_FILE,
                1,
                CHUNK_MS,
                CHUNK_MS * 2,
                TranscriptionChunkStatus.SUCCEEDED,
                1,
                null,
                null,
                List.of());
        List<TranscriptionChunk> chunks =
                List.of(chunk(INSTRUCTOR_FILE, 0, TranscriptionChunkStatus.PROCESSING, List.of()), foreign);

        assertThrows(
                TranscriptAssemblyInvalidException.class,
                () -> assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), chunks));
    }

    @Test
    void 선점되지_않은_청크도_대기로_본다() {
        List<TranscriptionChunk> pending = List.of(
                chunk(INSTRUCTOR_FILE, 0, TranscriptionChunkStatus.SUCCEEDED, List.of(segment(0, 5_000, "시작합니다"))),
                chunk(INSTRUCTOR_FILE, 1, TranscriptionChunkStatus.PENDING, List.of()));

        assertThrows(
                TranscriptNotReadyException.class,
                () -> assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), pending));
    }

    @Test
    void 화자를_모르는_트랙은_거절한다() {
        // V12 이전 legacy 파일 행. 화자 없이 저장하면 그 발화가 누구 것인지 영원히 알 수 없다.
        List<TranscriptionChunk> chunks =
                List.of(chunk(STUDENT_FILE, 0, TranscriptionChunkStatus.SUCCEEDED, List.of(segment(0, 3_000, "발화"))));

        assertThrows(
                TranscriptAssemblyInvalidException.class,
                () -> assemble(List.of(track(STUDENT_FILE, null, 0L)), chunks));
    }

    @Test
    void 시각이_없는_트랙은_거절한다() {
        List<TranscriptionChunk> chunks =
                List.of(chunk(STUDENT_FILE, 0, TranscriptionChunkStatus.SUCCEEDED, List.of(segment(0, 3_000, "발화"))));

        assertThrows(
                TranscriptAssemblyInvalidException.class,
                () -> assemble(List.of(track(STUDENT_FILE, STUDENT, null)), chunks));
    }

    @Test
    void 트랙_목록에_없는_파일의_청크는_거절한다() {
        List<TranscriptionChunk> chunks =
                List.of(chunk(STUDENT_FILE, 0, TranscriptionChunkStatus.SUCCEEDED, List.of(segment(0, 3_000, "발화"))));

        assertThrows(
                TranscriptAssemblyInvalidException.class,
                () -> assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), chunks));
    }

    @Test
    void 다른_세션의_청크가_섞이면_거절한다() {
        TranscriptionChunk foreign = new TranscriptionChunk(
                1L,
                SESSION_ID + 1,
                INSTRUCTOR_FILE,
                0,
                0,
                CHUNK_MS,
                TranscriptionChunkStatus.SUCCEEDED,
                1,
                null,
                null,
                List.of(segment(0, 3_000, "남의 수업 발화")));

        assertThrows(
                TranscriptAssemblyInvalidException.class,
                () -> assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), List.of(foreign)));
    }

    @Test
    void 같은_청크가_두_번_오면_거절한다() {
        // 그대로 두면 그 구간의 발화가 문서에 두 번 실린다.
        TranscriptionChunk duplicated =
                chunk(INSTRUCTOR_FILE, 0, TranscriptionChunkStatus.SUCCEEDED, List.of(segment(0, 3_000, "발화")));

        assertThrows(
                TranscriptAssemblyInvalidException.class,
                () -> assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), List.of(duplicated, duplicated)));
    }

    @Test
    void 청크_길이를_크게_넘는_세그먼트는_분할_오류로_거절한다() {
        // 분할이 잘못되면 청크 하나 단위로 어긋나므로 분 단위로 벗어난다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                INSTRUCTOR_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(segment(0, CHUNK_MS + 120_000, "청크를 2분 넘긴 문장"))));

        assertThrows(
                TranscriptAssemblyInvalidException.class,
                () -> assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), chunks));
    }

    @Test
    void 청크_밖에서_시작하는_세그먼트는_거절한다() {
        // 종료만 줄이면 start=600_500, end=600_000 처럼 시각이 뒤집힌 구간이 만들어진다.
        // 줄여서 살릴 수 있는 것은 청크 안에서 시작해 경계를 살짝 넘긴 문장뿐이다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                INSTRUCTOR_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(segment(CHUNK_MS + 500, CHUNK_MS + 800, "청크 밖에서 시작한 문장"))));

        assertThrows(
                TranscriptAssemblyInvalidException.class,
                () -> assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), chunks));
    }

    @Test
    void 청크_끝과_같은_시각에_시작하는_세그먼트도_거절한다() {
        // 길이가 0 인 구간은 발화가 아니고, 경계에 정확히 걸친 값은 다음 청크가 담당한다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                INSTRUCTOR_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(segment(CHUNK_MS, CHUNK_MS + 200, "경계에 걸친 문장"))));

        assertThrows(
                TranscriptAssemblyInvalidException.class,
                () -> assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), chunks));
    }

    @Test
    void 반올림_수준의_초과는_청크_끝으로_맞춘다() {
        // GMS 가 마지막 문장의 끝을 몇 ms 넘겨 주는 것은 정상 범위다. 이것까지 실패로 보면
        // 멀쩡한 수업 전체가 저장되지 않는다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                INSTRUCTOR_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(segment(CHUNK_MS - 5_000, CHUNK_MS + 40, "청크 끝에 걸친 문장"))));

        assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), chunks);

        assertEquals(CHUNK_MS, transcriptPort.only().segments().get(0).endOffsetMs());
    }

    @Test
    void 발화가_없는_세션도_완전한_빈_전사로_저장한다() {
        // 아무도 마이크를 열지 않은 수업이면 빈 전사가 사실이다. 실패가 아니다.
        AssembleTranscriptResult result = assemble(
                List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)),
                List.of(chunk(INSTRUCTOR_FILE, 0, TranscriptionChunkStatus.SKIPPED_SILENT, List.of())));

        assertEquals(0, result.segmentCount());
        assertEquals(0, result.speakerCount());
        assertEquals(0, result.lastEndOffsetMs());
        assertTrue(transcriptPort.only().segments().isEmpty());
    }

    @Test
    void 문서에_판과_언어와_신뢰도를_남긴다() {
        List<TranscriptionChunk> chunks = List.of(
                chunk(INSTRUCTOR_FILE, 0, TranscriptionChunkStatus.SUCCEEDED, List.of(segment(0, 5_000, "시작합니다"))));

        AssembleTranscriptResult result = assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), chunks);

        TranscriptDocument document = transcriptPort.only();
        assertEquals(TranscriptDocument.SCHEMA_VERSION, document.schemaVersion());
        assertEquals("ko", document.language());
        TranscriptDocumentSegment stored = document.segments().get(0);
        // 원값과 파생값을 함께 남긴다. confidence 만 두면 식을 바꿀 때 옛 값과 구분할 수 없다.
        assertEquals(-0.21, stored.avgLogprob(), 1e-9);
        assertEquals(Math.exp(-0.21), stored.confidence(), 1e-9);
        assertEquals(0.02, stored.noSpeechProb(), 1e-9);
        assertEquals(1, result.speakerCount());
        assertEquals(5_000, result.lastEndOffsetMs());
    }
}
