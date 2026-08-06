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
import com.a105.zani.postclass.application.port.TranscriptFilterSettings;
import com.a105.zani.postclass.application.port.TranscriptPort;
import com.a105.zani.postclass.application.port.TranscriptSegment;
import com.a105.zani.postclass.application.port.TranscriptionChunk;
import com.a105.zani.postclass.application.port.TranscriptionTrack;
import com.a105.zani.postclass.domain.model.ConfidenceMethod;
import com.a105.zani.postclass.domain.model.TranscriptDocument;
import com.a105.zani.postclass.domain.model.TranscriptDocumentSegment;
import com.a105.zani.postclass.domain.model.TranscriptionChunkStatus;
import com.a105.zani.recording.domain.model.TrackSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** 운영 기본값. 근거는 application.yaml 주석과 {@link TranscriptFilterSettings} 에 있다. */
    private static final double DEFAULT_THRESHOLD = 0.8;

    private final RecordingTranscriptPort transcriptPort = new RecordingTranscriptPort();
    private final AssembleTranscriptService service = service(transcriptPort, DEFAULT_THRESHOLD);

    private static AssembleTranscriptService service(TranscriptPort port, double threshold) {
        return new AssembleTranscriptService(
                port, Clock.fixed(NOW, ZoneOffset.UTC), new TranscriptFilterSettings(true, threshold));
    }

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
        return new TranscriptionTrack(fileId, participantId, TrackSource.MICROPHONE, "TR_" + fileId, startedOffsetMs);
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

    /** 무음 확률을 지정한 세그먼트. 실측값을 그대로 넣기 위한 것이다. */
    private static TranscriptSegment segment(long startMs, long endMs, String text, double noSpeechProb) {
        return new TranscriptSegment(startMs, endMs, text, -0.21, noSpeechProb);
    }

    private static List<String> textsOf(TranscriptDocument document) {
        return document.segments().stream().map(TranscriptDocumentSegment::text).toList();
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
        // 추적용 좌표 셋. recordingFileId·chunkIndex 는 체크포인트 행으로, trackSid 는 LiveKit 발행 구간으로 간다.
        assertEquals(STUDENT_FILE, stored.recordingFileId());
        assertEquals(2, stored.chunkIndex());
        assertEquals("TR_" + STUDENT_FILE, stored.trackSid());
        assertEquals(ConfidenceMethod.EXP_AVG_LOGPROB, stored.confidenceMethod());
    }

    @Test
    void trackSid_가_없는_트랙도_조립한다() {
        // 한 Egress 가 파일을 여러 개 남기면 첫 행만 Track SID 를 갖는다. 그것으로 조립을 막으면
        // 정상적으로 저장된 트랙이 전사되지 않는다.
        List<TranscriptionChunk> chunks =
                List.of(chunk(STUDENT_FILE, 0, TranscriptionChunkStatus.SUCCEEDED, List.of(segment(0, 3_000, "발화"))));
        TranscriptionTrack noSid = new TranscriptionTrack(STUDENT_FILE, STUDENT, TrackSource.MICROPHONE, null, 0L);

        assemble(List.of(noSid), chunks);

        assertNull(transcriptPort.only().segments().get(0).trackSid());
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
        service(second, DEFAULT_THRESHOLD)
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

    // ---------------------------------------------------------------------
    // 무음 환각 필터(S15P11A105-306)
    //
    // 실측 확률값과 시각을 fixture 로 고정한다. 2026-08-05 실제 세션의 학생 마이크 전사에서 환각
    // "고맙습니다." 는 no_speech_prob 0.906~0.985 로 나왔고 실제 질문 세그먼트는 0.176 이었다.
    // 관측값이 0.18~0.90 사이에 하나도 없다는 것이 기본 임곗값 0.8 의 근거이므로 두 극단을 그대로 넣는다.
    //
    // 학생 발화 원문은 넣지 않는다. 지워지면 안 되는 쪽의 텍스트는 형태만 같은 문장으로 대체했다 —
    // 상수 주석에 근거를 적었다.
    // ---------------------------------------------------------------------

    /**
     * 지워지면 안 되는 학생 질문 자리.
     *
     * <p><b>실제 세션의 발화 원문을 쓰지 않는다.</b> 실제 학생이 말한 문장을 저장소에 박으면 그 발화가 코드로 남는다. 이 fixture 의 검증력은 확률값·시각·순서와 <b>문장 끝의
     * 물음표</b>에서 나오고, 질문의 내용은 판정에 쓰이지 않는다. 그래서 형태만 같은 문장으로 대체했다 — 긴 문장이고 종결 부호가 물음표다.
     *
     * <p>실측 확률값({@code 0.176})과 시각은 응답 그대로다. 그쪽이 이 테스트가 재현하는 대상이다.
     */
    private static final String STUDENT_QUESTION = "적재율이 높아지면 조회 성능이 어떻게 달라지는지 다시 설명해 주실 수 있나요?";

    /**
     * 환각 문구.
     *
     * <p>이쪽은 사람의 발화가 아니라 <b>모델이 무음 구간에서 만들어 낸 출력</b>이라 실측 문구를 그대로 둔다. 이 티켓이 존재하는 이유이고, 이 문구가 30초 간격으로 반복됐다는 사실 자체가 재현
     * 조건이다.
     */
    private static final String HALLUCINATION = "고맙습니다.";

    @Test
    void 무음_확률이_임곗값보다_높은_세그먼트를_뺀다() {
        List<TranscriptionChunk> chunks = List.of(chunk(
                STUDENT_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(
                        segment(15_000, 18_000, HALLUCINATION, 0.953),
                        segment(45_000, 48_000, HALLUCINATION, 0.984),
                        segment(165_000, 174_000, STUDENT_QUESTION, 0.176))));

        AssembleTranscriptResult result = assemble(List.of(track(STUDENT_FILE, STUDENT, 0L)), chunks);

        assertEquals(List.of(STUDENT_QUESTION), textsOf(transcriptPort.only()));
        assertEquals(1, result.segmentCount());
        assertEquals(2, result.filteredSegmentCount());
    }

    @Test
    void 임곗값과_같은_값도_뺀다() {
        // 경계는 포함이다. 0.8 로 두었는데 0.8 이 남으면 설정의 뜻이 흐려진다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                STUDENT_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(segment(1_000, 3_000, HALLUCINATION, DEFAULT_THRESHOLD))));

        AssembleTranscriptResult result = assemble(List.of(track(STUDENT_FILE, STUDENT, 0L)), chunks);

        assertTrue(transcriptPort.only().segments().isEmpty());
        assertEquals(1, result.filteredSegmentCount());
    }

    @Test
    void 임곗값보다_낮으면_남긴다() {
        // 실측 최악의 경계 근처. 0.79 는 남고 0.80 은 빠진다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                STUDENT_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(segment(1_000, 3_000, "조금 애매한 발화", 0.79))));

        AssembleTranscriptResult result = assemble(List.of(track(STUDENT_FILE, STUDENT, 0L)), chunks);

        assertEquals(List.of("조금 애매한 발화"), textsOf(transcriptPort.only()));
        assertEquals(0, result.filteredSegmentCount());
    }

    @Test
    void 신뢰도가_낮아도_무음_확률이_낮으면_빼지_않는다() {
        // avgLogprob 이 낮다는 것은 모델이 그 문장에 확신이 없었다는 뜻일 뿐 무음이라는 뜻이 아니다.
        // 두 값을 섞으면 낮은 신뢰도의 정상 발화를 잃는다 — 이번 규칙은 noSpeechProb 하나만 본다.
        TranscriptSegment lowConfidence = new TranscriptSegment(1_000, 4_000, "웅얼거린 발화", -3.5, 0.12);
        List<TranscriptionChunk> chunks =
                List.of(chunk(STUDENT_FILE, 0, TranscriptionChunkStatus.SUCCEEDED, List.of(lowConfidence)));

        AssembleTranscriptResult result = assemble(List.of(track(STUDENT_FILE, STUDENT, 0L)), chunks);

        assertEquals(List.of("웅얼거린 발화"), textsOf(transcriptPort.only()));
        assertEquals(0, result.filteredSegmentCount());
    }

    @Test
    void 필터를_끄면_모든_세그먼트를_남긴다() {
        RecordingTranscriptPort port = new RecordingTranscriptPort();
        AssembleTranscriptService disabled = new AssembleTranscriptService(
                port, Clock.fixed(NOW, ZoneOffset.UTC), TranscriptFilterSettings.disabled());
        List<TranscriptionChunk> chunks = List.of(chunk(
                STUDENT_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(
                        segment(15_000, 18_000, HALLUCINATION, 0.953),
                        segment(165_000, 174_000, STUDENT_QUESTION, 0.176))));

        AssembleTranscriptResult result = disabled.assemble(
                new AssembleTranscriptCommand(SESSION_ID, "ko", List.of(track(STUDENT_FILE, STUDENT, 0L)), chunks));

        assertEquals(List.of(HALLUCINATION, STUDENT_QUESTION), textsOf(port.only()));
        assertEquals(0, result.filteredSegmentCount(), "끈 상태에서는 센 것도 없어야 한다");
    }

    @Test
    void 모든_세그먼트가_빠져도_빈_전사로_정상_저장한다() {
        // 실패가 아니다. partial 로 바꾸지도 않는다 — partial=true 는 "구간이 빠진 전사" 를 뜻하고
        // 그것을 소비하는 계약이 하류에 없다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                STUDENT_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(
                        segment(15_000, 18_000, HALLUCINATION, 0.953),
                        segment(45_000, 48_000, HALLUCINATION, 0.984),
                        segment(75_000, 78_000, HALLUCINATION, 0.906))));

        AssembleTranscriptResult result = assemble(List.of(track(STUDENT_FILE, STUDENT, 0L)), chunks);

        TranscriptDocument document = transcriptPort.only();
        assertTrue(document.segments().isEmpty());
        assertTrue(!document.partial(), "필터는 누락이 아니므로 partial 이 아니다");
        assertEquals(TranscriptDocument.SCHEMA_VERSION, document.schemaVersion());
        assertEquals("ko", document.language());
        assertEquals(0, result.segmentCount());
        assertEquals(0, result.speakerCount());
        assertEquals(3, result.filteredSegmentCount());
    }

    @Test
    void 필터링_후에도_화자_무관_시간순_정렬을_유지한다() {
        // 두 화자의 트랙에서 각각 환각이 빠진 뒤에도 전체가 절대 시간순이어야 한다. 남은 세그먼트의
        // 시각은 손대지 않는다 — 빠진 자리를 메우려고 당기면 타임라인이 녹화와 어긋난다.
        List<TranscriptionChunk> chunks = List.of(
                chunk(
                        INSTRUCTOR_FILE,
                        0,
                        TranscriptionChunkStatus.SUCCEEDED,
                        List.of(
                                segment(2_000, 32_000, "오늘은 해시 테이블을 다룹니다", 0.04),
                                segment(100_000, 103_000, HALLUCINATION, 0.973),
                                segment(400_000, 431_000, "체이닝과 개방 주소법", 0.06))),
                chunk(
                        STUDENT_FILE,
                        0,
                        TranscriptionChunkStatus.SUCCEEDED,
                        List.of(
                                segment(15_000, 18_000, HALLUCINATION, 0.940),
                                segment(165_000, 174_000, STUDENT_QUESTION, 0.176))));

        AssembleTranscriptResult result = assemble(
                List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L), track(STUDENT_FILE, STUDENT, 600_000L)), chunks);

        List<TranscriptDocumentSegment> segments = transcriptPort.only().segments();
        assertEquals(List.of("오늘은 해시 테이블을 다룹니다", "체이닝과 개방 주소법", STUDENT_QUESTION), textsOf(transcriptPort.only()));
        // 학생 트랙은 수업 10분 뒤에 발행됐으므로 600_000 + 165_000 = 765_000 이고, 강사의 400_000 뒤다.
        assertEquals(
                List.of(2_000L, 400_000L, 765_000L),
                segments.stream().map(TranscriptDocumentSegment::startOffsetMs).toList());
        assertEquals(2, result.filteredSegmentCount());
        assertEquals(2, result.speakerCount());
    }

    @Test
    void 남은_세그먼트의_시각과_식별자와_확률값은_그대로다() {
        List<TranscriptionChunk> chunks = List.of(chunk(
                STUDENT_FILE,
                2,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(
                        segment(5_000, 8_000, HALLUCINATION, 0.983),
                        segment(165_000, 174_000, STUDENT_QUESTION, 0.176))));

        assemble(List.of(track(STUDENT_FILE, STUDENT, 0L)), chunks);

        TranscriptDocumentSegment stored = transcriptPort.only().segments().get(0);
        // 2번 청크의 165초 지점 = 1_200_000 + 165_000. 앞 세그먼트가 빠졌어도 당겨지지 않는다.
        assertEquals(1_365_000, stored.startOffsetMs());
        assertEquals(1_374_000, stored.endOffsetMs());
        assertEquals(0.176, stored.noSpeechProb(), 1e-9);
        assertEquals(-0.21, stored.avgLogprob(), 1e-9);
        assertEquals(Math.exp(-0.21), stored.confidence(), 1e-9);
        assertEquals(ConfidenceMethod.EXP_AVG_LOGPROB, stored.confidenceMethod());
        assertEquals(STUDENT_FILE, stored.recordingFileId());
        assertEquals(2, stored.chunkIndex());
        assertEquals(STUDENT, stored.sessionParticipantId());
    }

    @Test
    void 같은_입력을_다시_조립하면_같은_문서가_나온다() {
        // 멱등. 체크포인트가 그대로면 재조립 결과도 그대로여야 하고, 그것이 "GMS 없이 재조립할 수 있다" 의
        // 전제다. 임곗값을 바꾸면 결과가 달라지는 것은 의도된 동작이므로 같은 임곗값으로 두 번 돈다.
        List<TranscriptionTrack> tracks = List.of(track(STUDENT_FILE, STUDENT, 0L));
        List<TranscriptionChunk> chunks = List.of(chunk(
                STUDENT_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(
                        segment(15_000, 18_000, HALLUCINATION, 0.953),
                        segment(165_000, 174_000, STUDENT_QUESTION, 0.176))));

        AssembleTranscriptResult first = assemble(tracks, chunks);
        RecordingTranscriptPort second = new RecordingTranscriptPort();
        AssembleTranscriptResult again = service(second, DEFAULT_THRESHOLD)
                .assemble(new AssembleTranscriptCommand(SESSION_ID, "ko", tracks, chunks));

        assertEquals(first, again);
        assertEquals(textsOf(transcriptPort.only()), textsOf(second.only()));
        assertEquals(
                transcriptPort.only().segments().stream()
                        .map(TranscriptDocumentSegment::startOffsetMs)
                        .toList(),
                second.only().segments().stream()
                        .map(TranscriptDocumentSegment::startOffsetMs)
                        .toList());
    }

    @Test
    void 재현_응답_전체를_그대로_조립하면_환각_8건이_빠지고_질문이_남는다() {
        // 2026-08-05 실제 세션의 학생 마이크 응답(240.21초) 14 세그먼트를 순서까지 그대로 옮긴 것이다.
        // 이 티켓이 존재하는 이유이므로 fixture 로 고정한다.
        //
        // 30초 간격으로 반복된 8건은 창 확률이 0.906~0.985 이고, seek=15000 창에 든 6건(환각 5 + 실제
        // 질문 1)은 전부 0.176 이다. 무음 확률로 8건을 지우고 질문을 지키는 것이 이번 범위이며,
        // 같은 창의 5건은 반복 문구 규칙(S15P11A105-316)의 몫이다.
        //
        // id=11 이 이 fixture 의 핵심이다. 질문이 끝난 174.0 초에서 0ms 로 이어지므로 시각만 보는
        // 인접성 가드는 이것을 "실제 발화의 연속" 으로 착각한다. 앞 세그먼트가 물음표로 문장을 끝냈다는
        // 사실이 그 착각을 막는다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                STUDENT_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(
                        segment(0, 5_500, HALLUCINATION, 0.9534643292427063), // seek=0
                        segment(30_000, 35_500, HALLUCINATION, 0.9847848415374756), // seek=3000
                        segment(60_000, 65_500, HALLUCINATION, 0.9836857914924622), // seek=6000
                        segment(90_000, 95_500, HALLUCINATION, 0.9767805337905884), // seek=9000
                        segment(120_000, 125_500, HALLUCINATION, 0.9736445546150208), // seek=12000
                        segment(150_000, 153_000, HALLUCINATION, 0.1763685643672943), // seek=15000
                        segment(153_000, 156_000, HALLUCINATION, 0.1763685643672943),
                        segment(156_000, 159_000, HALLUCINATION, 0.1763685643672943),
                        segment(159_000, 162_000, HALLUCINATION, 0.1763685643672943),
                        segment(162_000, 165_000, HALLUCINATION, 0.1763685643672943),
                        segment(165_000, 174_000, STUDENT_QUESTION, 0.1763685643672943),
                        segment(174_000, 179_500, HALLUCINATION, 0.906367301940918), // seek=17400
                        segment(204_000, 209_500, HALLUCINATION, 0.9249671101570129), // seek=20400
                        segment(234_000, 239_500, HALLUCINATION, 0.9403244256973267)))); // seek=23400

        AssembleTranscriptResult result = assemble(List.of(track(STUDENT_FILE, STUDENT, 0L)), chunks);

        assertEquals(8, result.filteredSegmentCount(), "무음 확률이 높은 8건이 빠진다");
        assertEquals(6, result.segmentCount(), "같은 창의 5건 + 실제 질문");
        List<TranscriptDocumentSegment> stored = transcriptPort.only().segments();
        // 실제 질문은 시각까지 그대로 남는다.
        assertEquals(STUDENT_QUESTION, stored.get(5).text());
        assertEquals(165_000, stored.get(5).startOffsetMs());
        assertEquals(174_000, stored.get(5).endOffsetMs());
        // 질문 바로 뒤에 0ms 로 붙은 환각은 살아남지 않는다.
        assertEquals(
                List.of(150_000L, 153_000L, 156_000L, 159_000L, 162_000L, 165_000L),
                stored.stream().map(TranscriptDocumentSegment::startOffsetMs).toList());
    }

    // ---------------------------------------------------------------------
    // 인접성 가드
    //
    // no_speech_prob 는 세그먼트 값이 아니라 30초 디코딩 창의 값이다 — 실측에서 같은 seek 을 공유하는
    // 세그먼트 6개가 모두 0.008 로 동일했다. 그래서 창 경계를 넘어간 문장의 뒷부분이 무음 창의 값을
    // 물려받는다. 아래 세 세그먼트가 실제 강사 녹음(4.6분)에서 그대로 나온 값이다.
    // ---------------------------------------------------------------------

    @Test
    void 창_경계를_넘어간_문장의_뒷부분은_남긴다() {
        // seek=19600 nsp=0.067 / seek=21728 nsp=0.964 — 두 번째는 첫 번째와 0ms 로 맞물린 한 문장의
        // 뒷부분이다. 무음 확률만 보고 지우면 타임라인에서 문장이 중간에 끊긴다. 임곗값을 올려서는
        // 못 막는다(0.964).
        List<TranscriptionChunk> chunks = List.of(chunk(
                INSTRUCTOR_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(
                        segment(212_600, 217_280, "다엑스트라 알고리즘은 가장 가까운 정점을 선택하고 간선완화연산", 0.067),
                        segment(217_280, 218_940, "을 반복하여 최단거리를 구합니다.", 0.964),
                        segment(247_280, 275_800, "학생이 자네에 피로가 났대요.", 0.907))));

        AssembleTranscriptResult result = assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), chunks);

        assertEquals(
                List.of("다엑스트라 알고리즘은 가장 가까운 정점을 선택하고 간선완화연산", "을 반복하여 최단거리를 구합니다."), textsOf(transcriptPort.only()));
        // 28.3초 공백 뒤의 환각은 앵커가 없어 그대로 빠진다.
        assertEquals(1, result.filteredSegmentCount());
    }

    @Test
    void 앞이_아니라_뒤에_실제_발화가_붙어_있어도_남긴다() {
        // 문장의 앞부분이 무음 창에 걸린 경우. 양쪽을 다 보지 않으면 이쪽을 놓친다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                INSTRUCTOR_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(
                        segment(100_000, 101_500, "그러면 이제", 0.95),
                        segment(101_500, 108_000, "간선 완화 연산을 반복합니다", 0.04))));

        AssembleTranscriptResult result = assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), chunks);

        assertEquals(List.of("그러면 이제", "간선 완화 연산을 반복합니다"), textsOf(transcriptPort.only()));
        assertEquals(0, result.filteredSegmentCount());
    }

    @Test
    void 환각끼리_붙어_있는_것은_서로를_살리지_못한다() {
        // 앵커를 "임곗값 미만" 으로 못 박은 이유. "이웃이 남았으면 살린다" 로 두면 환각 사슬이 전부
        // 살아남아 필터가 사실상 무력해진다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                STUDENT_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(
                        segment(15_000, 18_000, HALLUCINATION, 0.953),
                        segment(18_000, 21_000, HALLUCINATION, 0.984),
                        segment(21_000, 24_000, HALLUCINATION, 0.973))));

        AssembleTranscriptResult result = assemble(List.of(track(STUDENT_FILE, STUDENT, 0L)), chunks);

        assertTrue(transcriptPort.only().segments().isEmpty());
        assertEquals(3, result.filteredSegmentCount());
    }

    @Test
    void 앞_문장이_끝났으면_붙어_있어도_살리지_않는다() {
        // 재현 응답의 id=10/id=11 을 떼어 낸 것이다. 시각으로는 강사의 진짜 연속과 구별되지 않으므로
        // 앞 세그먼트가 문장을 끝냈는지가 유일한 신호다. 이 검사가 없으면 환각 하나가 남는다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                STUDENT_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(
                        segment(165_000, 174_000, STUDENT_QUESTION, 0.176),
                        segment(174_000, 179_500, HALLUCINATION, 0.906))));

        AssembleTranscriptResult result = assemble(List.of(track(STUDENT_FILE, STUDENT, 0L)), chunks);

        assertEquals(List.of(STUDENT_QUESTION), textsOf(transcriptPort.only()));
        assertEquals(1, result.filteredSegmentCount());
    }

    @Test
    void 쉼표로_끝난_문장은_이어지는_것으로_본다() {
        // 쉼표를 종결로 취급하면 진짜 연속을 잃는다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                INSTRUCTOR_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(
                        segment(10_000, 14_000, "간선 완화 연산을 반복하면,", 0.05),
                        segment(14_000, 16_000, "최단거리가 구해집니다.", 0.95))));

        AssembleTranscriptResult result = assemble(List.of(track(INSTRUCTOR_FILE, INSTRUCTOR, 0L)), chunks);

        assertEquals(List.of("간선 완화 연산을 반복하면,", "최단거리가 구해집니다."), textsOf(transcriptPort.only()));
        assertEquals(0, result.filteredSegmentCount());
    }

    @Test
    void 공백이_허용폭보다_크면_인접으로_보지_않는다() {
        // 허용폭은 200ms 다. 실측에서 창 경계로 쪼개진 문장의 두 조각은 정확히 0ms 로 맞물렸고,
        // 넓게 두면 진짜 발화가 끝난 한참 뒤에 시작한 환각까지 살려 준다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                STUDENT_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(segment(10_000, 12_000, "실제 발화입니다", 0.05), segment(12_500, 15_000, HALLUCINATION, 0.953))));

        AssembleTranscriptResult result = assemble(List.of(track(STUDENT_FILE, STUDENT, 0L)), chunks);

        assertEquals(List.of("실제 발화입니다"), textsOf(transcriptPort.only()));
        assertEquals(1, result.filteredSegmentCount());
    }

    @Test
    void 허용폭_안의_공백은_인접으로_본다() {
        // GMS 타임스탬프 해상도가 파일마다 다르다 — 정수 초 격자인 응답도 있었다. 0 으로 못 박으면
        // 격자가 거친 파일에서 맞물린 문장을 놓친다.
        List<TranscriptionChunk> chunks = List.of(chunk(
                STUDENT_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(segment(10_000, 12_000, "실제 발화입니다", 0.05), segment(12_200, 15_000, "그 뒤에 이어지는 말", 0.953))));

        AssembleTranscriptResult result = assemble(List.of(track(STUDENT_FILE, STUDENT, 0L)), chunks);

        assertEquals(List.of("실제 발화입니다", "그 뒤에 이어지는 말"), textsOf(transcriptPort.only()));
        assertEquals(0, result.filteredSegmentCount());
    }

    @Test
    void 청크가_다르면_앵커로_쓰지_않는다() {
        // 알려진 한계를 고정한다. 10분 청크 경계에서 문장이 쪼개지면 앵커가 다른 청크에 있어 찾지 못하고,
        // 그때는 원래 규칙대로 빠진다. 동작이 바뀌면 이 테스트가 알려 준다.
        List<TranscriptionChunk> chunks = List.of(
                chunk(
                        STUDENT_FILE,
                        0,
                        TranscriptionChunkStatus.SUCCEEDED,
                        List.of(segment(CHUNK_MS - 3_000, CHUNK_MS, "청크 끝에서 시작된 문장이", 0.05))),
                chunk(
                        STUDENT_FILE,
                        1,
                        TranscriptionChunkStatus.SUCCEEDED,
                        List.of(segment(0, 2_000, "다음 청크로 이어진다", 0.95))));

        AssembleTranscriptResult result = assemble(List.of(track(STUDENT_FILE, STUDENT, 0L)), chunks);

        assertEquals(List.of("청크 끝에서 시작된 문장이"), textsOf(transcriptPort.only()));
        assertEquals(1, result.filteredSegmentCount());
    }

    // ---------------------------------------------------------------------
    // 범위 밖 타임스탬프(S15P11A105-324)
    //
    // 환각은 무음 구간에 5.5초 고정 길이 세그먼트를 찍으므로 오디오 끝 근처에서 구조적으로 범위를
    // 넘는다. 그 세그먼트를 거르기 전에 경계 검사를 하면, 어차피 버릴 것 하나 때문에 수업 하나가
    // 통째로 저장되지 않는다. 실제로 그렇게 실패한 세션이 있었다.
    // ---------------------------------------------------------------------

    @Test
    void 범위를_넘긴_환각은_조립을_실패시키지_않고_빠진다() {
        // 실측(세션 873018724616549018, 학생 마이크 237,528ms 청크). 마지막 환각이 239,500ms 까지라고
        // 주장해 1,972ms 를 넘겼고, 허용폭 1,000ms 를 초과해 세션 전체가 FAILED 로 끝났다.
        long durationMs = 237_528L;
        TranscriptionChunk chunk = new TranscriptionChunk(
                1L,
                SESSION_ID,
                STUDENT_FILE,
                0,
                0L,
                durationMs,
                TranscriptionChunkStatus.SUCCEEDED,
                1,
                null,
                null,
                List.of(
                        segment(149_500, 152_500, "질문이 있습니다.", 0.1109),
                        segment(152_500, 167_500, "체인이 사용하면 하나의 넥스에 데이터가 너무", 0.1109),
                        segment(234_500, 239_500, HALLUCINATION, 0.9787)));

        AssembleTranscriptResult result = service.assemble(new AssembleTranscriptCommand(
                SESSION_ID, "ko", List.of(track(STUDENT_FILE, STUDENT, 0L)), List.of(chunk)));

        assertEquals(List.of("질문이 있습니다.", "체인이 사용하면 하나의 넥스에 데이터가 너무"), textsOf(transcriptPort.only()));
        assertEquals(1, result.filteredSegmentCount());
    }

    @Test
    void 범위를_넘긴_실제_발화는_여전히_분할_오류로_거절한다() {
        // 필터를 앞으로 옮겨도 탐지력이 유지되는지 본다. 남기기로 한 세그먼트가 범위를 크게 벗어나면
        // 그것은 환각이 아니라 분할이 잘못됐다는 신호이므로 조립을 멈춰야 한다.
        long durationMs = 237_528L;
        TranscriptionChunk chunk = new TranscriptionChunk(
                1L,
                SESSION_ID,
                STUDENT_FILE,
                0,
                0L,
                durationMs,
                TranscriptionChunkStatus.SUCCEEDED,
                1,
                null,
                null,
                List.of(segment(234_500, 239_500, "실제 발화인데 청크 밖으로 나간다", 0.05)));

        assertThrows(
                TranscriptAssemblyInvalidException.class,
                () -> service.assemble(new AssembleTranscriptCommand(
                        SESSION_ID, "ko", List.of(track(STUDENT_FILE, STUDENT, 0L)), List.of(chunk))));
    }

    @Test
    void 실측_세션의_학생_트랙을_그대로_조립한다() {
        // 세션 873018724616549018 학생 마이크 13 세그먼트를 순서·시각·확률 그대로 옮긴 것이다.
        // 환각 11건이 전부 같은 문구이고 #1~#8 이 연속이다 — 잔존분은 S15P11A105-316 의 대상이다.
        String repeated = "지금까지 재택 플러스였습니다.";
        TranscriptionChunk chunk = new TranscriptionChunk(
                1L,
                SESSION_ID,
                STUDENT_FILE,
                0,
                0L,
                237_528L,
                TranscriptionChunkStatus.SUCCEEDED,
                1,
                null,
                null,
                List.of(
                        segment(0, 1_840, repeated, 0.6215),
                        segment(30_000, 35_500, repeated, 0.9924),
                        segment(60_000, 65_500, repeated, 0.9920),
                        segment(90_000, 95_500, repeated, 0.9776),
                        segment(120_500, 128_000, repeated, 0.7898),
                        segment(130_500, 135_500, repeated, 0.7898),
                        segment(137_500, 144_500, repeated, 0.7898),
                        segment(144_500, 149_500, repeated, 0.1109),
                        segment(149_500, 152_500, "질문이 있습니다.", 0.1109),
                        segment(152_500, 167_500, "체인이 사용하면 하나의 넥스에 데이터가 너무", 0.1109),
                        segment(174_500, 179_500, repeated, 0.8946),
                        segment(204_500, 209_500, repeated, 0.9652),
                        segment(234_500, 239_500, repeated, 0.9787)));

        AssembleTranscriptResult result = service.assemble(new AssembleTranscriptCommand(
                SESSION_ID, "ko", List.of(track(STUDENT_FILE, STUDENT, 0L)), List.of(chunk)));

        // 조립이 끝까지 간다. 이것이 이 티켓의 핵심이다.
        assertEquals(6, result.filteredSegmentCount(), "0.895 이상 6건이 빠진다");
        assertEquals(7, result.segmentCount());
        // 실제 발화 두 건은 시각까지 그대로 남는다.
        List<TranscriptDocumentSegment> stored = transcriptPort.only().segments();
        assertEquals("질문이 있습니다.", stored.get(5).text());
        assertEquals(149_500, stored.get(5).startOffsetMs());
        assertEquals("체인이 사용하면 하나의 넥스에 데이터가 너무", stored.get(6).text());
        assertEquals(167_500, stored.get(6).endOffsetMs());
        // 임곗값 아래의 반복 환각은 이 규칙으로 잡히지 않는다(S15P11A105-316).
        assertEquals(
                5, stored.stream().filter(s -> s.text().equals(repeated)).count(), "0.79·0.62·0.11 로 나온 반복 환각은 남는다");
    }

    @Test
    void 임곗값을_올리면_같은_체크포인트에서_더_많이_남는다() {
        // 재조립만으로 판정을 바꿀 수 있다는 것을 고정한다. GMS 를 다시 부르지 않는다.
        List<TranscriptionTrack> tracks = List.of(track(STUDENT_FILE, STUDENT, 0L));
        List<TranscriptionChunk> chunks = List.of(chunk(
                STUDENT_FILE,
                0,
                TranscriptionChunkStatus.SUCCEEDED,
                List.of(
                        segment(15_000, 18_000, HALLUCINATION, 0.953),
                        segment(165_000, 174_000, STUDENT_QUESTION, 0.176))));

        RecordingTranscriptPort lenient = new RecordingTranscriptPort();
        service(lenient, 0.99).assemble(new AssembleTranscriptCommand(SESSION_ID, "ko", tracks, chunks));

        assertEquals(List.of(HALLUCINATION, STUDENT_QUESTION), textsOf(lenient.only()));
    }
}
