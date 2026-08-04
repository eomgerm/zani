package com.a105.zani.recording.application.gettrackfiles;

import java.util.List;

/**
 * 세션의 녹화 산출물과 그 준비 상태(S15P11A105-247).
 *
 * <p>파일 목록만 돌려주지 않는 이유는 {@link RecordingReadiness} 에 적었다 — <b>빈 목록의 뜻이 상태에 따라 정반대</b>다.
 *
 * @param files 트랙 파일 전체. 걸러 내지 않는다 — 무엇을 쓸지는 받는 쪽이 정한다
 * @param readiness 이 목록을 최종 결과로 믿어도 되는지
 */
public record SessionRecordingSnapshot(List<SessionTrackFile> files, RecordingReadiness readiness) {

    public SessionRecordingSnapshot {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
