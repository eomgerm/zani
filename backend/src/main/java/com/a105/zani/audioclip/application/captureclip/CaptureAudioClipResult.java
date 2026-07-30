package com.a105.zani.audioclip.application.captureclip;

/**
 * 클립 캡처 결과.
 *
 * @param transcribed 전사를 수행했는지. false 면 확보된 오디오가 최소 길이에 못 미쳐 건너뛴 것이다
 * @param transcript 전사 텍스트. transcribed 가 false 면 null
 * @param availableMs 시도 시점에 버퍼가 갖고 있던 오디오 길이. 건너뛴 이유를 판단할 근거다
 */
public record CaptureAudioClipResult(boolean transcribed, String transcript, long availableMs) {

    static CaptureAudioClipResult skipped(long availableMs) {
        return new CaptureAudioClipResult(false, null, availableMs);
    }

    static CaptureAudioClipResult of(String transcript, long availableMs) {
        return new CaptureAudioClipResult(true, transcript, availableMs);
    }
}
