package com.a105.zani.postclass;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptCommand;
import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptResult;
import com.a105.zani.postclass.application.assembletranscript.AssembleTranscriptService;
import com.a105.zani.postclass.application.port.TranscriptFilterSettings;
import com.a105.zani.postclass.application.port.TranscriptPort;
import com.a105.zani.postclass.application.port.TranscriptSegment;
import com.a105.zani.postclass.application.port.TranscriptionChunk;
import com.a105.zani.postclass.application.port.TranscriptionTrack;
import com.a105.zani.postclass.domain.model.TranscriptDocument;
import com.a105.zani.postclass.domain.model.TranscriptionChunkStatus;
import com.a105.zani.recording.domain.model.TrackSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 실제 강의 전사 fixture 로 <b>오제거</b>를 측정한다(S15P11A105-306).
 *
 * <p>티켓의 완료 조건 "정상 강사 전사 fixture 에서 오제거 0건" 을 지키는 테스트다. 환각을 지우는지는 {@link AssembleTranscriptServiceTest} 가 보고, 여기서는 반대
 * 방향 — <b>지우면 안 되는 것을 지키는지</b>만 본다. 임곗값을 조정할 때 이 테스트가 먼저 깨져야 한다.
 *
 * <p><b>값의 출처.</b> GMS {@code whisper-1} 이 실제 강의 녹음 3건에 대해 돌려준 {@code verbose_json} 응답이다(2026-08-06 수집, 총 498초·82
 * 세그먼트). {@code no_speech_prob} 는 응답에 있던 값을 그대로 옮겼다.
 *
 * <p><b>왜 텍스트를 담지 않는가.</b> 전사 원문을 테스트 소스에 박으면 실제 강의 내용이 저장소에 들어간다. 여기서 재는 것은 "무음 확률과 시각만으로 실제 발화가 지워지는가" 이고, 그 판정에 텍스트는
 * 필요하지 않다. {@code avgLogprob} 도 같은 이유로 대표값 하나를 쓴다.
 *
 * <p><b>그래서 이 fixture 는 가드의 문장 종결 검사를 재지 않는다.</b> 대표 텍스트에 종결 부호가 없어 "문장이 이어진다" 로만 판정되므로, 아래 수치는 가드가 가장 관대하게 동작한 경우다. 종결
 * 검사는 실제 재현 응답을 그대로 옮긴 {@link AssembleTranscriptServiceTest} 쪽에서 본다.
 *
 * <p><b>이 fixture 가 보여 주는 것.</b> 세 강의의 창 최댓값은 {@code 0.515}·{@code 0.345}·{@code 0.515} 로 기본 임곗값 {@code 0.8} 에 한참 못
 * 미친다. 임곗값을 {@code 0.5} 로 낮추면 위험 세그먼트가 7개로 늘고 그중 5개가 실제로 사라진다(2개는 가드가 살린다) — Node.js 강의의 한 창에 들어 있던 연속 설명이다. 기본값
 * {@code 0.8} 을 고른 근거의 절반이 이것이고, 나머지 절반은 관측된 환각이 {@code 0.906} 부터 시작한다는 사실이다. 그 사이가 비어 있다.
 */
class AssembleTranscriptFilterCorpusTest {

    private static final Long SESSION_ID = 9_400_001L;
    private static final Long INSTRUCTOR = 9_400_101L;
    private static final Long INSTRUCTOR_FILE = 9_400_201L;
    private static final Instant NOW = Instant.parse("2026-08-06T02:00:00Z");
    private static final double DEFAULT_THRESHOLD = 0.8;

    /**
     * 실제 응답 3건. 한 행이 세그먼트 하나이고 {@code {startSec, endSec, noSpeechProb}} 다.
     *
     * <p>같은 {@code seek} 을 공유하는 세그먼트의 {@code no_speech_prob} 가 전부 동일한 것이 눈에 보이도록 창 단위로 묶어 두었다 — 이 값이 세그먼트 값이 아니라 <b>30초
     * 디코딩 창의 값</b>이라는 사실이 이번 필터 설계의 근거다.
     */
    private static final double[][] HASH_TABLE_LECTURE = {
        // seek=0 · nsp=0.20084
        {0, 9, 0.20084327459335327},
        {9, 19, 0.20084327459335327},
        {19, 23, 0.20084327459335327},
        {23, 28, 0.20084327459335327},
        // seek=2800 · nsp=0.08753
        {28, 33, 0.08752647042274475},
        {33, 36, 0.08752647042274475},
        {36, 40, 0.08752647042274475},
        {40, 45, 0.08752647042274475},
        {45, 53, 0.08752647042274475},
        // seek=5300 · nsp=0.00181
        {53, 59, 0.0018101247260347009},
        {59, 64, 0.0018101247260347009},
        {71, 76, 0.0018101247260347009},
        {76, 80, 0.0018101247260347009},
        // seek=8000 · nsp=0.02556
        {80, 83, 0.025559784844517708},
        {83, 85, 0.025559784844517708},
        {86, 90, 0.025559784844517708},
        {90, 94, 0.025559784844517708},
        {94, 96, 0.025559784844517708},
        {96, 100, 0.025559784844517708},
        {100, 105, 0.025559784844517708},
        {105, 108, 0.025559784844517708},
        // seek=10800 · nsp=0.01322
        {108, 110, 0.013221174478530884},
        {110, 114, 0.013221174478530884},
        {114, 118, 0.013221174478530884},
        {118, 122, 0.013221174478530884},
        {122, 126, 0.013221174478530884},
        {126, 134, 0.013221174478530884},
        {134, 137, 0.013221174478530884},
        // seek=13700 · nsp=0.51451 — 강사의 마무리 인사. 임곗값을 0.5 로 낮추면 이것이 사라진다.
        {137, 139, 0.5145065188407898},
    };

    private static final double[][] DIJKSTRA_LECTURE = {
        // seek=0 · nsp=0.03345
        {0, 15, 0.03344511240720749},
        {15, 27, 0.03344511240720749},
        // seek=2700 · nsp=0.03357
        {27, 35, 0.03357333689928055},
        {35, 41, 0.03357333689928055},
        {41, 47, 0.03357333689928055},
        {47, 55, 0.03357333689928055},
        // seek=5500 · nsp=0.01590
        {55, 62, 0.015901973471045494},
        {73, 78, 0.015901973471045494},
        {78, 81, 0.015901973471045494},
        // seek=8500 · nsp=0.01971
        {86, 92, 0.019714931026101112},
        {101, 104, 0.019714931026101112},
        {104, 109, 0.019714931026101112},
        // seek=10900 · nsp=0.21193
        {109, 119, 0.21193231642246246},
        {121, 125, 0.21193231642246246},
        // seek=12500 · nsp=0.07157
        {126, 134, 0.07156942784786224},
        // seek=13400 · nsp=0.34464
        {135, 141, 0.34463581442832947},
        // seek=14100 · nsp=0.25653
        {141, 147, 0.25653335452079773},
        {157, 162, 0.25653335452079773},
        // seek=17100 · nsp=0.24201
        {171, 174, 0.24201172590255737},
    };

    private static final double[][] NODE_LECTURE = {
        // seek=0 · nsp=0.01045
        {0, 3.5, 0.010445710271596909},
        {4.2, 11.4, 0.010445710271596909},
        {11.8, 19.2, 0.010445710271596909},
        {20, 28, 0.010445710271596909},
        // seek=2800 · nsp=0.07262
        {29, 34, 0.07261741161346436},
        {35, 38, 0.07261741161346436},
        {39, 44, 0.07261741161346436},
        {45, 49, 0.07261741161346436},
        {50, 53, 0.07261741161346436},
        // seek=5300 · nsp=0.03067
        {53, 57, 0.03066910430788994},
        {58, 64, 0.03066910430788994},
        {65, 69, 0.03066910430788994},
        {70, 73, 0.03066910430788994},
        {74, 77, 0.03066910430788994},
        // seek=7700 · nsp=0.51498 — 창 하나에 든 연속 설명 6개. 임곗값 0.5 면 전부 사라진다.
        {78, 81, 0.5149751901626587},
        {82, 86, 0.5149751901626587},
        {87, 90, 0.5149751901626587},
        {91, 93, 0.5149751901626587},
        {94, 99, 0.5149751901626587},
        {100, 103, 0.5149751901626587},
        // seek=10300 · nsp=0.05107
        {103, 105, 0.051073089241981506},
        {106, 111, 0.051073089241981506},
        {112, 117, 0.051073089241981506},
        {118, 121, 0.051073089241981506},
        {122, 128, 0.051073089241981506},
        {129, 132, 0.051073089241981506},
        // seek=13300 · nsp=0.03161
        {133, 136, 0.031612783670425415},
        {137, 141, 0.031612783670425415},
        {142, 145, 0.031612783670425415},
        {146, 149, 0.031612783670425415},
        {150, 153, 0.031612783670425415},
        {154, 157, 0.031612783670425415},
        // seek=15700 · nsp=0.05499
        {158, 164, 0.05499095097184181},
        {165, 167, 0.05499095097184181},
    };

    @Test
    void 해시_테이블_강의에서_오제거가_없다() {
        assertEquals(0, filteredCountOf(HASH_TABLE_LECTURE, DEFAULT_THRESHOLD));
        assertEquals(HASH_TABLE_LECTURE.length, keptCountOf(HASH_TABLE_LECTURE, DEFAULT_THRESHOLD));
    }

    @Test
    void 다익스트라_강의에서_오제거가_없다() {
        assertEquals(0, filteredCountOf(DIJKSTRA_LECTURE, DEFAULT_THRESHOLD));
        assertEquals(DIJKSTRA_LECTURE.length, keptCountOf(DIJKSTRA_LECTURE, DEFAULT_THRESHOLD));
    }

    @Test
    void node_강의에서_오제거가_없다() {
        assertEquals(0, filteredCountOf(NODE_LECTURE, DEFAULT_THRESHOLD));
        assertEquals(NODE_LECTURE.length, keptCountOf(NODE_LECTURE, DEFAULT_THRESHOLD));
    }

    @Test
    void 임곗값을_0_5_로_낮추면_실제_강의_발화가_사라진다() {
        // 기본값 0.8 을 고른 근거의 절반. 나머지 절반은 관측된 환각이 0.906 부터 시작한다는 사실이고,
        // 그 사이가 비어 있어 0.8 은 양쪽에 여유가 있다.
        //
        // 이 수치가 인접성 가드의 효과도 함께 보여 준다. 창 최댓값이 0.515 인 두 강의에서 위험 세그먼트는
        // 7개인데, 그중 2개는 가드가 살린다 — 해시 강의의 마무리 인사는 앞 세그먼트와 0ms 로 맞물려 있고,
        // Node 강의 창의 마지막 세그먼트는 뒤에 붙은 실제 발화가 앵커가 된다. 남은 5개는 앞뒤로 1초
        // 공백이라 앵커를 찾지 못한다. 가드가 있어도 0.5 는 실제 발화 5개를 잃는다.
        assertEquals(0, filteredCountOf(HASH_TABLE_LECTURE, 0.5), "마무리 인사는 앞 발화와 맞물려 가드가 살린다");
        assertEquals(0, filteredCountOf(DIJKSTRA_LECTURE, 0.5), "창 최댓값이 0.345 라 0.5 에서도 걸리지 않는다");
        assertEquals(5, filteredCountOf(NODE_LECTURE, 0.5), "한 창에 든 연속 설명 6개 중 5개가 사라진다");
    }

    /**
     * 마이크 오설정으로 오디오가 거의 들어가지 않은 세션. 같은 강의 대본인데 전사가 {@code " 이"} 98개 중 94개로 무너졌다(194.97초, 2026-08-06 실측).
     *
     * <p><b>이 규칙으로는 못 잡는다.</b> 소리가 아주 작게라도 들어가 있어 창 확률이 0.088~0.879 로 흩어지고, 임곗값 0.8 을 넘는 창은 둘뿐이다. 36개가 빠지고 62개가 남는데 남는
     * 것도 전부 {@code " 이"} 다 — 즉 정제가 아니라 무너진 전사를 조금 얇게 만드는 것에 그친다.
     *
     * <p>이것을 테스트로 남기는 이유는 <b>필터가 이 경우를 처리한다고 오해하지 않게</b> 하려는 것이다. 무음 환각과 <b>붕괴된 디코딩</b>은 다른 고장이고, 잡을 신호도 다르다 — 이 응답은
     * {@code compression_ratio} 가 최대 6.07(정상 강의는 0.64~1.74)이고 {@code temperature} 가 8개 창 중 6개에서 1.0 까지 올라갔다(정상 강의는 전부
     * 0). 둘 다 GMS 응답에 있지만 지금 어댑터가 버리는 값이다.
     */
    @Test
    void 마이크_오설정으로_붕괴된_전사는_이_규칙으로_잡히지_않는다() {
        double[][] brokenMicrophone = brokenMicrophoneSession();

        AssembleTranscriptResult result = assemble(brokenMicrophone, DEFAULT_THRESHOLD);

        assertEquals(98, brokenMicrophone.length);
        assertEquals(36, result.filteredSegmentCount(), "임곗값을 넘는 창이 둘뿐이다");
        assertEquals(62, result.segmentCount(), "남은 것도 전부 무의미한 조각이다");
    }

    /**
     * 창 단위로 묶은 실측 응답. 한 행이 {@code {startSec, endSec, noSpeechProb}} 다.
     *
     * <p><b>시각은 실측 그대로여야 한다.</b> 처음에는 창별 시작점과 개수만 주고 1초 간격으로 생성했는데 결과가 39건으로 나왔다(실측은 36건). 붕괴된 전사에서도 창 경계에서 정확히 맞물린
     * 세그먼트가 셋 있고 가드가 그것을 살리는데, 합성한 시각으로는 그 맞물림이 사라진다.
     */
    private static double[][] brokenMicrophoneSession() {
        List<double[]> rows = new ArrayList<>();
        double window0 = 0.6099101901054382;
        double window6000 = 0.8787069320678711;
        double window8900 = 0.5712301731109619;
        double window9900 = 0.08818339556455612;
        double window11100 = 0.2524105906486511;
        double window14000 = 0.8515068888664246;
        double window16900 = 0.513681173324585;

        rows.add(new double[] {0, 1, window0});
        // 이 창의 첫 세그먼트만 2초이고 나머지는 1초 간격으로 89초까지 이어진다.
        rows.add(new double[] {60, 62, window6000});
        appendSecondly(rows, 62, 27, window6000);
        appendSecondly(rows, 89, 10, window8900);
        appendSecondly(rows, 99, 12, window9900);
        // 이 창부터 간격이 불규칙해진다.
        appendSpans(rows, window11100, new double[][] {
            {112, 113}, {113, 114}, {114, 115}, {115, 116}, {117, 118}, {118, 119},
            {120, 121}, {121, 122}, {123, 124}, {124, 125}, {125, 126}, {126, 127},
            {127, 128}, {128, 129}, {129, 130}, {130, 131}, {131, 132}, {133, 134},
            {134, 135}, {135, 136}, {136, 137}, {137, 138}, {139, 140}
        });
        appendSpans(rows, window14000, new double[][] {
            {140, 142},
            {142, 144},
            {149, 150},
            {155, 156},
            {157, 158},
            {161, 162},
            {162, 163},
            {163, 164},
            {164, 165},
            {167, 168},
            {168, 169}
        });
        appendSpans(rows, window16900, new double[][] {
            {169, 170}, {170, 171}, {171, 172}, {172, 173}, {174, 175}, {176, 177},
            {177, 178}, {183, 184}, {189, 190}, {191, 192}, {192, 193}, {194, 195},
            {196, 197}
        });
        return rows.toArray(double[][]::new);
    }

    private static void appendSecondly(List<double[]> rows, double startSec, int count, double noSpeechProb) {
        for (int index = 0; index < count; index++) {
            rows.add(new double[] {startSec + index, startSec + index + 1, noSpeechProb});
        }
    }

    private static void appendSpans(List<double[]> rows, double noSpeechProb, double[][] spans) {
        for (double[] span : spans) {
            rows.add(new double[] {span[0], span[1], noSpeechProb});
        }
    }

    @Test
    void 관측된_환각_구간은_같은_임곗값에서_제거된다() {
        // 같은 임곗값이 양방향으로 맞는지 본다. 위 세 강의에서는 아무것도 지우지 않고, 실측 환각
        // 구간(0.906~0.985)에서는 전부 지운다. 한쪽만 보면 "임곗값을 1.0 으로 두면 오제거가 0" 같은
        // 무의미한 통과가 가능하다.
        double[][] hallucinated = {
            {15, 18, 0.953},
            {45, 48, 0.984},
            {75, 78, 0.906},
            {105, 108, 0.924},
        };

        assertEquals(4, filteredCountOf(hallucinated, DEFAULT_THRESHOLD));
        assertEquals(0, keptCountOf(hallucinated, DEFAULT_THRESHOLD));
    }

    private int filteredCountOf(double[][] fixture, double threshold) {
        return assemble(fixture, threshold).filteredSegmentCount();
    }

    private int keptCountOf(double[][] fixture, double threshold) {
        return assemble(fixture, threshold).segmentCount();
    }

    private AssembleTranscriptResult assemble(double[][] fixture, double threshold) {
        List<TranscriptSegment> segments = new ArrayList<>(fixture.length);
        for (double[] row : fixture) {
            segments.add(
                    new TranscriptSegment(Math.round(row[0] * 1_000), Math.round(row[1] * 1_000), "발화", -0.21, row[2]));
        }
        TranscriptionChunk chunk = new TranscriptionChunk(
                1L,
                SESSION_ID,
                INSTRUCTOR_FILE,
                0,
                0L,
                600_000L,
                TranscriptionChunkStatus.SUCCEEDED,
                1,
                null,
                null,
                segments);
        TranscriptionTrack track =
                new TranscriptionTrack(INSTRUCTOR_FILE, INSTRUCTOR, TrackSource.MICROPHONE, "TR_INSTRUCTOR", 0L);
        AssembleTranscriptService service = new AssembleTranscriptService(
                new DiscardingTranscriptPort(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TranscriptFilterSettings(true, threshold, false));
        return service.assemble(new AssembleTranscriptCommand(SESSION_ID, "ko", List.of(track), List.of(chunk)));
    }

    private static final class DiscardingTranscriptPort implements TranscriptPort {

        @Override
        public void save(Long sessionId, TranscriptDocument document, Instant now) {
            /* 이 테스트는 결과 수치만 본다. */
        }

        @Override
        public Optional<TranscriptDocument> findBySessionId(Long sessionId) {
            return Optional.empty();
        }
    }
}
