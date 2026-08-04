package com.a105.zani.postclass.domain.model;

import com.a105.zani.recording.domain.model.TrackSource;

/**
 * 최종 전사 문서의 세그먼트 하나(S15P11A105-247).
 *
 * <p><b>화자는 실명이 아니라 {@code sessionParticipantId} 다.</b> 이름을 넣으면 JSON 안에 개인정보가 굳고, 표시 이름이 바뀌면 옛 전사와 새 전사의 같은 사람이 다르게
 * 보인다. 실명 해석은 화면이 참여자 행을 조회해서 한다. GMS 로 나갈 때는 {@code RecordingAlias} 로 치환하는 규칙이 101 에 이미 있다.
 *
 * <p><b>{@code avgLogprob} 과 {@code confidence} 를 함께 남긴다.</b> {@code confidence = exp(avgLogprob)} 이라 하나만 있어도 계산은 되지만,
 * 식을 나중에 바꿀 때 옛 값과 새 값을 구분할 수 없다. 원값을 보존해 두면 다시 계산할 수 있고, {@link #confidenceMethod} 가 어느 식으로 만든 값인지 알려 준다.
 *
 * <p><b>추적 좌표를 둘 둔다.</b> {@code recordingFileId} 는 DB·체크포인트로 가는 길이고({@code (recordingFileId, chunkIndex)} 가 체크포인트의 고유
 * 기준이다), {@code trackSid} 는 LiveKit 쪽으로 가는 길이다. 재접속·재발행은 같은 참가자라도 다른 Track SID 를 만들므로, 어느 발행 구간의 발화인지는 이 값으로만 가른다.
 *
 * @param sessionParticipantId 발화자 세션 참여자 id
 * @param source 트랙 종류. MVP 전사 대상은 {@code MICROPHONE} 뿐이지만 값을 남겨 대상 확대 시 구분할 수 있게 한다
 * @param trackSid LiveKit Track SID. <b>{@code null} 일 수 있다</b> — 한 Egress 가 파일을 여러 개 남기면 첫 행만 이 값을
 *     갖는다({@code UK(recording_id, livekit_track_sid)} 때문에 나머지는 {@code null} 로 저장된다). 없다고 전사를 막지 않는다: 그것은 정상적인 저장 형태이고,
 *     막으면 멀쩡한 트랙이 전사되지 않는다
 * @param startOffsetMs 수업 타임라인 기준 시작 시각
 * @param endOffsetMs 수업 타임라인 기준 종료 시각
 * @param text 발화 텍스트
 * @param avgLogprob GMS 가 준 토큰당 평균 로그 확률 원값. <b>0~1 신뢰도가 아니다</b>
 * @param confidence {@link #confidenceMethod} 로 만든 파생값. 보정된 정답 확률이 아니라 휴리스틱이다
 * @param confidenceMethod {@code confidence} 를 만든 식. 소비자가 이 값을 보고 해석 방법을 정한다
 * @param noSpeechProb 무음 확률
 * @param recordingFileId 이 문장이 나온 원본 트랙 파일
 * @param chunkIndex 그 파일 안에서의 청크 순번
 */
public record TranscriptDocumentSegment(
        long sessionParticipantId,
        TrackSource source,
        String trackSid,
        long startOffsetMs,
        long endOffsetMs,
        String text,
        double avgLogprob,
        double confidence,
        ConfidenceMethod confidenceMethod,
        double noSpeechProb,
        long recordingFileId,
        int chunkIndex) {

    /**
     * 이 문서에 실릴 수 없는 값을 여기서 막는다.
     *
     * <p>검증을 조립 쪽에만 두지 않는 이유는 이 record 가 <b>쓰기와 읽기의 공통 관문</b>이기 때문이다. 조립이 실수해도, 옛 판의 문서를 읽어도, 손으로 넣은 시드가 틀려도 같은 자리에서
     * 걸린다. 특히 {@code endOffsetMs >= startOffsetMs} 가 중요하다 — 종료를 청크 경계로 줄이는 로직이 시작 시각을 함께 보지 않으면 시각이 뒤집힌 구간이 만들어지고, 그런
     * 구간은 어떤 소비자도 올바르게 해석할 수 없다.
     *
     * <p>확률·로그확률의 유한성도 본다. {@code NaN} 이나 {@code Infinity} 는 JSON 표준 값이 아니라 직렬화 단계에서 깨지거나 표준을 벗어난 문서를 만든다. 비교·평균 같은 하류
     * 계산도 조용히 오염된다.
     *
     * <p>{@code trackSid} 는 검사하지 않는다({@code null} 허용). 이유는 필드 설명에 적었다.
     */
    public TranscriptDocumentSegment {
        if (sessionParticipantId <= 0 || recordingFileId <= 0 || chunkIndex < 0) {
            throw new IllegalArgumentException("세그먼트 식별자가 올바르지 않습니다: participant=" + sessionParticipantId + ", file="
                    + recordingFileId + ", chunkIndex=" + chunkIndex);
        }
        if (source == null) {
            throw new IllegalArgumentException("세그먼트 트랙 종류가 없습니다");
        }
        if (startOffsetMs < 0 || endOffsetMs < startOffsetMs) {
            throw new IllegalArgumentException("세그먼트 구간이 올바르지 않습니다: " + startOffsetMs + "~" + endOffsetMs);
        }
        if (text == null) {
            throw new IllegalArgumentException("세그먼트 텍스트가 없습니다");
        }
        // avgLogprob 은 로그 확률이라 0 을 넘을 수 없다. 넘었다면 GMS 응답을 잘못 읽은 것이다.
        if (!Double.isFinite(avgLogprob) || avgLogprob > 0) {
            throw new IllegalArgumentException("세그먼트 평균 로그확률이 올바르지 않습니다: " + avgLogprob);
        }
        // exp(avgLogprob) 이므로 (0, 1] 이 정상이다. 크게 음수인 로그확률은 0.0 으로 언더플로하므로 0 을 허용한다.
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("세그먼트 신뢰도가 올바르지 않습니다: " + confidence);
        }
        if (confidenceMethod == null) {
            throw new IllegalArgumentException("세그먼트 신뢰도 계산 방법이 없습니다");
        }
        if (!Double.isFinite(noSpeechProb) || noSpeechProb < 0 || noSpeechProb > 1) {
            throw new IllegalArgumentException("세그먼트 무음 확률이 올바르지 않습니다: " + noSpeechProb);
        }
    }
}
