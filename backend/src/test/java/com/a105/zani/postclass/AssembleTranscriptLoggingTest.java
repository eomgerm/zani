package com.a105.zani.postclass;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptCommand;
import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptService;
import com.a105.zani.postclass.application.port.TranscriptFilterSettings;
import com.a105.zani.postclass.application.port.TranscriptPort;
import com.a105.zani.postclass.application.port.TranscriptSegment;
import com.a105.zani.postclass.application.port.TranscriptionChunk;
import com.a105.zani.postclass.application.port.TranscriptionTrack;
import com.a105.zani.postclass.domain.model.TranscriptDocument;
import com.a105.zani.postclass.domain.model.TranscriptionChunkStatus;
import com.a105.zani.recording.domain.model.TrackSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 필터 로그에 전사 원문이 섞이지 않는지 본다(S15P11A105-306).
 *
 * <p>일반적인 로깅 테스트가 아니다. 이 로그는 <b>왜 세그먼트가 빠졌는지</b>를 남기는 것이 목적이라 "무엇이 빠졌는지" 를 함께 적고 싶은 압력이 상시 있고, 한 번 텍스트를 넣으면 학생 발화가 운영
 * 로그와 로그 수집기로 흘러간다. 빠진 내용은 체크포인트에 그대로 남아 있으므로 로그에 둘 이유가 없다.
 *
 * <p>텍스트 부재만 보지 않고 <b>필요한 진단 정보가 있는지</b>도 함께 본다. 둘을 한 테스트에 두는 이유는 "텍스트를 지우려고 로그를 통째로 없앴다" 가 통과하지 못하게 하려는 것이다.
 */
class AssembleTranscriptLoggingTest {

    private static final Long SESSION_ID = 9_300_001L;
    private static final Long STUDENT = 9_300_102L;
    private static final Long STUDENT_FILE = 9_300_202L;
    private static final Instant NOW = Instant.parse("2026-08-05T02:00:00Z");

    /** 실제 세션의 발화 원문을 쓰지 않는다 — 이유는 {@link AssembleTranscriptServiceTest} 의 같은 상수에 적었다. */
    private static final String STUDENT_QUESTION = "적재율이 높아지면 조회 성능이 어떻게 달라지는지 다시 설명해 주실 수 있나요?";

    private static final String HALLUCINATION = "고맙습니다.";

    private final Logger assemblyLogger = (Logger) LoggerFactory.getLogger(AssembleTranscriptService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        assemblyLogger.addAppender(appender);
        assemblyLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachAppender() {
        assemblyLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void 필터_로그에_전사문을_남기지_않는다() {
        assemble(List.of(
                segment(15_000, 18_000, HALLUCINATION, 0.953),
                segment(45_000, 48_000, HALLUCINATION, 0.984),
                segment(165_000, 174_000, STUDENT_QUESTION, 0.176)));

        String logged = renderedLog();
        assertThat(logged).doesNotContain(HALLUCINATION);
        assertThat(logged).doesNotContain(STUDENT_QUESTION);
        // 실제 발화의 일부만 새어 나가는 것도 막는다.
        assertThat(logged).doesNotContain("적재율");
        assertThat(logged).doesNotContain("고맙");
    }

    @Test
    void 필터_로그에_추적에_필요한_수치는_남긴다() {
        assemble(List.of(
                segment(15_000, 18_000, HALLUCINATION, 0.953), segment(165_000, 174_000, STUDENT_QUESTION, 0.176)));

        String logged = renderedLog();
        assertThat(logged).contains("sessionId=" + SESSION_ID);
        assertThat(logged).contains("recordingFileId=" + STUDENT_FILE);
        assertThat(logged).contains("chunkIndex=0");
        assertThat(logged).contains("filteredSegmentCount=1");
        assertThat(logged).contains("threshold=0.8");
    }

    @Test
    void 전부_빠져_빈_전사가_되어도_전사문을_남기지_않는다() {
        // 빈 전사 경고는 "왜 리포트가 비었나" 에 답하는 로그라 임곗값과 건수를 적는다. 그 자리에 텍스트를
        // 넣고 싶은 압력이 가장 큰 곳이다.
        assemble(List.of(segment(15_000, 18_000, HALLUCINATION, 0.953), segment(45_000, 48_000, HALLUCINATION, 0.984)));

        String logged = renderedLog();
        assertThat(logged).doesNotContain(HALLUCINATION);
        assertThat(logged).contains("totalSegmentCount=2");
        assertThat(logged).contains("filteredSegmentCount=2");
    }

    private void assemble(List<TranscriptSegment> segments) {
        AssembleTranscriptService service = new AssembleTranscriptService(
                new DiscardingTranscriptPort(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TranscriptFilterSettings(true, 0.8));
        TranscriptionTrack track =
                new TranscriptionTrack(STUDENT_FILE, STUDENT, TrackSource.MICROPHONE, "TR_STUDENT", 0L);
        TranscriptionChunk chunk = new TranscriptionChunk(
                1L,
                SESSION_ID,
                STUDENT_FILE,
                0,
                0L,
                600_000L,
                TranscriptionChunkStatus.SUCCEEDED,
                1,
                null,
                null,
                segments);
        service.assemble(new AssembleTranscriptCommand(SESSION_ID, "ko", List.of(track), List.of(chunk)));
    }

    /** 서식 인자까지 채운 최종 문자열로 본다 — 텍스트가 인자로 들어가면 패턴만 보는 검사는 놓친다. */
    private String renderedLog() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static TranscriptSegment segment(long startMs, long endMs, String text, double noSpeechProb) {
        return new TranscriptSegment(startMs, endMs, text, -0.21, noSpeechProb);
    }

    private static final class DiscardingTranscriptPort implements TranscriptPort {

        @Override
        public void save(Long sessionId, TranscriptDocument document, Instant now) {
            /* 이 테스트는 로그만 본다. */
        }

        @Override
        public Optional<TranscriptDocument> findBySessionId(Long sessionId) {
            return Optional.empty();
        }
    }
}
