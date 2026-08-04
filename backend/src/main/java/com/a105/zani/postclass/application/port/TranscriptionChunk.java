package com.a105.zani.postclass.application.port;

import java.time.Instant;
import java.util.List;

import com.a105.zani.postclass.domain.model.TranscriptionChunkStatus;

/**
 * 체크포인트 한 행(S15P11A105-247).
 *
 * <p>{@code sourceStartMs}·{@code sourceEndMs} 는 FFmpeg segment CSV 의 <b>원본 기준</b> 시각이다. 임시 청크 파일은 처리 후 삭제하므로 재시도할 때
 * 원본을 다시 잘라야 하는데, 저장해 둔 이 값과 새 CSV 를 대조하면 경계가 어긋났을 때 조용히 시간축이 밀리는 대신 명확히 실패할 수 있다.
 *
 * <p>{@code segments} 는 <b>청크 기준 상대 시각</b>이다. 절대 시각으로 옮기는 것은 조립하는 쪽이 한다 — 여기 저장할 때 이미 더해 두면, 나중에 오프셋 계산이 잘못됐음을 알게 됐을 때
 * 원본 없이는 되돌릴 수 없다.
 *
 * @param id 체크포인트 행 id
 * @param sessionId 세션 id
 * @param recordingFileId 분할 대상 원본 트랙 파일
 * @param chunkIndex 원본 안에서의 순번(0부터). {@code (recordingFileId, chunkIndex)} 가 고유 기준이다
 * @param sourceStartMs 원본 기준 시작 시각
 * @param sourceEndMs 원본 기준 종료 시각
 * @param status 처리 단계
 * @param attemptCount 이 청크의 GMS 호출 시도 횟수. 상한 판정은 호출자가 한다
 * @param leaseUntil {@code PROCESSING} 선점의 만료점. 그 외 단계에서는 {@code null}
 * @param nextAttemptAt 재시도 대기 해제 시각. 대기 중이 아니면 {@code null}
 * @param segments 성공한 청크의 세그먼트. 그 외에는 빈 목록
 */
public record TranscriptionChunk(
        Long id,
        Long sessionId,
        Long recordingFileId,
        int chunkIndex,
        long sourceStartMs,
        long sourceEndMs,
        TranscriptionChunkStatus status,
        int attemptCount,
        Instant leaseUntil,
        Instant nextAttemptAt,
        List<TranscriptSegment> segments) {

    public TranscriptionChunk {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public long durationMs() {
        return sourceEndMs - sourceStartMs;
    }
}
