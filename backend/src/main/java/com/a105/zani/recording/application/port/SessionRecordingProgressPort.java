package com.a105.zani.recording.application.port;

/**
 * 세션의 녹화 진행 상황을 센다(S15P11A105-247).
 *
 * <p>사후 전사가 "파일이 없다" 와 "아직 파일이 도착하지 않았다" 를 구분하기 위해 필요하다. {@code recording_files} 행은 Egress <b>종료 webhook</b> 이 도착해야
 * 만들어지므로, 그 전에 조회하면 목록이 비어 있는 것과 실제로 아무도 말하지 않은 것이 똑같이 보인다.
 */
public interface SessionRecordingProgressPort {

    RecordingProgress load(Long sessionId);

    /**
     * 녹화 진행 수치. <b>네 값 모두 발화를 담는 트랙만 센다.</b>
     *
     * <p>기준을 좁히는 이유는 사후 전사가 상관없는 실패에 막히지 않게 하는 것이다. 화면 공유·카메라 Egress 가 실패하거나 늦어져도 발화는 마이크 트랙에 그대로 있다. 특히 실시간 코칭 링버퍼용
     * {@code START_AUDIO_STREAM_EGRESS} 는 강사 마이크 하나에 대해 파일 Egress 와 <b>따로</b> 만들어지므로, 그것까지 세면 링버퍼 실패가 전사를 영구히 막는다.
     *
     * <p>종류를 알 수 없는 것(V12 이전 행·payload)은 발화 트랙으로 센다. 아니라고 단정하면 잃은 발화를 못 본다.
     *
     * @param unfinishedRecordings 아직 진행 중인 발화 트랙 Egress 수({@code STARTING}·{@code RECORDING})
     * @param failedMicrophoneRecordings 최종 실패한 발화 트랙 Egress 수
     * @param undeliveredOutbox 아직 전달되지 않은 발화 트랙 {@code START_TRACK_EGRESS} 수({@code PENDING}·{@code IN_PROGRESS}). 남아
     *     있으면 그만큼의 Egress 가 아직 시작되지 않았고, 파일이 더 올 수 있다
     * @param failedOutbox 전달을 포기한 발화 트랙 {@code START_TRACK_EGRESS} 수. 그만큼의 트랙은 녹화 자체가 시작되지 않았다
     */
    record RecordingProgress(
            long unfinishedRecordings, long failedMicrophoneRecordings, long undeliveredOutbox, long failedOutbox) {

        public boolean stillRunning() {
            return unfinishedRecordings > 0 || undeliveredOutbox > 0;
        }

        public boolean permanentlyBroken() {
            return failedMicrophoneRecordings > 0 || failedOutbox > 0;
        }
    }
}
