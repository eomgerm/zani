import "./styles.css";

import { extractFrameFeatures } from "./features";
import { createBrowserFaceLandmarker, type BrowserFaceLandmarker } from "./mediapipe";
import { createEngagementModel, type EngagementModel, type Prediction } from "./model";
import { RollingFeatureWindow } from "./rolling-window";

const SAMPLE_INTERVAL_MS = 100;
const PREDICTION_INTERVAL_MS = 1_000;
const HISTORY_LIMIT = 12;

const KOREAN_LABELS: Record<string, string> = {
  "Not-Engaged": "참여하지 않음",
  "Barely-Engaged": "낮은 참여",
  Engaged: "참여",
  "Highly-Engaged": "높은 참여",
};

function requiredElement<T extends Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (!element) throw new Error(`필수 UI 요소를 찾을 수 없습니다: ${selector}`);
  return element;
}

const video = requiredElement<HTMLVideoElement>("#camera");
const overlay = requiredElement<HTMLCanvasElement>("#overlay");
const startButton = requiredElement<HTMLButtonElement>("#start");
const stopButton = requiredElement<HTMLButtonElement>("#stop");
const status = requiredElement<HTMLElement>("#status");
const progress = requiredElement<HTMLProgressElement>("#progress");
const progressText = requiredElement<HTMLElement>("#progress-text");
const currentLabel = requiredElement<HTMLElement>("#current-label");
const probabilityList = requiredElement<HTMLElement>("#probabilities");
const historyElement = requiredElement<HTMLElement>("#history");
const cameraPlaceholder = requiredElement<HTMLElement>("#camera-placeholder");

let model: EngagementModel | null = null;
let faceLandmarker: BrowserFaceLandmarker | null = null;
let stream: MediaStream | null = null;
let animationFrame = 0;
let lastSampleAt = -Infinity;
let lastPredictionAt = -Infinity;
let inferenceRunning = false;
const featureWindow = new RollingFeatureWindow();
const history: Prediction[] = [];

function setStatus(message: string, tone: "neutral" | "ready" | "warning" = "neutral"): void {
  status.textContent = message;
  status.dataset.tone = tone;
}

function renderPrediction(prediction: Prediction): void {
  currentLabel.textContent = KOREAN_LABELS[prediction.label] ?? prediction.label;
  probabilityList.replaceChildren(
    ...Array.from(prediction.probabilities, (value, index) => {
      const label = model?.metadata.labels[index] ?? "";
      const row = document.createElement("div");
      row.className = "probability-row";
      row.innerHTML = `
        <div class="probability-copy"><span>${KOREAN_LABELS[label] ?? label}</span><strong>${Math.round(value * 100)}%</strong></div>
        <div class="probability-track"><span style="width: ${value * 100}%"></span></div>
      `;
      return row;
    }),
  );
  history.push(prediction);
  if (history.length > HISTORY_LIMIT) history.shift();
  historyElement.replaceChildren(
    ...history.map((item) => {
      const dot = document.createElement("span");
      dot.className = `history-dot level-${item.probabilities.indexOf(Math.max(...item.probabilities))}`;
      dot.title = KOREAN_LABELS[item.label] ?? item.label;
      return dot;
    }),
  );
}

async function predictIfReady(timestampMs: number): Promise<void> {
  if (!model || inferenceRunning || timestampMs - lastPredictionAt < PREDICTION_INTERVAL_MS) return;
  const tokens = featureWindow.tokens(timestampMs);
  if (!tokens) return;
  inferenceRunning = true;
  lastPredictionAt = timestampMs;
  try {
    renderPrediction(await model.predict(tokens));
    setStatus("최근 10초를 분석하고 있습니다.", "ready");
  } catch (error) {
    setStatus(error instanceof Error ? error.message : "추론 중 오류가 발생했습니다.", "warning");
  } finally {
    inferenceRunning = false;
  }
}

function syncCanvasSize(): void {
  if (overlay.width !== video.videoWidth || overlay.height !== video.videoHeight) {
    overlay.width = video.videoWidth;
    overlay.height = video.videoHeight;
  }
}

function processFrame(timestampMs: number): void {
  if (!stream || !faceLandmarker) return;
  if (video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA && timestampMs - lastSampleAt >= SAMPLE_INTERVAL_MS) {
    lastSampleAt = timestampMs;
    syncCanvasSize();
    const result = faceLandmarker.detect(video, timestampMs);
    faceLandmarker.draw(null, overlay);
    if (result) {
      featureWindow.add(timestampMs, extractFrameFeatures(result));
      const ratio = featureWindow.progress(timestampMs);
      progress.value = ratio;
      progressText.textContent = `${Math.round(ratio * 100)}%`;
      if (ratio < 1) setStatus("얼굴을 유지한 채 10초 창을 수집하고 있습니다.");
      void predictIfReady(timestampMs);
    } else {
      featureWindow.add(timestampMs, null);
      setStatus("얼굴이 보이지 않습니다. 화면 중앙을 바라봐 주세요.", "warning");
    }
  }
  animationFrame = requestAnimationFrame(processFrame);
}

async function startCamera(): Promise<void> {
  if (!model || stream) return;
  startButton.disabled = true;
  setStatus("MediaPipe와 카메라를 준비하고 있습니다.");
  try {
    faceLandmarker = await createBrowserFaceLandmarker();
    stream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: { facingMode: "user", width: { ideal: 1280 }, height: { ideal: 720 } },
    });
    video.srcObject = stream;
    await video.play();
    cameraPlaceholder.hidden = true;
    stopButton.disabled = false;
    lastSampleAt = -Infinity;
    lastPredictionAt = -Infinity;
    animationFrame = requestAnimationFrame(processFrame);
  } catch (error) {
    faceLandmarker?.close();
    faceLandmarker = null;
    stream = null;
    startButton.disabled = false;
    const denied = error instanceof DOMException && error.name === "NotAllowedError";
    setStatus(
      denied ? "카메라 권한이 거부되었습니다. 브라우저 설정에서 허용해 주세요." :
        error instanceof Error ? error.message : "카메라를 시작하지 못했습니다.",
      "warning",
    );
  }
}

function stopCamera(): void {
  cancelAnimationFrame(animationFrame);
  stream?.getTracks().forEach((track) => track.stop());
  stream = null;
  video.srcObject = null;
  cameraPlaceholder.hidden = false;
  faceLandmarker?.close();
  faceLandmarker = null;
  featureWindow.clear();
  overlay.getContext("2d")?.clearRect(0, 0, overlay.width, overlay.height);
  progress.value = 0;
  progressText.textContent = "0%";
  startButton.disabled = model === null;
  stopButton.disabled = true;
  setStatus("카메라가 중지되었습니다.");
}

async function initialize(): Promise<void> {
  startButton.disabled = true;
  stopButton.disabled = true;
  setStatus("ONNX 모델을 확인하고 있습니다.");
  try {
    model = await createEngagementModel();
    startButton.disabled = false;
    setStatus("모델 준비 완료. 카메라 시작을 눌러주세요.", "ready");
  } catch (error) {
    setStatus(
      error instanceof Error ? error.message : "ONNX 모델을 불러오지 못했습니다.",
      "warning",
    );
  }
}

startButton.addEventListener("click", () => void startCamera());
stopButton.addEventListener("click", stopCamera);
window.addEventListener("beforeunload", () => {
  stopCamera();
  void model?.dispose();
});

void initialize();
