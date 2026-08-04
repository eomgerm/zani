package com.a105.zani.recording.application.gettrackfiles;

/**
 * 세션의 녹화가 사후 처리에 넘길 만큼 안정됐는지(S15P11A105-247).
 *
 * <p>이 구분이 필요한 이유는 {@code recording_files} 행이 Egress <b>종료 webhook</b> 이 도착해야 만들어진다는 데 있다. 그 전에 조회하면 목록이 비어 있는데, 그것을
 * "아무도 말하지 않은 수업" 으로 읽으면 이런 일이 벌어진다.
 *
 * <pre>
 * 메모 확정 → 작업 등록 → 전사 실행 → recording_files 가 비어 있음
 *   → 빈 전사 저장 + ANALYZING 전이
 *   → 그 뒤 Egress 종료 webhook 도착
 *   → 실제 OGG 는 영원히 전사되지 않는다
 * </pre>
 *
 * <p>파일 목록만 보면 이 경우와 정상적인 빈 수업을 구별할 방법이 없다. 그래서 목록과 함께 판정을 준다.
 */
public enum RecordingReadiness {
    /** 녹화 작업이 모두 종결됐다. 파일 목록이 그 세션의 최종 결과다 — 비어 있으면 정말로 발화가 없었다. */
    SETTLED,
    /** 아직 진행 중인 Egress 나 전달되지 않은 outbox 가 있다. 파일이 더 올 수 있으므로 기다린다. */
    IN_PROGRESS,
    /** 발화를 담는 Egress 나 outbox 전달이 최종 실패했다. 기다려도 그 구간은 오지 않는다. */
    BROKEN
}
