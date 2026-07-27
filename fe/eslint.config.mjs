import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    // Statically served assets, including the vendored MediaPipe/onnxruntime wasm
    // glue that scripts/sync-attention-assets.mjs copies in. Not our source.
    "public/**",
  ]),
]);

export default eslintConfig;
