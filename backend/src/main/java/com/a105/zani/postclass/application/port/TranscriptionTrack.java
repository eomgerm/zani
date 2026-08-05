package com.a105.zani.postclass.application.port;

import com.a105.zani.recording.domain.model.TrackSource;

/**
 * 전사 대상 트랙 파일 한 개의 조립용 투영(S15P11A105-247).
 *
 * <p>체크포인트는 {@code recordingFileId} 만 들고 있어 "이 청크가 누구의 발화인가" 와 "수업 시작으로부터 몇 ms 지점인가" 를 모른다. 두 값은
 * {@code recording_files} 행에 있고, 이 record 가 그것을 조립 쪽으로 옮긴다.
 *
 * <p><b>한 참가자가 여러 트랙 파일을 가질 수 있다.</b> {@code RecordingTrackEntry} 가 "재접속·재발행은 같은 participant/source 라도 별도 항목" 이라고 정의한다.
 * 그래서 파일마다 {@code startedOffsetMs} 가 다르고, 같은 화자의 여러 파일을 하나의 타임라인으로 병합하는 것이 조립의 일이다.
 *
 * @param recordingFileId {@code recording_files.id}. 체크포인트와 잇는 키
 * @param sessionParticipantId 이 트랙의 발행자. V12 이전 legacy 행은 {@code null} 일 수 있고, 그러면 조립이 실패한다
 * @param trackSource 트랙 종류. legacy 행은 {@code null} 일 수 있다
 * @param livekitTrackSid LiveKit Track SID. <b>{@code null} 을 허용한다</b> — 한 Egress 가 파일을 여러 개 남기면 첫 행만 이 값을 갖는다. 없다고 조립을
 *     막지 않는다: 시간축을 만드는 데 쓰이지 않고 추적용으로만 문서에 실린다
 * @param startedOffsetMs 수업 타임라인 기준 이 파일의 시작 시각. webhook 에 시각이 없으면 {@code null} 이고, 그러면 조립이 실패한다 — 0 으로 가정하면 그 트랙의 발화
 *     전체가 수업 시작 지점으로 밀린다
 */
public record TranscriptionTrack(
        Long recordingFileId,
        Long sessionParticipantId,
        TrackSource trackSource,
        String livekitTrackSid,
        Long startedOffsetMs) {}
