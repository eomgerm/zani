import { createHash } from "node:crypto";
import { mkdtemp, mkdir, readFile, rm, unlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import {
  syncAttentionModelArtifact,
  validateAttentionModelArtifact,
} from "./attention-model-artifact.mjs";

const temporaryRoots = [];

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

async function createArtifactFixture() {
  const root = await mkdtemp(join(tmpdir(), "zani-attention-model-"));
  temporaryRoots.push(root);
  const sourceDir = join(root, "model");
  const packageJsonPath = join(root, "package.json");
  await mkdir(sourceDir);

  const model = Buffer.from("valid-onnx-fixture");
  const metadata = {
    schema: "mediapipe_98_v1",
    input_name: "tokens",
    input_shape: ["batch", 20, 98],
    output_name: "logits",
    labels: ["Not-Engaged", "Barely-Engaged", "Engaged", "Highly-Engaged"],
    window_seconds: 10,
    segment_count: 20,
    sample_fps: 10,
  };
  const metadataContents = `${JSON.stringify(metadata, null, 2)}\n`;
  const manifest = {
    artifact_version: 1,
    schema: "mediapipe_98_v1",
    engine: { name: "onnxruntime-web", version: "1.27.0" },
    files: {
      model: { name: "engagement.onnx", sha256: sha256(model) },
      metadata: {
        name: "engagement.metadata.json",
        sha256: sha256(metadataContents),
      },
    },
  };

  await Promise.all([
    writeFile(join(sourceDir, "engagement.onnx"), model),
    writeFile(join(sourceDir, "engagement.metadata.json"), metadataContents),
    writeFile(join(sourceDir, "engagement.manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`),
    writeFile(
      packageJsonPath,
      `${JSON.stringify({ dependencies: { "onnxruntime-web": "1.27.0" } }, null, 2)}\n`,
    ),
  ]);

  return { sourceDir, packageJsonPath };
}

async function readJson(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

async function writeJson(path, value) {
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`);
}

async function rewriteMetadata(fixture, mutate) {
  const metadataPath = join(fixture.sourceDir, "engagement.metadata.json");
  const manifestPath = join(fixture.sourceDir, "engagement.manifest.json");
  const metadata = await readJson(metadataPath);
  mutate(metadata);
  const metadataContents = `${JSON.stringify(metadata, null, 2)}\n`;
  await writeFile(metadataPath, metadataContents);
  const manifest = await readJson(manifestPath);
  manifest.files.metadata.sha256 = sha256(metadataContents);
  await writeJson(manifestPath, manifest);
}

afterEach(async () => {
  await Promise.all(temporaryRoots.splice(0).map((root) => rm(root, { recursive: true })));
});

describe("validateAttentionModelArtifact", () => {
  it("accepts a complete artifact matching the frontend inference contract", async () => {
    const fixture = await createArtifactFixture();

    const result = await validateAttentionModelArtifact(fixture);

    expect(result.modelPath).toBe(join(fixture.sourceDir, "engagement.onnx"));
    expect(result.metadataPath).toBe(join(fixture.sourceDir, "engagement.metadata.json"));
    expect(result.manifestPath).toBe(join(fixture.sourceDir, "engagement.manifest.json"));
  });

  it.each(["engagement.onnx", "engagement.metadata.json"])(
    "rejects a missing required file: %s",
    async (filename) => {
      const fixture = await createArtifactFixture();
      await unlink(join(fixture.sourceDir, filename));

      await expect(validateAttentionModelArtifact(fixture)).rejects.toThrow(
        `배포 모델 파일이 없습니다: ${filename}`,
      );
    },
  );

  it.each(["engagement.onnx", "engagement.metadata.json"])(
    "rejects changed bytes whose SHA-256 differs: %s",
    async (filename) => {
      const fixture = await createArtifactFixture();
      await writeFile(join(fixture.sourceDir, filename), "corrupted");

      await expect(validateAttentionModelArtifact(fixture)).rejects.toThrow(
        `${filename} SHA-256이 manifest와 다릅니다.`,
      );
    },
  );

  it("rejects metadata from a different feature schema", async () => {
    const fixture = await createArtifactFixture();
    await rewriteMetadata(fixture, (metadata) => {
      metadata.schema = "mediapipe_132_v1";
    });

    await expect(validateAttentionModelArtifact(fixture)).rejects.toThrow(
      "지원하지 않는 특징 스키마입니다: mediapipe_132_v1",
    );
  });

  it("rejects a manifest for a different onnxruntime-web version", async () => {
    const fixture = await createArtifactFixture();
    const manifestPath = join(fixture.sourceDir, "engagement.manifest.json");
    const manifest = await readJson(manifestPath);
    manifest.engine.version = "1.26.0";
    await writeJson(manifestPath, manifest);

    await expect(validateAttentionModelArtifact(fixture)).rejects.toThrow(
      "모델 엔진 버전 1.26.0이 frontend 의존성 1.27.0과 다릅니다.",
    );
  });
});

describe("syncAttentionModelArtifact", () => {
  it("copies the validated model, metadata, and manifest into the generated asset directory", async () => {
    const fixture = await createArtifactFixture();
    const targetDir = join(fixture.sourceDir, "..", "public-models");

    await syncAttentionModelArtifact({ ...fixture, targetDir });

    await expect(readFile(join(targetDir, "engagement.onnx"), "utf8")).resolves.toBe(
      "valid-onnx-fixture",
    );
    await expect(readJson(join(targetDir, "engagement.metadata.json"))).resolves.toMatchObject({
      schema: "mediapipe_98_v1",
    });
    await expect(readJson(join(targetDir, "engagement.manifest.json"))).resolves.toMatchObject({
      artifact_version: 1,
    });
  });
});
