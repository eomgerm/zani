import { createTrackProcessorFrameSource } from "../../src/domains/attention/infrastructure/trackProcessorFrameSource";

export interface AttentionFrameProbeResult {
  readonly firstFrameTimestampMs: number;
  readonly receivedNativeVideoFrame: boolean;
  readonly originalReadyState: MediaStreamTrackState;
  readonly originalMuted: boolean;
  readonly analysisReadyState: MediaStreamTrackState;
}

declare global {
  interface Window {
    runAttentionFrameProbe(): Promise<AttentionFrameProbeResult>;
  }
}

window.runAttentionFrameProbe = async () => {
  const media = await navigator.mediaDevices.getUserMedia({ video: true });
  const originalTrack = media.getVideoTracks()[0];
  if (!originalTrack) throw new Error("fake camera did not provide a video track");

  let analysisTrack: MediaStreamTrack | null = null;
  const frameSourceTrack = {
    clone(): MediaStreamTrack {
      analysisTrack = originalTrack.clone();
      return analysisTrack;
    },
  } as MediaStreamTrack;

  try {
    return await new Promise<AttentionFrameProbeResult>((resolve, reject) => {
      const timeout = window.setTimeout(
        () => reject(new Error("timed out waiting for the first VideoFrame")),
        10_000,
      );
      const source = createTrackProcessorFrameSource({
        track: frameSourceTrack,
        onFrame(frame, timestampMs) {
          frame.close();
          source.stop();
          window.clearTimeout(timeout);
          const stoppedAnalysisTrack = analysisTrack as MediaStreamTrack | null;
          if (stoppedAnalysisTrack === null) {
            reject(new Error("analysis track clone was not created"));
            return;
          }
          resolve({
            firstFrameTimestampMs: timestampMs,
            receivedNativeVideoFrame: frame instanceof VideoFrame,
            originalReadyState: originalTrack.readyState,
            originalMuted: originalTrack.muted,
            analysisReadyState: stoppedAnalysisTrack.readyState,
          });
        },
        onFailure(message) {
          window.clearTimeout(timeout);
          reject(new Error(message));
        },
      });
    });
  } finally {
    originalTrack.stop();
  }
};
