package com.a105.zani.recording.infrastructure.persistence.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.a105.zani.recording.application.getsessiontranscript.GetSessionTranscriptQueryPort;
import com.a105.zani.recording.application.getsessiontranscript.GetSessionTranscriptResult;
import com.a105.zani.recording.application.getsessiontranscript.TranscriptLine;
import com.a105.zani.recording.infrastructure.persistence.repository.TranscriptJpaRepository;

/**
 * {@code transcripts.transcript_document} JSON 을 읽어 전사 줄 목록으로 바꾼다(S15P11A105-247 계약, schemaVersion 1).
 *
 * <p>문서 구조는 {@code {schemaVersion, language, partial, segments:[{startOffsetMs, endOffsetMs, text, ...}]}} 다. 여기서 쓰는 것은
 * {@code partial} 과 각 세그먼트의 오프셋·본문뿐이다. {@code confidence}·{@code recordingFileId}·{@code chunkIndex} 는 원본 추적용이라 분석 입력으로
 * 올리지 않는다 — 올려도 쓰는 곳이 없고, 소비자가 늘면 그때 넓힌다.
 *
 * <p><b>정렬을 다시 한다.</b> 계약은 시간순 평면 배열을 보장하지만, 이 조회의 소비자는 구간 경계를 만드는 쪽이라 순서가 어긋난 문서 하나가 타임라인을 통째로 망가뜨린다. 정렬은 세그먼트 수에 비해
 * 값싸고, 보장을 여기서 한 번 더 확인하는 편이 뒤에서 원인을 찾는 것보다 싸다.
 *
 * <p>모양이 어긋난 세그먼트는 건너뛰고 수를 남긴다. 문서 하나 때문에 전사 전체를 버리면 그 수업은 리포트를 받지 못한다.
 *
 * <p><b>읽을 수 없는 문서와 무음 수업을 구분한다.</b> 둘 다 "줄이 없다"로 돌려주면 소비자가 무음 수업으로 착각해 "발화 없음" 리포트를 만들고, 그 리포트는 세션당 1회 멱등에 걸려 다시 고쳐지지
 * 않는다. {@code segments} 가 빈 배열인 것만 무음이고, 키가 없거나 모든 세그먼트가 형식에 걸려 버려진 것은 읽지 못한 것이라 빈 값으로 돌려준다 — 소비자는 그것을 전사 미완료로 다룬다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionTranscriptQueryAdapter implements GetSessionTranscriptQueryPort {

    private static final String PARTIAL_KEY = "partial";
    private static final String SEGMENTS_KEY = "segments";
    private static final String START_OFFSET_KEY = "startOffsetMs";
    private static final String END_OFFSET_KEY = "endOffsetMs";
    private static final String TEXT_KEY = "text";

    private final TranscriptJpaRepository transcriptJpaRepository;

    @Override
    public Optional<GetSessionTranscriptResult> findBySessionId(Long sessionId) {
        return transcriptJpaRepository
                .findBySessionId(sessionId)
                .flatMap(entity -> readDocument(entity.getTranscriptDocument(), sessionId));
    }

    /** 읽지 못한 문서는 빈 값이다. 무음 수업({@code segments: []})은 줄이 없는 결과로 돌려준다. */
    private Optional<GetSessionTranscriptResult> readDocument(Map<String, Object> document, Long sessionId) {
        if (document == null || !(document.get(SEGMENTS_KEY) instanceof List<?> segments)) {
            log.error("전사 문서에 segments 가 없어 읽지 못했습니다. sessionId={}", sessionId);
            return Optional.empty();
        }

        List<TranscriptLine> lines = new ArrayList<>(segments.size());
        int skipped = 0;
        for (Object segment : segments) {
            TranscriptLine line = readLine(segment);
            if (line == null) {
                skipped++;
                continue;
            }
            lines.add(line);
        }
        if (skipped > 0) {
            log.warn("전사 세그먼트 {}건을 형식 불일치로 건너뜁니다. sessionId={}", skipped, sessionId);
        }
        if (lines.isEmpty() && !segments.isEmpty()) {
            // 세그먼트가 있는데 하나도 읽지 못했다. 무음 수업이 아니라 계약이 어긋난 문서다 —
            // 여기서 빈 목록으로 돌려주면 소비자가 "발화 없음" 리포트를 영구히 저장한다.
            log.error("전사 세그먼트 {}건을 모두 읽지 못했습니다. sessionId={}", segments.size(), sessionId);
            return Optional.empty();
        }
        lines.sort(
                Comparator.comparingLong(TranscriptLine::startOffsetMs).thenComparingLong(TranscriptLine::endOffsetMs));
        return Optional.of(
                new GetSessionTranscriptResult(Boolean.TRUE.equals(document.get(PARTIAL_KEY)), List.copyOf(lines)));
    }

    private TranscriptLine readLine(Object segment) {
        if (!(segment instanceof Map<?, ?> fields)) {
            return null;
        }
        Long startOffsetMs = readOffset(fields.get(START_OFFSET_KEY));
        Long endOffsetMs = readOffset(fields.get(END_OFFSET_KEY));
        if (startOffsetMs == null
                || endOffsetMs == null
                || startOffsetMs < 0
                || endOffsetMs < startOffsetMs
                || !(fields.get(TEXT_KEY) instanceof String text)
                || text.isBlank()) {
            return null;
        }
        return new TranscriptLine(startOffsetMs, endOffsetMs, text.strip());
    }

    /** JSON 숫자는 매핑에 따라 Integer·Long·BigDecimal 로 올 수 있어 Number 로 받는다. */
    private Long readOffset(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
