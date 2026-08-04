package com.a105.zani.recording.infrastructure.persistence.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.a105.zani.recording.application.getsessiontranscript.GetSessionTranscriptResult;
import com.a105.zani.recording.application.getsessiontranscript.TranscriptLine;
import com.a105.zani.recording.infrastructure.persistence.entity.TranscriptJpaEntity;
import com.a105.zani.recording.infrastructure.persistence.repository.TranscriptJpaRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

/** S15P11A105-247 이 확정한 transcript_document 계약(schemaVersion 1)을 읽어 내는지 고정한다. */
@ExtendWith(MockitoExtension.class)
class SessionTranscriptQueryAdapterTest {

    private static final Long SESSION_ID = 100L;

    @Mock
    private TranscriptJpaRepository transcriptJpaRepository;

    @InjectMocks
    private SessionTranscriptQueryAdapter adapter;

    private static Map<String, Object> segment(long startOffsetMs, long endOffsetMs, String text) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("sessionParticipantId", 1_000_000_003_001L);
        segment.put("source", "MICROPHONE");
        segment.put("startOffsetMs", startOffsetMs);
        segment.put("endOffsetMs", endOffsetMs);
        segment.put("text", text);
        segment.put("avgLogprob", -0.21);
        segment.put("confidence", 0.811);
        segment.put("noSpeechProb", 0.02);
        segment.put("recordingFileId", 1_000_000_010_001L);
        segment.put("chunkIndex", 0);
        return segment;
    }

    private void givenDocument(boolean partial, List<?> segments) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1);
        document.put("language", "ko");
        document.put("partial", partial);
        document.put("segments", segments);
        given(transcriptJpaRepository.findBySessionId(SESSION_ID))
                .willReturn(Optional.of(TranscriptJpaEntity.builder()
                        .sessionId(SESSION_ID)
                        .transcriptDocument(document)
                        .build()));
    }

    @Test
    void readsOffsetsAndTextFromTheStoredDocument() {
        givenDocument(false, List.of(segment(2_000, 32_000, "자, 오늘은 React 의 상태 관리를 다뤄보겠습니다.")));

        GetSessionTranscriptResult result = adapter.findBySessionId(SESSION_ID).orElseThrow();

        assertFalse(result.partial());
        assertEquals(1, result.lines().size());
        TranscriptLine line = result.lines().getFirst();
        assertEquals(2_000, line.startOffsetMs());
        assertEquals(32_000, line.endOffsetMs());
        assertEquals("자, 오늘은 React 의 상태 관리를 다뤄보겠습니다.", line.text());
    }

    /** 계약은 시간순을 보장하지만 구간 경계를 만드는 쪽이라 한 번 더 맞춘다 — 어긋난 문서 하나가 타임라인을 통째로 망가뜨린다. */
    @Test
    void ordersLinesByOffsetEvenWhenTheDocumentIsOutOfOrder() {
        givenDocument(false, List.of(segment(32_000, 60_000, "두 번째"), segment(2_000, 32_000, "첫 번째")));

        List<TranscriptLine> lines =
                adapter.findBySessionId(SESSION_ID).orElseThrow().lines();

        assertEquals("첫 번째", lines.getFirst().text());
        assertEquals("두 번째", lines.getLast().text());
    }

    /** 발화가 없으면 segments 는 빈 배열이다. 전사가 없는 것과 다른 상태라 결과 자체는 존재한다. */
    @Test
    void returnsAnEmptyLineListForASilentClass() {
        givenDocument(false, List.of());

        GetSessionTranscriptResult result = adapter.findBySessionId(SESSION_ID).orElseThrow();

        assertTrue(result.lines().isEmpty());
    }

    /** 미완결 전사를 그대로 알린다. 소비자가 완결 전 전사로 분석하지 않게 하려면 이 값이 필요하다. */
    @Test
    void reportsAPartialTranscript() {
        givenDocument(true, List.of(segment(0, 1_000, "일부만 전사됨")));

        assertTrue(adapter.findBySessionId(SESSION_ID).orElseThrow().partial());
    }

    /** 모양이 어긋난 세그먼트만 버리고 나머지는 살린다 — 하나 때문에 그 수업이 리포트를 못 받으면 안 된다. */
    @Test
    void skipsMalformedSegmentsAndKeepsTheRest() {
        Map<String, Object> missingText = segment(0, 1_000, "지워질 값");
        missingText.remove("text");
        Map<String, Object> reversedOffsets = segment(9_000, 8_000, "끝이 시작보다 앞선다");

        givenDocument(false, List.of(missingText, reversedOffsets, segment(2_000, 32_000, "살아남는 줄")));

        List<TranscriptLine> lines =
                adapter.findBySessionId(SESSION_ID).orElseThrow().lines();

        assertEquals(1, lines.size());
        assertEquals("살아남는 줄", lines.getFirst().text());
    }

    /** 전사가 아직 없는 세션은 빈 값이다. 전사 단계가 끝나지 않은 것과 무음 수업은 다르게 다뤄야 한다. */
    @Test
    void returnsEmptyWhenTheSessionHasNoTranscriptYet() {
        given(transcriptJpaRepository.findBySessionId(SESSION_ID)).willReturn(Optional.empty());

        assertTrue(adapter.findBySessionId(SESSION_ID).isEmpty());
    }
}
