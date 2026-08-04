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
        Set<String> seenChunks = new HashSet<>();
        List<TranscriptDocumentSegment> segments = new ArrayList<>();

        for (TranscriptionChunk chunk : command.chunks()) {
            requireSameSession(command.sessionId(), chunk);
            requireFinishedAndUsable(chunk);
            requireDistinct(seenChunks, chunk);
            TranscriptionTrack track = requireTrack(tracks, chunk);
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

    /** 다른 세션의 청크가 섞이면 그 발화가 남의 수업 리포트에 실린다. 조회 실수를 여기서 끊는다. */
    private void requireSameSession(Long sessionId, TranscriptionChunk chunk) {
        if (!sessionId.equals(chunk.sessionId())) {
            log.error(
                    "Chunk belongs to another session: expected={}, actual={}, chunkId={}",
                    sessionId,
                    chunk.sessionId(),
                    chunk.id());
            throw new TranscriptAssemblyInvalidException();
        }
    }

    /**
     * 끝나지 않았거나 결과를 실을 수 없는 청크를 거른다.
     *
     * <p><b>두 경우를 다른 예외로 올린다.</b> 지금 {@code ANALYZING} 으로 넘기지 않는 것은 같지만 이후가 다르다 — 아직 처리 중인 것은 기다리면 끝나고, 영구 실패한 것은 기다려도
     * 달라지지 않는다. 합치면 오케스트레이션이 {@code retryable} 을 정할 근거를 잃고, 영구 실패를 상한까지 재시도하거나 처리 중인 세션을 너무 일찍 최종 실패로 굳힌다.
     */
    private void requireFinishedAndUsable(TranscriptionChunk chunk) {
        if (!chunk.status().isTerminal()) {
            // 데이터는 멀쩡하므로 재시도 가능이지만, 종결을 기다리지 않고 조립을 부른 것 자체가
            // 오케스트레이션 버그일 수 있어 ERROR 로 남긴다.
            log.error(
                    "Assembly called before every chunk finished: chunkId={}, recordingFileId={}, chunkIndex={},"
                            + " status={}",
                    chunk.id(),
                    chunk.recordingFileId(),
                    chunk.chunkIndex(),
                    chunk.status());
            throw new TranscriptNotReadyException();
        }
        if (!chunk.status().contributesToTranscript()) {
            log.error(
                    "Refusing to store an incomplete transcript, chunk permanently failed: chunkId={},"
                            + " recordingFileId={}, chunkIndex={}, attemptCount={}",
                    chunk.id(),
                    chunk.recordingFileId(),
                    chunk.chunkIndex(),
                    chunk.attemptCount());
            throw new TranscriptIncompleteException();
        }
    }

    /** 같은 {@code (recordingFileId, chunkIndex)} 가 두 번 오면 그 구간의 발화가 문서에 두 번 실린다. */
    private void requireDistinct(Set<String> seen, TranscriptionChunk chunk) {
        if (!seen.add(chunk.recordingFileId() + ":" + chunk.chunkIndex())) {
            log.error(
                    "Duplicate chunk in the assembly input: recordingFileId={}, chunkIndex={}",
                    chunk.recordingFileId(),
                    chunk.chunkIndex());
            throw new TranscriptAssemblyInvalidException();
        }
    }

    private TranscriptionTrack requireTrack(Map<Long, TranscriptionTrack> tracks, TranscriptionChunk chunk) {
        TranscriptionTrack track = tracks.get(chunk.recordingFileId());
        if (track == null) {
            log.error(
                    "No track for a chunk, cannot resolve its speaker: chunkId={}, recordingFileId={}",
                    chunk.id(),
                    chunk.recordingFileId());
            throw new TranscriptAssemblyInvalidException();
        }
        return track;
    }

    /** 세 겹을 더해 수업 기준 절대 시각으로 옮긴다. */
    private TranscriptDocumentSegment toDocumentSegment(
            TranscriptionTrack track, TranscriptionChunk chunk, TranscriptSegment segment) {
        long base = track.startedOffsetMs() + chunk.sourceStartMs();
        long endMs = clampToChunk(chunk, segment);
        return new TranscriptDocumentSegment(
                track.sessionParticipantId(),
                track.trackSource(),
                base + segment.startMs(),
                base + endMs,
                segment.text(),
                segment.avgLogprob(),
                segment.confidenceScore(),
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
