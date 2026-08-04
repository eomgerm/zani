package com.a105.zani.postclass.application.assembletranscript;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.postclass.application.exception.TranscriptAssemblyInvalidException;
import com.a105.zani.postclass.application.exception.TranscriptIncompleteException;
import com.a105.zani.postclass.application.exception.TranscriptNotReadyException;
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
     * 세그먼트 종료 시각이 청크 길이를 넘는 것을 허용하는 폭.
     *
     * <p>검사의 목적은 <b>분할이 잘못된 것</b>을 잡는 것이다. 그런 오류는 청크 하나 단위로 어긋나므로 분 단위로 벗어난다. 반면 GMS 가 마지막 문장의 끝을 반올림해 몇 ms 넘겨 주는 것은 정상
     * 범위이고, 그것까지 실패로 보면 멀쩡한 수업 전체가 저장되지 않는다.
     *
     * <p>그래서 두 경우를 가른다. 이 폭 안이면 청크 끝으로 맞추고 {@code WARN} 을 남긴다(조용히 넘기지 않는다). 넘으면 조립을 멈춘다.
     */
    private static final long CHUNK_OVERSHOOT_TOLERANCE_MS = 1_000L;

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
        for (TranscriptionChunk chunk : command.chunks()) {
            TranscriptionTrack track = tracks.get(chunk.recordingFileId());
            for (TranscriptSegment segment : chunk.segments()) {
                segments.add(toDocumentSegment(track, chunk, segment));
            }
        }
        segments.sort(TIMELINE_ORDER);

        TranscriptDocument document = TranscriptDocument.complete(command.language(), segments);
        transcriptPort.save(command.sessionId(), document, clock.instant());

        AssembleTranscriptResult result = summarize(segments);
        if (result.segmentCount() == 0) {
            // 실패가 아니다. 아무도 마이크를 열지 않은 수업이면 빈 전사가 사실이다. 다만 조용히 넘기면
            // "왜 리포트가 비었나" 에 답할 근거가 없어진다.
            log.warn("Assembled an empty transcript, no speech in any track: sessionId={}", command.sessionId());
        } else {
            log.info(
                    "Assembled transcript: sessionId={}, segments={}, speakers={}, lastEndOffsetMs={}",
                    command.sessionId(),
                    result.segmentCount(),
                    result.speakerCount(),
                    result.lastEndOffsetMs());
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

    /** 세 겹을 더해 수업 기준 절대 시각으로 옮긴다. */
    private TranscriptDocumentSegment toDocumentSegment(
            TranscriptionTrack track, TranscriptionChunk chunk, TranscriptSegment segment) {
        long base = track.startedOffsetMs() + chunk.sourceStartMs();
        long endMs = clampToChunk(chunk, segment);
        return new TranscriptDocumentSegment(
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
                chunk.chunkIndex());
    }

    /**
     * 세그먼트 종료 시각을 청크 경계에 맞춘다. 맞출 수 없으면 조립을 멈춘다.
     *
     * <p><b>시작 시각이 청크 밖인 것을 먼저 거른다.</b> 종료만 보고 줄이면 {@code start=600_500, end=600_000} 처럼 시작이 종료보다 뒤인 구간이 만들어진다. 그런 구간은
     * 어떤 소비자도 올바르게 해석할 수 없고, 시각이 뒤집혔다는 사실 자체가 조립이 아니라 분할 쪽 오류의 신호다. 줄여서 살릴 수 있는 것은 "청크 안에서 시작해 경계를 살짝 넘긴 문장" 뿐이다.
     */
    private long clampToChunk(TranscriptionChunk chunk, TranscriptSegment segment) {
        if (segment.startMs() >= chunk.durationMs()) {
            log.error(
                    "Segment starts outside its chunk, the split is wrong: recordingFileId={}, chunkIndex={},"
                            + " chunkDurationMs={}, segmentStartMs={}, segmentEndMs={}",
                    chunk.recordingFileId(),
                    chunk.chunkIndex(),
                    chunk.durationMs(),
                    segment.startMs(),
                    segment.endMs());
            throw new TranscriptAssemblyInvalidException();
        }
        long overshoot = segment.endMs() - chunk.durationMs();
        if (overshoot <= 0) {
            return segment.endMs();
        }
        if (overshoot > CHUNK_OVERSHOOT_TOLERANCE_MS) {
            log.error(
                    "Segment runs past its chunk, the split is wrong: recordingFileId={}, chunkIndex={},"
                            + " chunkDurationMs={}, segmentEndMs={}",
                    chunk.recordingFileId(),
                    chunk.chunkIndex(),
                    chunk.durationMs(),
                    segment.endMs());
            throw new TranscriptAssemblyInvalidException();
        }
        log.warn(
                "Trimming a segment end to the chunk boundary: recordingFileId={}, chunkIndex={}, overshootMs={}",
                chunk.recordingFileId(),
                chunk.chunkIndex(),
                overshoot);
        return chunk.durationMs();
    }

    private AssembleTranscriptResult summarize(List<TranscriptDocumentSegment> segments) {
        Set<Long> speakers = new HashSet<>();
        long lastEndOffsetMs = 0;
        for (TranscriptDocumentSegment segment : segments) {
            speakers.add(segment.sessionParticipantId());
            lastEndOffsetMs = Math.max(lastEndOffsetMs, segment.endOffsetMs());
        }
        return new AssembleTranscriptResult(segments.size(), speakers.size(), lastEndOffsetMs);
    }
}
