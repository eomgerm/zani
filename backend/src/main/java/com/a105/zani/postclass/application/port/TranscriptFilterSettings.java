package com.a105.zani.postclass.application.port;

/**
 * 최종 전사를 조립할 때 세그먼트를 뺄지 판정하는 규칙(S15P11A105-306).
 *
 * <p><b>왜 조립 단계인가.</b> GMS 응답과 {@code postclass_transcription_chunks.result_document} 는 그대로 보존하고, 사용자에게 내보내는
 * {@code transcripts.transcript_document} 를 만들 때만 뺀다. 그래야 임곗값을 바꾸거나 판정 규칙을 고칠 때 GMS 를 다시 부르지 않고 재조립만 할 수 있다 — 체크포인트가
 * {@code noSpeechProb} 까지 보존하고 있어서 가능한 일이다.
 *
 * <p><b>왜 읽기 시점이 아닌가.</b> {@code transcript_document} 를 읽는 곳은 두 군데인데 둘 다 저장된 문서를 그대로 읽는다 — 공통 분석(248)과 학생 리포트 타임라인(SQL
 * {@code JSON_TABLE}). 조립 시 걸러 두면 두 소비자가 같은 정제 결과를 보는 것이 코드 변경 없이 성립한다. 읽는 쪽마다 필터를 두면 규칙이 갈리는 날 같은 전사에서 다른 타임라인이 나온다.
 *
 * <p>값의 출처는 외부 설정이지만 application 계층이 스프링 설정 타입을 알 필요는 없다. infrastructure 가 설정을 읽어 이 타입으로 바꿔 주입한다 —
 * {@link PostClassTranscriptionSettings} 와 같은 방식이다.
 *
 * @param silenceHallucinationFilterEnabled 무음 환각 필터를 적용할지. 껐을 때는 필터 이전 동작과 완전히 같아야 한다
 * @param noSpeechThreshold 이 값 <b>이상</b> 인 {@code noSpeechProb} 세그먼트를 뺀다. {@code 0.0}~{@code 1.0}
 * @param repeatedPhraseFilterEnabled 반복 문구 환각 필터를 적용할지(S15P11A105-316). 무음 확률만으로는 잡히지 않는 반복을 다룬다
 */
public record TranscriptFilterSettings(
        boolean silenceHallucinationFilterEnabled, double noSpeechThreshold, boolean repeatedPhraseFilterEnabled) {

    public TranscriptFilterSettings {
        // 확률과 비교하는 값이므로 0~1 을 벗어나면 뜻이 없다. 꺼져 있어도 검사한다 — 켜는 순간이 아니라
        // 넣는 순간에 걸려야 원인을 아는 사람이 고칠 수 있다.
        if (!Double.isFinite(noSpeechThreshold) || noSpeechThreshold < 0.0 || noSpeechThreshold > 1.0) {
            throw new IllegalArgumentException("무음 확률 임곗값은 0.0 이상 1.0 이하여야 합니다: " + noSpeechThreshold);
        }
    }

    /** 두 필터를 모두 끈 설정. 임곗값은 쓰이지 않지만 유효 범위 안의 값을 둔다. */
    public static TranscriptFilterSettings disabled() {
        return new TranscriptFilterSettings(false, 1.0, false);
    }

    /**
     * 붙어 있다고 볼 최대 공백.
     *
     * <p>좁게 둔다. 실측에서 한 문장이 창 경계로 쪼개졌을 때 두 조각의 시각은 <b>정확히 0ms</b> 로 맞물렸다({@code 217.28s} 에서 앞 조각이 끝나고 뒷 조각이 시작). 넓게 두면
     * 진짜 발화가 끝난 0.5초 뒤에 시작한 환각까지 살려 준다. GMS 타임스탬프 해상도가 파일마다 다르므로(정수 초 격자인 응답도 있었다) 0 으로 못 박지 않고 한 격자만큼의 여유를 둔다.
     */
    private static final long ADJACENCY_TOLERANCE_MS = 200L;

    /**
     * 무음 확률만으로 이 세그먼트를 뺄 대상인지.
     *
     * <p><b>경계는 포함이다</b>({@code >=}). 임곗값과 정확히 같은 값을 살리면 "0.8 로 두었는데 0.8 이 남는다" 가 되어 설정의 뜻이 흐려진다.
     *
     * <p>판정은 {@code noSpeechProb} 하나만 본다. {@code avgLogprob} 이 낮다는 것은 모델이 그 문장에 확신이 없었다는 뜻일 뿐 무음이라는 뜻이 아니고, 실제로 낮은 신뢰도의
     * 정상 발화가 흔하다 — 두 값을 섞으면 멀쩡한 발화를 잃는다.
     *
     * <p>이 판정만으로 빼면 실제 발화를 잃는 경우가 있다. {@link #anchors(double)} 와 {@link #adjacent(long, long)} 를 함께 봐야 한다.
     */
    public boolean exceedsNoSpeechThreshold(double noSpeechProb) {
        return silenceHallucinationFilterEnabled && noSpeechProb >= noSpeechThreshold;
    }

    /**
     * 이 세그먼트가 <b>실제 발화의 앵커</b>인지 — 무음 확률이 임곗값 미만인가.
     *
     * <p>앵커를 "임곗값 미만" 으로 못 박는 이유는 사슬을 막기 위해서다. "이웃이 남았으면 살린다" 로 두면 환각 두 개가 서로를 붙잡아 둘 다 살아남는다.
     */
    public boolean anchors(double noSpeechProb) {
        return noSpeechProb < noSpeechThreshold;
    }

    /**
     * 두 시각이 맞물려 있는지. 한쪽의 끝과 다른 쪽의 시작을 넣는다.
     *
     * <p><b>왜 이 검사가 필요한가.</b> {@code no_speech_prob} 는 세그먼트 값이 아니라 <b>30초 디코딩 창의 값</b>이다 — 실측에서 같은 {@code seek} 을 공유하는
     * 세그먼트 6개가 모두 {@code 0.008} 로 동일했다. 그래서 한 문장이 창 경계를 넘으면 뒷조각이 "거의 무음인 다음 창" 에 떨어지고, 그 창의 값이 조각에 그대로 복사된다.
     *
     * <p>실측 사례(강사 녹음 4.6분):
     *
     * <pre>
     * seek=19600  212.60~217.28  nsp=0.067  "...가장 가까운 정점을 선택하고 간선완화연산"
     * seek=21728  217.28~218.94  nsp=0.964  "을 반복하여 최단거리를 구합니다."        &lt;- 한 문장의 뒷부분
     * seek=24728  247.28~275.80  nsp=0.907  "학생이 자네에 피로가 났대요."            &lt;- 28.3초 공백 뒤의 환각
     * </pre>
     *
     * <p>둘 다 임곗값을 넘지만 하나는 진짜 발화다. 무음 확률로는 가를 수 없고 — 0.964 라서 임곗값을 올려도 못 살린다 — 가르는 신호는 <b>앞 조각과 시각이 맞물려 있다</b> 는 사실이다.
     * 진짜 무음 환각은 앞뒤가 공백이다.
     */
    public boolean adjacent(long earlierEndMs, long laterStartMs) {
        return laterStartMs - earlierEndMs <= ADJACENCY_TOLERANCE_MS;
    }

    /**
     * 앞 세그먼트가 문장을 끝내지 않았는지 — 즉 경계를 넘어 <b>같은 문장이 이어지는가</b>.
     *
     * <p><b>왜 시각만으로는 부족한가.</b> 실측에서 두 경우가 시각으로는 구별되지 않았다. 둘 다 무음 확률이 높은 세그먼트가 낮은 세그먼트의 끝에서 {@code 0ms} 로 이어진다.
     *
     * <pre>
     * 진짜 연속  212.60~217.28 "...가장 가까운 정점을 선택하고 간선완화연산"  -> 217.28~218.94 nsp=0.964
     * 환각      165.0 ~174.0  "...그러면 조회수가 느려지지 않나요?"          -> 174.0 ~179.5  nsp=0.906
     * </pre>
     *
     * <p>가르는 신호는 앞 세그먼트가 <b>문장을 끝냈는지</b>다. 위쪽은 종결 부호가 없어 문장이 끊긴 채 다음 창으로 넘어갔고, 아래쪽은 물음표로 끝나 이어질 문장이 없다. 그 뒤에 붙은
     * {@code "고맙습니다."} 는 30초 간격으로 반복된 무음 환각 13건 중 하나였다.
     *
     * <p><b>쉼표는 종결로 보지 않는다.</b> {@code "...연산," } 로 끝나면 문장은 이어진다. 종결로 취급하면 진짜 연속을 잃는다.
     *
     * <p>텍스트를 읽지만 <b>내용을 판정하지는 않는다</b> — 마지막 글자 하나만 본다. 특정 문구를 지목하는 규칙(S15P11A105-316)과는 성질이 다르다.
     */
    public boolean sentenceContinues(String earlierText) {
        if (earlierText == null) {
            return false;
        }
        String trimmed = earlierText.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        return SENTENCE_TERMINATORS.indexOf(trimmed.charAt(trimmed.length() - 1)) < 0;
    }

    /**
     * 문장을 끝내는 부호.
     *
     * <p>전각 형태를 함께 담는다 — 한국어 전사에 섞여 나오고, 빠뜨리면 문장이 끝났는데도 이어지는 것으로 보아 환각을 살린다. 쉼표·가운뎃점 같은 연결 부호는 담지 않는다.
     */
    private static final String SENTENCE_TERMINATORS = ".?!…。．？！";

    // ---------------------------------------------------------------------
    // 반복 문구 환각(S15P11A105-316)
    //
    // 무음 확률이 낮게 나온 환각은 위 규칙으로 잡히지 않는다. no_speech_prob 는 30초 디코딩 창의
    // 값이라, 실제 발화와 같은 창에 떨어진 환각은 발화와 값이 똑같아진다 — 실측에서 학생 질문과
    // 환각 5건이 모두 0.1109 였다. whisper 가 주는 품질 지표는 전부 창 단위라 어떤 조합으로도
    // 그 둘을 가를 수 없다. 남는 신호는 텍스트 자체다.
    // ---------------------------------------------------------------------

    /**
     * 반복으로 볼 최소 횟수.
     *
     * <p>둘로 두면 안 된다. 강사가 같은 말을 두 번 하는 것은 흔하고, 그것까지 지우면 정상 설명을 잃는다. 실측된 환각은 8회·13회처럼 훨씬 길게 반복됐다.
     */
    private static final int MIN_REPEAT_RUN = 3;

    /**
     * 반복 판정 대상이 될 최대 길이(정규화 후).
     *
     * <p>긴 문장은 반복돼도 건드리지 않는다. 실측 반례가 있다 — 강사가 같은 52자 문장을 3회 연속 읽은 세션이 있었고, 그것은 실제 발화였다. 반면 환각 문구는 짧다:
     * {@code "고맙습니다."}(6자), {@code "지금까지 재택 플러스였습니다."}(17자), {@code "시청해 주셔서 감사합니다"}(12자).
     */
    private static final int MAX_REPEAT_LENGTH = 20;

    /**
     * 반복 구간이 무음 위에서 만들어졌다고 볼 무음 확률.
     *
     * <p>{@link #noSpeechThreshold} 보다 낮게 둔다. 이 값은 <b>단독으로 세그먼트를 지우는 기준이 아니라</b> 이미 반복으로 걸린 구간의 성격을 가르는 보조 신호다. 실측 반복
     * 8건의 무음 확률은 {@code 0.62·0.99·0.99·0.98·0.79·0.79·0.79·0.11} 로 7개가 이 값을 넘었다. 반대로 강사가 숨 쉬듯 이어서 반복한 실제 발화는 그 구간에 소리가
     * 있으므로 낮게 나온다.
     */
    private static final double REPEAT_SILENCE_HINT = 0.5;

    /**
     * 반복 판정에 쓸 정규화. 공백과 종결 부호 차이로 같은 문구가 다르게 세지지 않게 한다.
     *
     * <p>내용을 해석하지 않는다 — 유니코드 정규화, 공백 축소, 끝 구두점 제거뿐이다.
     */
    public String normalizeForRepeat(String text) {
        if (text == null) {
            return "";
        }
        String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
                .strip()
                .replaceAll("\\s+", " ");
        int end = normalized.length();
        while (end > 0 && REPEAT_TRIMMED_MARKS.indexOf(normalized.charAt(end - 1)) >= 0) {
            end--;
        }
        return normalized.substring(0, end).strip();
    }

    /** 끝에서 떼어 낼 부호. 종결 부호에 쉼표·가운뎃점을 더한다 — 같은 문구가 부호 차이로 갈리면 안 된다. */
    private static final String REPEAT_TRIMMED_MARKS = SENTENCE_TERMINATORS + ",·、，";

    /**
     * 이 길이의 반복이 환각 후보인지.
     *
     * <p>짧은 문장이 최소 횟수 이상 <b>연속으로</b> 나온 경우만 본다. 사이에 다른 발화가 끼면 반복으로 보지 않는다 — 실측에서 강사가 대본을 두 번 낭독했을 때 {@code "안녕하세요"} 가 두
     * 번 나왔지만 사이에 다른 문장이 있어 연속이 아니었다. 그 재낭독을 지우지 않는 것이 이 조건이다.
     */
    public boolean isRepeatCandidate(String normalizedText, int runLength) {
        return repeatedPhraseFilterEnabled
                && runLength >= MIN_REPEAT_RUN
                && !normalizedText.isEmpty()
                && normalizedText.length() <= MAX_REPEAT_LENGTH;
    }

    /**
     * 반복 전체를 뺄지, 첫 하나만 남길지.
     *
     * <p>반복 중 <b>과반</b>이 무음 위에서 나왔으면 그 구간에 실제 발화가 없었다고 보고 전부 뺀다. 그렇지 않으면 강사가 실제로 반복해 말했을 수 있으므로 첫 하나를 남긴다 — 지우는 쪽으로 기울되
     * 근거가 없으면 흔적을 남긴다.
     */
    public boolean dropsWholeRun(int runLength, int silentCount) {
        return silentCount * 2 > runLength;
    }

    /** 이 세그먼트가 무음 위에서 만들어졌다는 신호인지. 반복 구간의 성격을 가를 때만 쓴다. */
    public boolean looksSilent(double noSpeechProb) {
        return noSpeechProb >= REPEAT_SILENCE_HINT;
    }
}
