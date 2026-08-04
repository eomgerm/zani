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
}
