import { createHash } from "node:crypto";
import { access, copyFile, mkdir, readFile } from "node:fs/promises";
import { basename, join } from "node:path";

const EXPECTED_SCHEMA = "mediapipe_98_v1";
const EXPECTED_LABELS = [
  "Not-Engaged",
  "Barely-Engaged",
  "Engaged",
  "Highly-Engaged",
];

function isExactArray(value, expected) {
  return Array.isArray(value) &&
    value.length === expected.length &&
    value.every((item, index) => item === expected[index]);
}

async function requireFile(path, filename) {
  try {
    await access(path);
  } catch {
    throw new Error(`배포 모델 파일이 없습니다: ${filename}`);
  }
}

async function readJson(path, filename) {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    throw new Error(
      `${filename} JSON을 읽을 수 없습니다: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
}

async function sha256(path) {
  return createHash("sha256").update(await readFile(path)).digest("hex");
}

function validateMetadata(metadata) {
  if (metadata?.schema !== EXPECTED_SCHEMA) {
    throw new Error(`지원하지 않는 특징 스키마입니다: ${String(metadata?.schema)}`);
  }
  if (
    metadata.input_name !== "tokens" ||
    !isExactArray(metadata.input_shape, ["batch", 20, 98]) ||
    metadata.output_name !== "logits" ||
    !isExactArray(metadata.labels, EXPECTED_LABELS) ||
    metadata.window_seconds !== 10 ||
    metadata.segment_count !== 20 ||
    metadata.sample_fps !== 10
  ) {
    throw new Error("모델 메타데이터가 frontend 추론 계약과 다릅니다.");
  }
}

export async function validateAttentionModelArtifact({ sourceDir, packageJsonPath }) {
  const modelName = "engagement.onnx";
  const metadataName = "engagement.metadata.json";
  const manifestName = "engagement.manifest.json";
  const modelPath = join(sourceDir, modelName);
  const metadataPath = join(sourceDir, metadataName);
  const manifestPath = join(sourceDir, manifestName);

  await Promise.all([
    requireFile(modelPath, modelName),
    requireFile(metadataPath, metadataName),
    requireFile(manifestPath, manifestName),
    requireFile(packageJsonPath, "package.json"),
  ]);

  const [manifest, packageJson] = await Promise.all([
    readJson(manifestPath, manifestName),
    readJson(packageJsonPath, "package.json"),
  ]);

  if (
    manifest?.artifact_version !== 1 ||
    manifest.schema !== EXPECTED_SCHEMA ||
    manifest.engine?.name !== "onnxruntime-web" ||
    manifest.files?.model?.name !== modelName ||
    manifest.files?.metadata?.name !== metadataName
  ) {
    throw new Error("배포 모델 manifest 형식이 올바르지 않습니다.");
  }

  const engineVersion = packageJson.dependencies?.["onnxruntime-web"];
  if (manifest.engine.version !== engineVersion) {
    throw new Error(
      `모델 엔진 버전 ${String(manifest.engine.version)}이 frontend 의존성 ${String(engineVersion)}과 다릅니다.`,
    );
  }

  const [modelHash, metadataHash] = await Promise.all([
    sha256(modelPath),
    sha256(metadataPath),
  ]);
  if (modelHash !== manifest.files.model.sha256) {
    throw new Error(`${modelName} SHA-256이 manifest와 다릅니다.`);
  }
  if (metadataHash !== manifest.files.metadata.sha256) {
    throw new Error(`${metadataName} SHA-256이 manifest와 다릅니다.`);
  }

  const metadata = await readJson(metadataPath, metadataName);
  validateMetadata(metadata);
  if (metadata.schema !== manifest.schema) {
    throw new Error("manifest와 모델 메타데이터의 특징 스키마가 다릅니다.");
  }

  return {
    modelPath,
    metadataPath,
    manifestPath,
  };
}

export async function syncAttentionModelArtifact({ sourceDir, targetDir, packageJsonPath }) {
  const paths = await validateAttentionModelArtifact({ sourceDir, packageJsonPath });
  await mkdir(targetDir, { recursive: true });
  await Promise.all(
    Object.values(paths).map((sourcePath) =>
      copyFile(sourcePath, join(targetDir, basename(sourcePath))),
    ),
  );
}
