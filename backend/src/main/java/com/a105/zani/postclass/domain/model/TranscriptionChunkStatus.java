package com.a105.zani.postclass.domain.model;

/**
 * 전사 청크의 처리 단계(S15P11A105-247). V13 스키마의 status 컬럼 값과 일치한다.
 *
 * <p>{@link #SKIPPED_SILENT} 와 {@link #SUCCEEDED} 를 구분하는 이유가 이 enum 의 핵심이다. 둘 다 "더 할 일이 없다" 지만 뜻이 다르다.
 *
 * <ul>
 *   <li>{@code SKIPPED_SILENT} — 사전 무음 판별로 <b>GMS 를 호출하지 않았다</b>
 *   <li>{@code SUCCEEDED} — 호출했고 결과를 받았다. 그 결과가 무음이어서 세그먼트가 비었을 수도 있다
 * </ul>
 *
 * <p>구분하지 않으면 나중에 "이 학생 전사가 왜 비었나" 에 답할 수 없다. 호출을 안 한 것과 호출했더니 발화가 없던 것은 다른 사실이고, 전자는 사전 판별의 임계값 문제일 수 있다.
 */
public enum TranscriptionChunkStatus {
    /** 등록됐고 아직 처리하지 않았다. */
    PENDING,
    /** 어떤 실행이 선점했다. {@code lease_until} 이 지나면 그 실행이 죽은 것으로 보고 회수한다. */
    PROCESSING,
    /** GMS 를 호출해 결과를 받았다. 세그먼트가 비어 있을 수 있다(발화 없음). */
    SUCCEEDED,
    /** 재시도 불가 실패이거나 재시도 상한을 넘었다. */
    FAILED,
    /** 사전 무음 판별로 GMS 를 호출하지 않고 건너뛰었다. */
    SKIPPED_SILENT;

    /** 더 처리할 필요가 없는 단계. 조립은 모든 청크가 여기 있을 때만 시작한다. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED_SILENT;
    }

    /** 최종 transcript 에 결과를 실을 수 있는 단계. FAILED 는 결과가 없다. */
    public boolean contributesToTranscript() {
        return this == SUCCEEDED || this == SKIPPED_SILENT;
    }
}
