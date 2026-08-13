package com.a105.zani.recording.application.port;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 세션 하나의 최종 녹화 파일 위치. EC2 로컬 디스크에서 찾는다(S3 미사용, FRD §15.2).
 *
 * <p>비어 있으면 "아직 없다"는 뜻이다. 병합이 끝나지 않았거나 녹화가 실패한 세션이며, 오류가 아니라 준비 전 상태다.
 */
public interface LectureMediaPort {

    Optional<Path> findLectureRecording(long sessionId);

    /**
     * 최종 녹화의 1/2 지점 썸네일 <b>원본</b>. 병합 워커가 대표 프레임으로 함께 뽑아 두는 파일이라 별도 추출 작업이 없다.
     *
     * <p>대표 프레임 추출은 best-effort 다(가이드 §6) — 최종 MP4 는 있는데 썸네일만 없는 세션이 있을 수 있고, 그때도 비어 있음이지 오류가 아니다.
     *
     * <p>존재 판정용이다. 목록 주소 발급이 세션 수만큼 부르는 경로라 여기서는 변환 같은 무게 있는 일을 하지 않는다 — 실제 바이트를 내보낼 파일은
     * {@link #findLectureThumbnailForServing(long)} 이 준다.
     */
    Optional<Path> findLectureThumbnail(long sessionId);

    /**
     * 실제로 내보낼 썸네일 파일. 원본 프레임은 장당 0.5~1MB 라 그대로 서빙하면 목록 화면이 카드 수에 비례해 무거워지므로, 표시 크기로 줄인 변환본이 있으면 그쪽을 준다. 변환할 수 없는 원본이면
     * 원본 그대로다.
     *
     * <p>스트리밍 경로 전용이다 — 요청당 한 세션이라 변환 비용(첫 요청 때 수십 ms)을 물어도 된다.
     */
    Optional<Path> findLectureThumbnailForServing(long sessionId);
}
