package com.a105.zani.recording.application.gettrackfiles;

import com.a105.zani.recording.domain.model.TrackSource;

/**
 * 세션이 남긴 트랙 파일 하나. 다른 도메인은 {@code RecordingFile} 이 아니라 이 값만 받는다.
 *
 * <p>{@code sessionParticipantId}·{@code trackSource}·{@code startedOffsetMs} 가 모두 {@code null} 일 수 있다. V12 이전에 시작된
 * Egress 의 파일 행은 화자·트랙 종류가 없고, webhook 페이로드에 시각이 없으면 오프셋도 없다. 여기서 걸러 주지 않는 이유는 <b>받는 쪽이 무엇을 없는 값으로 볼지 정해야</b> 하기 때문이다 —
 * 사후 전사는 종류를 모르는 파일을 건너뛰고, 화자나 시각이 없는 파일은 실패로 본다.
 *
 * @param recordingFileId {@code recording_files.id}
 * @param sessionParticipantId 이 트랙의 발행자. legacy 행은 {@code null}
 * @param trackSource 트랙 종류. legacy 행이거나 알 수 없는 값이 저장돼 있으면 {@code null}
 * @param storageKey 세션 루트 기준 상대 경로. 절대 경로가 아니며 이것이 파일 위치의 정본이다
 * @param startedOffsetMs 수업 타임라인 기준 시작 시각. 없으면 {@code null}
 * @param endedOffsetMs 수업 타임라인 기준 종료 시각. 없으면 {@code null}
 */
public record SessionTrackFile(
        Long recordingFileId,
        Long sessionParticipantId,
        TrackSource trackSource,
        String storageKey,
        Long startedOffsetMs,
        Long endedOffsetMs) {}
