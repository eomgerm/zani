package com.a105.zani.postclass.application.assembletranscript;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

/**
 * 청크 결과를 수업 타임라인 하나로 합쳐 저장한다.
 *
 * <p>외부 호출이 없어 트랜잭션이 짧다. 조립은 순수 계산이고 쓰기는 한 행이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssembleTranscriptService implements AssembleTranscriptUseCase {

    /**
     * whisper 디코딩 창 하나의 길이. 세그먼트 종료 시각이 청크 길이를 넘는 것을 허용하는 폭으로 쓴다.
     *
     * <p><b>이것은 관측값에 맞춘 여유폭이 아니라 구조적 상한이다.</b> whisper 는 오디오를 30초 창으로 잘라 디코딩하고, 각 창의 세그먼트 타임스탬프는 그 창의 시작({@code seek})에
     * 상대적으로 매겨진다. {@code seek} 은 오디오 길이를 넘지 못하고 창 안의 종료 시각은 30초를 넘지 못하므로, whisper 가 만들 수 있는 최대 초과는 <b>30초</b>다. 이보다 큰
     * 초과는 whisper 가 만든 값이 아니라는 뜻이고, 그때는 그 세그먼트를 신뢰할 근거가 없다.
     *
     * <p><b>여기까지 오는 데 두 번의 사고가 있었다.</b> 처음 값은 1초로, 두 경우만 가정한 것이었다 — 분 단위로 어긋나는 분할 오류와, GMS 가 마지막 문장의 끝을 반올림해 몇 ms 넘기는
     * 정상 범위. 실측에는 세 번째 경우가 있다: <b>whisper 가 무음 구간에 찍는 환각의 종료 시각은 초 단위로 넘친다.</b>
     *
     * <pre>
     * 청크 241,267ms  세그먼트 242,000~244,000ms  초과 2,733ms   "감사합니다"
     * 청크 271,468ms  세그먼트 270,000~275,500ms  초과 4,032ms   "고맙습니다."
     * 청크 237,528ms  세그먼트 234,500~239,500ms  초과 1,972ms   "지금까지 재택 플러스였습니다."
     * </pre>
     *
     * <p>S15P11A105-324 는 이 검사를 필터 뒤로 옮겼고, 329 는 폭을 5초로 늘렸다. 둘 다 <b>같은 자리를 관측값에 맞춰 넓힌 것</b>이라 다음 초과 앞에서 다시 무너진다. 5초에는
     * 근거가 없다 — 그때까지 본 최대 초과가 4.0초였다는 것뿐이다. 30초는 whisper 의 동작에서 나오는 값이므로 관측이 늘어도 바뀌지 않는다.
     *
     * <p><b>이 검사는 남기기로 한 세그먼트에만 적용된다</b>(S15P11A105-324). 필터가 지울 세그먼트로 수업을 잃지 않도록 환각 판정을 먼저 한다.
     */
    private static final long DECODING_WINDOW_MS = 30_000L;

    /**
     * 문서 안의 세그먼트 순서.
     *
     * <p>시작 시각만으로는 순서가 확정되지 않는다. 서로 다른 화자의 트랙을 병합하므로 같은 시각에 여러 세그먼트가 있을 수 있고, 그때 순서가 실행마다 달라지면 같은 체크포인트에서 매번 다른 JSON 이
     * 나온다. 그러면 "재조립이 내용을 바꾸지 않는다" 를 확인할 수 없다. 그래서 동점을 끝까지 깬다.
     */
    private static final Comparator<TranscriptDocumentSegment> TIMELINE_ORDER = Comparator.comparingLong(
                    TranscriptDocumentSegment::startOffsetMs)
            .thenComparingLong(TranscriptDocumentSegment::endOffsetMs)
            .thenComparingLong(TranscriptDocumentSegment::sessionParticipantId)
            .thenComparingLong(TranscriptDocumentSegment::recordingFileId)
            .thenComparingInt(TranscriptDocumentSegment::chunkIndex);

    private final TranscriptPort transcriptPort;
    private final Clock clock;
    private final TranscriptFilterSettings filterSettings;

    @Override
    @Transactional
    public AssembleTranscriptResult assemble(AssembleTranscriptCommand command) {
        Map<Long, TranscriptionTrack> tracks = indexTracks(command);
        // 단계 검사는 전체를 두 번 훑는다. 순서대로 확인하며 첫 예외에서 멈추면 안 된다 — 상세한 이유는
        // requireNoPermanentFailure 에 적었다.
        requireStructurallySound(command, tracks);
        requireNoPermanentFailure(command);
        requireEveryChunkFinished(command);

        List<TranscriptDocumentSegment> segments = new ArrayList<>();
        int totalSegmentCount = 0;
        int filteredSegmentCount = 0;
        int rescuedSegmentCount = 0;
        int unplaceableSegmentCount = 0;
        for (TranscriptionChunk chunk : command.chunks()) {
            TranscriptionTrack track = tracks.get(chunk.recordingFileId());
            List<TranscriptSegment> source = chunk.segments();
            totalSegmentCount += source.size();

            // 반복 판정을 먼저 한다. 무음 필터가 반복 중 일부를 먼저 빼면 남은 것들이 짧은 조각으로
            // 갈려 "연속 3회" 를 못 채운다 — 실측에서 8연속 반복이 무음 필터를 먼저 태우면 1+4 로
            // 쪼개져 앞의 하나가 살아남았다. 원본 순서 그대로 봐야 한 덩어리로 잡힌다.
            boolean[] repeated = markRepeatedPhrases(source);

            int filteredInChunk = 0;
            int repeatedInChunk = 0;
            int rescuedInChunk = 0;
            int unplaceableInChunk = 0;
            for (int index = 0; index < source.size(); index++) {
                TranscriptSegment candidate = source.get(index);
                if (repeated[index]) {
                    repeatedInChunk++;
                    continue;
                }
                if (filterSettings.exceedsNoSpeechThreshold(candidate.noSpeechProb())) {
                    if (continuesRealSpeech(source, index)) {
                        rescuedInChunk++;
                    } else {
                        filteredInChunk++;
                        continue;
                    }
                }
                // 남기기로 한 것만 문서 세그먼트로 옮긴다. 청크 경계 검사가 여기서 돈다. 놓을 수 없는
                // 세그먼트는 그것만 빠지고 조립은 계속된다(S15P11A105-330).
                Optional<TranscriptDocumentSegment> placed = toDocumentSegment(track, chunk, candidate);
                if (placed.isEmpty()) {
                    unplaceableInChunk++;
                    continue;
                }
                segments.add(placed.get());
            }
            filteredSegmentCount += filteredInChunk + repeatedInChunk;
            rescuedSegmentCount += rescuedInChunk;
            unplaceableSegmentCount += unplaceableInChunk;
            if (filteredInChunk > 0 || repeatedInChunk > 0 || rescuedInChunk > 0 || unplaceableInChunk > 0) {
                // 청크 단위로 남긴다 — 어느 트랙의 어느 구간이 통째로 무음이었는지는 운영에서 볼 일이다.
                // 텍스트는 남기지 않는다: 이 로그의 목적은 몇 개가 빠졌는지이고, 무엇이 빠졌는지는 체크포인트에 있다.
                log.info(
                        "Filtered hallucination segments: sessionId={}, recordingFileId={}, chunkIndex={},"
                                + " chunkSegmentCount={}, silenceFilteredCount={}, repeatFilteredCount={},"
                                + " rescuedSegmentCount={}, unplaceableSegmentCount={}, threshold={}",
                        command.sessionId(),
                        chunk.recordingFileId(),
                        chunk.chunkIndex(),
                        source.size(),
                        filteredInChunk,
                        repeatedInChunk,
                        rescuedInChunk,
                        unplaceableInChunk,
                        filterSettings.noSpeechThreshold());
            }
        }
        segments.sort(TIMELINE_ORDER);

        TranscriptDocument document = TranscriptDocument.complete(command.language(), segments);
        transcriptPort.save(command.sessionId(), document, clock.instant());

        AssembleTranscriptResult result = summarize(segments, filteredSegmentCount);
        if (result.segmentCount() == 0) {
            // 실패가 아니다. 아무도 마이크를 열지 않은 수업이면 빈 전사가 사실이고, 필터가 전부 걸러 낸
            // 것도 마찬가지다. 다만 조용히 넘기면 "왜 리포트가 비었나" 에 답할 근거가 없어진다.
            // 세 경우를 구분해 남긴다 — 대응이 다르다(녹화를 보라 vs 임곗값을 보라 vs 타임스탬프를 보라).
            log.warn(
                    "Assembled an empty transcript: sessionId={}, totalSegmentCount={}, filteredSegmentCount={},"
                            + " unplaceableSegmentCount={}, threshold={}",
                    command.sessionId(),
                    totalSegmentCount,
                    filteredSegmentCount,
                    unplaceableSegmentCount,
                    filterSettings.noSpeechThreshold());
        } else {
            log.info(
                    "Assembled transcript: sessionId={}, segments={}, speakers={}, lastEndOffsetMs={},"
                            + " totalSegmentCount={}, filteredSegmentCount={}, rescuedSegmentCount={},"
                            + " unplaceableSegmentCount={}",
                    command.sessionId(),
                    result.segmentCount(),
                    result.speakerCount(),
                    result.lastEndOffsetMs(),
                    totalSegmentCount,
                    filteredSegmentCount,
                    rescuedSegmentCount,
                    unplaceableSegmentCount);
        }
        return result;
    }

    /**
     * 트랙을 파일 id 로 색인한다. 이 시점에 화자·트랙 종류·시작 시각이 다 있는지 확인한다.
     *
     * <p>세 값 중 하나라도 없으면 그 트랙의 세그먼트를 타임라인에 놓을 수 없다. 특히 {@code startedOffsetMs} 를 0 으로 가정하면 안 된다 — 수업 30분 뒤에 재접속한 학생의 발화가
     * 전부 수업 시작 지점으로 밀리고, 결과 리포트는 그 학생이 도입부에서만 말했다고 집계한다.
     */
    private Map<Long, TranscriptionTrack> indexTracks(AssembleTranscriptCommand command) {
        Map<Long, TranscriptionTrack> indexed = new HashMap<>();
        for (TranscriptionTrack track : command.tracks()) {
            if (track.recordingFileId() == null
                    || track.sessionParticipantId() == null
                    || track.trackSource() == null
                    || track.startedOffsetMs() == null) {
                log.error(
                        "Track cannot be placed on the lesson timeline: sessionId={}, recordingFileId={},"
                                + " sessionParticipantId={}, trackSource={}, startedOffsetMs={}",
                        command.sessionId(),
                        track.recordingFileId(),
                        track.sessionParticipantId(),
                        track.trackSource(),
                        track.startedOffsetMs());
                throw new TranscriptAssemblyInvalidException();
            }
            if (indexed.put(track.recordingFileId(), track) != null) {
                log.error(
                        "Duplicate track for the same recording file: sessionId={}, recordingFileId={}",
                        command.sessionId(),
                        track.recordingFileId());
                throw new TranscriptAssemblyInvalidException();
            }
        }
        return indexed;
    }

    /**
     * 데이터 자체가 틀린 것을 먼저 거른다 — 남의 세션 청크, 중복 청크, 트랙 없는 청크.
     *
     * <p>모두 재시도 불가이므로 단계 검사보다 앞에 둔다. 뒤에 두면 아직 처리 중인 청크가 하나 있을 때 재시도 가능으로 판정돼, 잘못된 데이터가 재시도 예산을 다 쓴 뒤에야 드러난다.
     */
    private void requireStructurallySound(AssembleTranscriptCommand command, Map<Long, TranscriptionTrack> tracks) {
        Set<String> seen = new HashSet<>();
        for (TranscriptionChunk chunk : command.chunks()) {
            if (!command.sessionId().equals(chunk.sessionId())) {
                log.error(
                        "Chunk belongs to another session: expected={}, actual={}, chunkId={}",
                        command.sessionId(),
                        chunk.sessionId(),
                        chunk.id());
                throw new TranscriptAssemblyInvalidException();
            }
            if (!seen.add(chunk.recordingFileId() + ":" + chunk.chunkIndex())) {
                log.error(
                        "Duplicate chunk in the assembly input: recordingFileId={}, chunkIndex={}",
                        chunk.recordingFileId(),
                        chunk.chunkIndex());
                throw new TranscriptAssemblyInvalidException();
            }
            if (!tracks.containsKey(chunk.recordingFileId())) {
                log.error(
                        "No track for a chunk, cannot resolve its speaker: chunkId={}, recordingFileId={}",
                        chunk.id(),
                        chunk.recordingFileId());
                throw new TranscriptAssemblyInvalidException();
            }
        }
    }

    /**
     * 영구 실패한 청크를 <b>전체에서</b> 먼저 찾는다.
     *
     * <p>청크를 하나씩 보며 그 자리에서 던지면 판정이 순서에 좌우된다. {@code chunk 0 = PENDING}, {@code chunk 1 = FAILED} 인 경우 0 번에서 먼저
     * {@link TranscriptNotReadyException} 이 나가 {@code retryable=true} 로 기록되고, 뒤의 영구 실패는 보이지 않는다. 그러면 재시도 예산을 다 쓸 때까지 매번
     * 같은 자리에서 멈추고, 8시간 뒤에야 최종 실패가 된다 — "하나라도 영구 실패면 전체 비재시도" 규칙이 청크 순서에 따라 깨지는 것이다.
     *
     * <p>그래서 전체를 훑어 영구 실패가 있는지 먼저 판정한다. 몇 개인지도 함께 남긴다 — 하나가 실패한 것과 트랙 하나가 통째로 실패한 것은 운영에서 다르게 대응할 일이다.
     */
    private void requireNoPermanentFailure(AssembleTranscriptCommand command) {
        List<TranscriptionChunk> failed = command.chunks().stream()
                .filter(chunk -> chunk.status().isTerminal() && !chunk.status().contributesToTranscript())
                .toList();
        if (failed.isEmpty()) {
            return;
        }
        log.error(
                "Refusing to store an incomplete transcript, {} of {} chunks permanently failed: sessionId={},"
                        + " firstFailed=(recordingFileId={}, chunkIndex={}, attemptCount={})",
                failed.size(),
                command.chunks().size(),
                command.sessionId(),
                failed.get(0).recordingFileId(),
                failed.get(0).chunkIndex(),
                failed.get(0).attemptCount());
        throw new TranscriptIncompleteException();
    }

    /**
     * 아직 종결되지 않은 청크가 있는지 본다. 영구 실패 검사를 통과한 뒤에만 부른다.
     *
     * <p>이쪽만 재시도 가능이다. 기다리면 끝나므로 파이프라인을 최종 실패로 만들지 않는다.
     */
    private void requireEveryChunkFinished(AssembleTranscriptCommand command) {
        List<TranscriptionChunk> unfinished = command.chunks().stream()
                .filter(chunk -> !chunk.status().isTerminal())
                .toList();
        if (unfinished.isEmpty()) {
            return;
        }
        // 데이터는 멀쩡하므로 재시도 가능이지만, 종결을 기다리지 않고 조립을 부른 것 자체가
        // 오케스트레이션 버그일 수 있어 ERROR 로 남긴다.
        log.error(
                "Assembly called before every chunk finished: sessionId={}, unfinished={} of {}, first=(chunkId={},"
                        + " recordingFileId={}, chunkIndex={}, status={})",
                command.sessionId(),
                unfinished.size(),
                command.chunks().size(),
                unfinished.get(0).id(),
                unfinished.get(0).recordingFileId(),
                unfinished.get(0).chunkIndex(),
                unfinished.get(0).status());
        throw new TranscriptNotReadyException();
    }

    /**
     * 같은 짧은 문구가 연속으로 반복된 구간을 찾아 뺄 자리를 표시한다(S15P11A105-316).
     *
     * <p><b>왜 텍스트를 보는가.</b> {@code no_speech_prob} 는 30초 디코딩 창의 값이라 실제 발화와 같은 창에 떨어진 환각은 발화와 값이 똑같다 — 실측에서 학생 질문과 환각
     * 5건이 모두 {@code 0.1109} 였다. whisper 가 주는 품질 지표가 전부 창 단위라 어떤 조합으로도 그 둘을 가를 수 없고, 남는 신호가 텍스트뿐이다.
     *
     * <p>판정 조건과 그 근거는 {@link TranscriptFilterSettings} 에 있다. 여기서는 <b>연속 구간을 끊는 일</b>만 한다 — 사이에 다른 발화가 끼면 다른 구간으로 본다.
     *
     * @return 인덱스별로 뺄지 여부. 필터가 꺼져 있으면 전부 {@code false}
     */
    private boolean[] markRepeatedPhrases(List<TranscriptSegment> source) {
        boolean[] repeated = new boolean[source.size()];
        int runStart = 0;
        for (int index = 1; index <= source.size(); index++) {
            boolean sameAsPrevious = index < source.size()
                    && filterSettings
                            .normalizeForRepeat(source.get(index).text())
                            .equals(filterSettings.normalizeForRepeat(
                                    source.get(index - 1).text()));
            if (sameAsPrevious) {
                continue;
            }
            markRun(source, repeated, runStart, index);
            runStart = index;
        }
        return repeated;
    }

    /** 반복 구간 하나를 판정한다. {@code [from, toExclusive)} 는 정규화 텍스트가 같은 연속 구간이다. */
    private void markRun(List<TranscriptSegment> source, boolean[] repeated, int from, int toExclusive) {
        int runLength = toExclusive - from;
        String normalized = filterSettings.normalizeForRepeat(source.get(from).text());
        if (!filterSettings.isRepeatCandidate(normalized, runLength)) {
            return;
        }
        int silentCount = 0;
        for (int index = from; index < toExclusive; index++) {
            if (filterSettings.looksSilent(source.get(index).noSpeechProb())) {
                silentCount++;
            }
        }
        // 무음 위에서 만들어진 반복이면 전부 뺀다. 아니면 첫 하나를 남긴다 — 강사가 실제로 반복해
        // 말했을 수 있으므로 근거가 없으면 흔적을 남긴다.
        int keepFrom = filterSettings.dropsWholeRun(runLength, silentCount) ? from : from + 1;
        for (int index = keepFrom; index < toExclusive; index++) {
            repeated[index] = true;
        }
    }

    /**
     * 무음 확률이 높은 세그먼트가 <b>실제 발화의 연속</b>인지 본다.
     *
     * <p>{@code no_speech_prob} 는 세그먼트 값이 아니라 30초 디코딩 창의 값이다(같은 {@code seek} 을 공유하는 세그먼트는 값이 전부 같다). 그래서 한 문장이 창 경계를
     * 넘으면 뒷조각이 거의 무음인 다음 창의 값을 물려받아, 무음 확률만 보고 지우면 문장이 중간에서 끊긴다. 실측에서 강사의 {@code "을 반복하여 최단거리를 구합니다."} 가 {@code 0.964}
     * 로 나왔는데, 그것은 바로 앞 조각과 시각이 정확히 맞물린 한 문장의 뒷부분이었다.
     *
     * <p>그래서 앞뒤 이웃 중 <b>앵커</b>(무음 확률이 임곗값 미만인 세그먼트)와 시각이 맞물린 것이 있으면 남긴다. 진짜 무음 환각은 앞뒤가 공백이다 — 같은 녹음에서 28.3초 공백 뒤에 나온
     * 환각은 이 검사를 통과하지 못한다.
     *
     * <p><b>한계.</b> 같은 청크 안에서만 본다. 10분 청크가 갈리는 자리에서 문장이 쪼개지면 앵커가 다른 청크에 있어 찾지 못하고, 그때는 원래 규칙대로 빠진다. 청크마다 한 번뿐인 경계라 감수한다
     * — 청크를 넘어 이웃을 찾으려면 조립이 트랙별 청크 순서를 다시 세워야 하고, 그 복잡도가 이득보다 크다.
     *
     * <p><b>청크 기준 상대 시각으로 판정한다.</b> 절대 시각으로 옮긴 뒤 보는 것과 결과가 같다 — 같은 청크의 세그먼트는 모두 같은 base 를 더하므로 <b>차이</b>가 보존된다. 그리고 판정을
     * 변환 앞에 두어야 한다(S15P11A105-324): 변환이 청크 경계 검사를 겸하는데, 환각은 오디오 끝을 넘는 시각을 찍는 일이 흔해서 뒤에 두면 <b>지울 세그먼트 때문에 세션 전체 조립이
     * 죽는다.</b>
     */
    private boolean continuesRealSpeech(List<TranscriptSegment> source, int index) {
        return anchoredBefore(source, index) || anchoredAfter(source, index);
    }

    /** 앞 세그먼트가 실제 발화이고, 문장을 끝내지 않은 채로 이 세그먼트와 맞물려 있는가. */
    private boolean anchoredBefore(List<TranscriptSegment> source, int index) {
        if (index == 0) {
            return false;
        }
        TranscriptSegment previous = source.get(index - 1);
        return filterSettings.anchors(previous.noSpeechProb())
                && filterSettings.adjacent(previous.endMs(), source.get(index).startMs())
                && filterSettings.sentenceContinues(previous.text());
    }

    /**
     * 이 세그먼트가 문장을 끝내지 않은 채로 뒤의 실제 발화와 맞물려 있는가.
     *
     * <p>종결 여부를 보는 대상이 앞쪽 검사와 다르다. 경계 {@code A -> B} 에서 문장이 이어지는지는 항상 <b>앞선 쪽</b>의 끝으로 판정하고, 이 경우 앞선 쪽이 후보 자신이다.
     */
    private boolean anchoredAfter(List<TranscriptSegment> source, int index) {
        if (index + 1 >= source.size()) {
            return false;
        }
        TranscriptSegment candidate = source.get(index);
        TranscriptSegment next = source.get(index + 1);
        return filterSettings.anchors(next.noSpeechProb())
                && filterSettings.adjacent(candidate.endMs(), next.startMs())
                && filterSettings.sentenceContinues(candidate.text());
    }

    /**
     * 세 겹을 더해 수업 기준 절대 시각으로 옮긴다.
     *
     * @return 배치할 수 없는 타임스탬프면 {@link Optional#empty()}
     */
    private Optional<TranscriptDocumentSegment> toDocumentSegment(
            TranscriptionTrack track, TranscriptionChunk chunk, TranscriptSegment segment) {
        OptionalLong placed = placeableEndMs(chunk, segment);
        if (placed.isEmpty()) {
            return Optional.empty();
        }
        long base = track.startedOffsetMs() + chunk.sourceStartMs();
        long endMs = placed.getAsLong();
        return Optional.of(new TranscriptDocumentSegment(
                track.sessionParticipantId(),
                track.trackSource(),
                track.livekitTrackSid(),
                base + segment.startMs(),
                base + endMs,
                segment.text(),
                segment.avgLogprob(),
                segment.confidenceScore(),
                // confidenceScore() 가 exp(avgLogprob) 이다. 식과 이름이 한 자리에서 갈리지 않게 함께 적는다.
                ConfidenceMethod.EXP_AVG_LOGPROB,
                segment.noSpeechProb(),
                chunk.recordingFileId(),
                chunk.chunkIndex()));
    }

    /**
     * 세그먼트를 청크 안에 놓을 수 있는지 보고, 놓을 수 있으면 쓸 종료 시각을 준다(S15P11A105-330).
     *
     * <p><b>여기서 조립을 멈추지 않는다.</b> 놓을 수 없는 세그먼트는 그것만 버린다. 전에는 예외를 던졌고 그 때문에 세션 두 개가 죽었다 — whisper 가 찍은 타임스탬프 하나로 수업 하나의
     * 리포트를 잃는 교환은 성립하지 않는다.
     *
     * <p><b>분할 오류 탐지를 잃지 않는다.</b> 그 판정은 조립 전에 두 번, 더 강한 근거로 이뤄진다.
     *
     * <ul>
     *   <li>{@code verifyBoundaries} — 재분할한 경계가 저장된 체크포인트와 개수·순번·시작·종료까지 일치하는지 대조한다
     *   <li>{@code verifyDuration} — <b>GMS 가 보고한 오디오 길이</b>와 CSV 구간을 1초 폭으로 비교한다
     * </ul>
     *
     * <p>둘째가 결정적이다. whisper 자신이 "이 오디오는 이만큼이었다" 고 보고한 값이 우리 CSV 와 맞는 것을 이미 확인한 뒤에 조립이 시작된다. 그러면 범위 밖 종료 시각은 분할 오류일 수 없다
     * — 받은 오디오 길이는 맞게 알면서 타임스탬프만 틀리게 찍은 것이다.
     *
     * <p><b>어긋난 정도로 처분을 가른다.</b>
     *
     * <ul>
     *   <li>{@link #DECODING_WINDOW_MS} 안이면 청크 끝으로 맞추고 {@code WARN}. 문장의 시작 시각은 믿을 수 있고 끝만 조금 넘친 경우다
     *   <li>넘으면 <b>버리고</b> {@code ERROR}. 그 정도로 틀린 타임스탬프는 텍스트가 어느 시각에 속하는지 알 수 없어, 억지로 맞추면 엉뚱한 시각에 붙은 문장이 타임라인에 남는다
     *   <li>시작 시각이 청크 밖이면 <b>버린다</b>. 종료만 줄이면 {@code start > end} 인 구간이 만들어지고 어떤 소비자도 해석할 수 없다. {@code seek + 30초} 가
     *       오디오 끝을 넘을 수 있으므로 이것도 whisper 가 만들 수 있는 값이다
     * </ul>
     */
    private OptionalLong placeableEndMs(TranscriptionChunk chunk, TranscriptSegment segment) {
        if (segment.startMs() >= chunk.durationMs()) {
            log.error(
                    "Dropping a segment that starts outside its chunk: recordingFileId={}, chunkIndex={},"
                            + " chunkDurationMs={}, segmentStartMs={}, segmentEndMs={}",
                    chunk.recordingFileId(),
                    chunk.chunkIndex(),
                    chunk.durationMs(),
                    segment.startMs(),
                    segment.endMs());
            return OptionalLong.empty();
        }
        long overshoot = segment.endMs() - chunk.durationMs();
        if (overshoot <= 0) {
            return OptionalLong.of(segment.endMs());
        }
        if (overshoot > DECODING_WINDOW_MS) {
            log.error(
                    "Dropping a segment whose end is beyond one decoding window past its chunk: recordingFileId={},"
                            + " chunkIndex={}, chunkDurationMs={}, segmentEndMs={}, overshootMs={}",
                    chunk.recordingFileId(),
                    chunk.chunkIndex(),
                    chunk.durationMs(),
                    segment.endMs(),
                    overshoot);
            return OptionalLong.empty();
        }
        log.warn(
                "Trimming a segment end to the chunk boundary: recordingFileId={}, chunkIndex={}, overshootMs={}",
                chunk.recordingFileId(),
                chunk.chunkIndex(),
                overshoot);
        return OptionalLong.of(chunk.durationMs());
    }

    private AssembleTranscriptResult summarize(List<TranscriptDocumentSegment> segments, int filteredSegmentCount) {
        Set<Long> speakers = new HashSet<>();
        long lastEndOffsetMs = 0;
        for (TranscriptDocumentSegment segment : segments) {
            speakers.add(segment.sessionParticipantId());
            lastEndOffsetMs = Math.max(lastEndOffsetMs, segment.endOffsetMs());
        }
        return new AssembleTranscriptResult(segments.size(), speakers.size(), lastEndOffsetMs, filteredSegmentCount);
    }
}
