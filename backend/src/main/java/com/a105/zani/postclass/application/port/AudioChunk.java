package com.a105.zani.postclass.application.port;

import java.nio.file.Path;

/**
 * 원본 트랙을 자른 청크 하나(S15P11A105-247).
 *
 * <p>{@code sourceStartMs}·{@code sourceEndMs} 는 <b>원본 기준</b> 시각이다. FFmpeg segment muxer 가 내놓는 CSV 값을 그대로 담는다 — 절대 시간축
 * 계산의 정본이 이 값이다. 청크 파일을 각각 {@code ffprobe} 해 얻은 길이를 누적하면 Opus pre-skip 때문에 경계마다 6.5ms 씩 밀린다(실측: 3분할에서 +19.5ms). 그래서 "청크
 * 길이의 합" 이 아니라 CSV 의 원본 시각을 쓴다.
 *
 * @param index 원본 안에서의 순번(0부터)
 * @param file 청크 파일. 작업 디렉터리 안에 있고 처리 후 삭제된다
 * @param sourceStartMs 원본 기준 시작 시각
 * @param sourceEndMs 원본 기준 종료 시각
 * @param sizeBytes 청크 파일 크기. 업로드 상한 판정에 쓴다
 */
public record AudioChunk(int index, Path file, long sourceStartMs, long sourceEndMs, long sizeBytes) {

    public AudioChunk {
        if (index < 0) {
            throw new IllegalArgumentException("청크 순번은 0 이상이어야 합니다: " + index);
        }
        if (sourceStartMs < 0 || sourceEndMs <= sourceStartMs) {
            throw new IllegalArgumentException("청크 구간이 올바르지 않습니다: " + sourceStartMs + "~" + sourceEndMs);
        }
    }

    public long durationMs() {
        return sourceEndMs - sourceStartMs;
    }
}
